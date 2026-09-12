package server

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Minimal valid leading bytes for each format the store accepts. The store
// looks no further than the signature, so nothing after it needs to be real.
var imageSignatures = map[string][]byte{
	"jpg":  {0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'},
	"png":  []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"),
	"gif":  []byte("GIF89a\x01\x00\x01\x00"),
	"webp": []byte("RIFF\x24\x00\x00\x00WEBPVP8 "),
	"heic": []byte("\x00\x00\x00\x18ftypheic\x00\x00\x00\x00"),
}

func newTestAttachmentStore(t *testing.T) *AttachmentStore {
	t.Helper()
	return NewAttachmentStore(filepath.Join(t.TempDir(), "attachments"))
}

func TestEachAcceptedFormatGetsTheExtensionItsBytesSay(t *testing.T) {
	store := newTestAttachmentStore(t)
	for ext, sig := range imageSignatures {
		path, err := store.Save(sig)
		if err != nil {
			t.Fatalf("%s: %v", ext, err)
		}
		if !strings.HasSuffix(path, "."+ext) {
			t.Fatalf("%s bytes landed as %s", ext, path)
		}
		got, err := os.ReadFile(path)
		if err != nil || !bytes.Equal(got, sig) {
			t.Fatalf("%s: file content differs or unreadable: %v", ext, err)
		}
	}
}

func TestWhatIsNotAnImageIsRefusedBeforeAnythingIsWritten(t *testing.T) {
	store := newTestAttachmentStore(t)
	cases := map[string]struct {
		image []byte
		want  error
	}{
		"empty":         {nil, errAttachmentEmpty},
		"text":          {[]byte("#!/bin/sh\nrm -rf ~\n"), errAttachmentNotImage},
		"html":          {[]byte("<html><body>hi</body></html>"), errAttachmentNotImage},
		"ftyp not heif": {[]byte("\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00"), errAttachmentNotImage},
		"over the cap":  {append(bytes.Clone(imageSignatures["jpg"]), make([]byte, attachmentMaxBytes)...), errAttachmentTooLarge},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := store.Save(tc.image)
			if !errors.Is(err, tc.want) {
				t.Fatalf("want %v, got %v", tc.want, err)
			}
		})
	}
	if _, err := os.Stat(store.dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("a refused image must not create the directory, stat: %v", err)
	}
}

func TestALandedFileIsPrivateAndNoPartialIsLeftBehind(t *testing.T) {
	store := newTestAttachmentStore(t)
	path, err := store.Save(imageSignatures["png"])
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("file mode %o, want 600", info.Mode().Perm())
	}
	dir, err := os.Stat(store.dir)
	if err != nil {
		t.Fatal(err)
	}
	if dir.Mode().Perm() != 0o700 {
		t.Fatalf("dir mode %o, want 700", dir.Mode().Perm())
	}
	entries, err := os.ReadDir(store.dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("want exactly the landed file in the directory, got %d entries", len(entries))
	}
}

// The name is the bridge's: a timestamp, six hex digits and a sniffed
// extension. Two saves in the same second still get distinct paths, and the
// path has no whitespace to quote when it is pasted.
func TestTheNameIsTheBridgesNotThePhones(t *testing.T) {
	store := newTestAttachmentStore(t)
	fixed := time.Date(2026, 9, 12, 22, 40, 5, 0, time.Local)
	store.now = func() time.Time { return fixed }
	a, err := store.Save(imageSignatures["jpg"])
	if err != nil {
		t.Fatal(err)
	}
	b, err := store.Save(imageSignatures["jpg"])
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatalf("two saves in one second collided: %s", a)
	}
	base := filepath.Base(a)
	if !strings.HasPrefix(base, "20260912-224005-") || len(base) != len("20260912-224005-abcdef.jpg") {
		t.Fatalf("unexpected name shape: %s", base)
	}
	if strings.ContainsAny(a, " \t\n") {
		t.Fatalf("path contains whitespace: %q", a)
	}
}

func TestSweepRemovesOnlyWhatIsPastRetentionAndOnlyInTheDirectory(t *testing.T) {
	store := newTestAttachmentStore(t)
	now := time.Date(2026, 9, 12, 12, 0, 0, 0, time.Local)
	store.now = func() time.Time { return now }
	old, err := store.Save(imageSignatures["jpg"])
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(old, now.Add(-8*24*time.Hour), now.Add(-8*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	fresh, err := store.Save(imageSignatures["png"])
	if err != nil {
		t.Fatal(err)
	}
	nested := filepath.Join(store.dir, "sub")
	if err := os.Mkdir(nested, 0o700); err != nil {
		t.Fatal(err)
	}
	nestedOld := filepath.Join(nested, "keep.jpg")
	if err := os.WriteFile(nestedOld, imageSignatures["jpg"], 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(nestedOld, now.Add(-30*24*time.Hour), now.Add(-30*24*time.Hour)); err != nil {
		t.Fatal(err)
	}

	removed, err := store.Sweep()
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("removed %d, want 1", removed)
	}
	if _, err := os.Stat(old); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("the eight-day-old file should be gone")
	}
	for _, kept := range []string{fresh, nestedOld} {
		if _, err := os.Stat(kept); err != nil {
			t.Fatalf("%s should have been kept: %v", kept, err)
		}
	}
}

func TestSweepOnADirectoryThatDoesNotExistYetIsNothingToDo(t *testing.T) {
	store := newTestAttachmentStore(t)
	if removed, err := store.Sweep(); err != nil || removed != 0 {
		t.Fatalf("removed=%d err=%v", removed, err)
	}
}
