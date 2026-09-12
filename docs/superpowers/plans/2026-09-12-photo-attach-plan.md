# Plan: attach a photo to a terminal pane

Design: `docs/superpowers/specs/2026-09-12-photo-attach-design.md`.
Issue: `cmux-app-ej0`.

Three commits. The first is the wire and the bridge, and is shaped by
invariant 3: the new `TerminalUp` field must land in Go and Kotlin together
with tests on both sides, so the Kotlin DTO change and a bare send path are in
it too. The UI is deliberately kept out of it. The second is the whole phone
experience. The third is the pre-existing socket read limit, which is its own
logical item but has to be in place before the feature is deployed.

## Commit 1 — `attach` up-frame, bridge landing, Kotlin DTO

**Go**

- `internal/wire/terminal.go`: `TerminalUp` gains `Image string
  json:"image,omitempty"` and `Name string json:"name,omitempty"`; the type
  comment lists `"attach"`.
- `internal/server/attachments.go` (new): `attachmentStore` with a directory,
  a cap, and `save(image []byte) (path string, err error)` — sniff (JPEG, PNG,
  WebP, GIF, HEIC by magic bytes), cap check, `0700` dir on first use, temp
  file + rename to `<yyyymmdd-HHMMSS>-<6 hex>.<ext>` with `0600`. Retention
  per decision 5 in `sweep()`, called at server start and daily.
- `internal/server/terminal.go`: `case "attach"` in `terminalReadLoop` —
  decode base64, `save`, `mobile.terminal.paste` of `path + " "`, nudge the
  poll loop (and add the missing nudge to `case "paste"` while there), ack.
  A save failure acks `ok:false` and logs the reason without the content.
- Config: `AttachmentsDir` with the decision-4 default, `~/` expanded like the
  other path fields.

Tests (`attachments_test.go`, `terminal_encryption_test.go`):
- Each accepted format gets its sniffed extension; a text file, an empty
  frame and an over-cap image are refused before anything is written.
- The written file is `0600` in a `0700` directory and no temp file remains.
- The name hint never influences the path (a hint of `../../x` lands as a
  timestamped name).
- Through the fake cmux script: an `attach` frame produces exactly one
  `mobile.terminal.paste` whose text is the written path plus a trailing
  space, followed by an ack with the same seq; a refused frame produces no
  paste and an `ok:false` ack.
- Sweep deletes only files older than the retention and only inside the
  directory.

**Kotlin**

- `model/Dtos.kt`: `TerminalUp` gains `image: String? = null`, `name: String?
  = null`; `TerminalUpType.ATTACH`.
- `DtosTest`: an attach frame round-trips and omits the new fields when null,
  so existing frames are byte-identical to before.

Gate: both build-and-verify lines from `CLAUDE.md`.

## Commit 2 — the phone side

- `ui/terminal/AttachImage.kt` (new): `ImageSizing` — pure functions for
  target dimensions and `inSampleSize` given source dimensions and the 1568
  px bound; `prepareForSend(uri, original: Boolean)` — decode with EXIF
  orientation applied, scale, JPEG q85, or the original bytes when asked and
  under the cap.
- `ui/terminal/TerminalScreen.kt`: attach button beside the input bar; a
  source sheet (Gallery via `PickVisualMedia`, Clipboard via the platform
  `ClipboardManager` primary clip, disabled when it holds no image); a
  confirmation sheet with thumbnail, size, the send-original switch and the
  EXIF note per decision 7.
- `ui/terminal/TerminalViewModel.kt`: `attach(bytes, name)` builds the frame
  and hands it to `DeliveryTracker.send`.
- `ui/terminal/DeliveryTracker.kt`: per-message stale threshold — a function
  of payload size, so a large upload is "sending" not "delayed" — replacing
  the single `ACK_STALE_MS` for that message.
- Strings in `res/values/strings.xml`.

Tests:
- `ImageSizingTest` (plain JVM): a 4000×3000 source → 1568×1176, sample size
  2; a 1200×800 source is untouched; a 3000×4000 portrait keeps its
  orientation in the target.
- Robolectric: an EXIF-rotated fixture comes out upright and under 1568 px on
  its longest side; an over-cap "original" request falls back to the
  downscaled bytes.
- `DeliveryTrackerTest`: a large message is still `SENDING` at 1.5 s and
  `DELAYED` only after its own threshold.

Gate as above. Then the live test from the design's "How this gets believed",
on the phone over the relay.

## Commit 3 — terminal socket read limit

`internal/server/terminal.go`: `SetReadLimit` on the terminal connection at
the attachment cap plus base64 and framing overhead. A frame over it ends the
socket the same way a decrypt failure does. Test: an oversize frame closes
the socket and nothing is pasted.

This is independent of the feature and would be worth landing even if the
feature were dropped; it is last only so the first two commits are not
blocked on it.

## Not in this plan

Inbox attachments, multiple images per send, chunked upload beyond the cap,
camera capture. Each is a bead of its own if wanted.
