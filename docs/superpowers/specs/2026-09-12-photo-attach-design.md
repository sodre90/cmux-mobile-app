# Attach a photo to a terminal pane — Design

- **Date:** 2026-09-12
- **Status:** Approved 2026-09-12 — ready for implementation (see Decisions)
- **Author:** perdos
- **Bead:** `cmux-app-ej0`

## Decisions

Taken on 2026-09-12:

| # | decision | outcome |
|---|---|---|
| 1 | How the photo reaches the agent | **file on the Mac, path pasted into the pane** — verified live, see below |
| 2 | Photo size | **downscale by default** (longest side 1568 px, JPEG q85), with a per-send "send original" option |
| 3 | Where it lives in the app | **terminal screen only**; Inbox replies are out of scope |

Taken on 2026-09-12 after review; each was the recommendation:

| # | decision | outcome |
|---|---|---|
| 4 | Where the bridge writes attachments | `~/.config/cmux-bridge/attachments/` — beside the bridge's own state, no spaces, one place to clear. Not the pane's working directory, which would litter repos |
| 5 | Retention | delete files older than **7 days**, at bridge start and once a day |
| 6 | Cap for "send original" | **10 MB**; above it the option is greyed out and the downscaled copy is sent. No chunked upload |
| 7 | EXIF on originals | **send as-is**, with a note in the UI that GPS and camera data reach the Mac. Downscaled sends drop it as a side effect of the re-encode |

## Context

The user wants to hand an agent a photo from the phone: a screenshot from the
clipboard, or a picture from the gallery. Today the terminal screen can send
keystrokes and text paste; there is no way to get an image across.

cmux has no image RPC. The bridge talks to it through `mobile.terminal.input`
and `mobile.terminal.paste`, both text. So the image has to land on the Mac
as a file and the agent has to be told where it is.

## Verified live: a pasted path is an attached image

Tested 2026-09-12 in a scratch workspace (created and closed by UUID) running
Claude Code 2.1.270, driving it exactly the way the bridge would:

```
cmux rpc mobile.terminal.paste '{"surface_id":"…","text":"/…/attach-test-green.png "}'
cmux rpc mobile.terminal.input '{"surface_id":"…","text":"What single colour is this image?\r"}'
```

The prompt showed `❯ [Image #1] What single colour is this image?` and the
answer was **Green** — the image was attached, not echoed as text. A second
run with the file in a directory containing a space, pasted mid-sentence after
typed text, gave `[Image #2]` and **Magenta**. So:

- `mobile.terminal.paste` of a path is enough; no clipboard, no keystroke
  tricks.
- Placement inside a half-typed prompt works.
- A space in the path survives for Claude Code. The attachment directory is
  still kept spaceless (decision 4) because a shell or another agent may not
  be as forgiving, and the bridge cannot know what is in the pane.

`mobile.terminal.paste` returns `"submitted": true`; that means the paste was
delivered, not that the prompt was submitted — the prompt stayed open.

## Design

### Wire: one new up-frame on the terminal socket

```
TerminalUp{ type: "attach", seq, image: <base64>, name: "<hint>" }
```

The terminal socket already carries e2e-encrypted up-frames with seq/ack
delivery tracking, and the relay is a blind tunnel for it, so the image needs
no new endpoint, no relay change, and no new crypto. The encrypted-body limit
on HTTP requests (8 KB, `encryption.go`) is why an HTTP path is not used.

`image` is the raw file bytes, base64. `name` is a hint only (the phone's idea
of a name, e.g. `IMG_2041`); the bridge never uses it to form a path.

The field lands in Go `wire.TerminalUp` and Kotlin `TerminalUp` in the same
commit, with tests on both sides (invariant 3). There is no relay mirror of
this DTO.

### Bridge: write, sniff, paste, ack

On an `attach` frame the bridge:

1. Rejects the frame — ack `ok:false` — if the decoded image is over the cap
   (decision 6) or its magic bytes are not JPEG, PNG, WebP, GIF or HEIC. The
   extension comes from the sniff, never from the phone.
2. Writes it to `<attachments>/<yyyymmdd-HHMMSS>-<6 random hex>.<ext>`, dir
   `0700`, file `0600`, via a temp file and rename so a half-written file is
   never visible.
3. Pastes `<path> ` (trailing space) into the pane with
   `mobile.terminal.paste`, then nudges the poll loop so the path shows up on
   the phone immediately — `input` already does this, `paste` does not, and
   that omission is fixed for both in passing.
4. Acks the seq with the RPC's outcome, as every other up-frame does.

Nothing about the image reaches the log (invariant 5): the log line is the
path (which carries the sniffed type), the byte count and the name hint.

**The socket gets a read limit.** The bridge reads terminal up-frames with no
`SetReadLimit` today — an unbounded read on an authenticated but
untrusted-after-pairing peer, and a pre-existing gap independent of this
feature. The limit is set to the attachment cap plus base64 and framing
overhead, and a frame over it closes the socket.

### App: attach button, picker or clipboard, downscale, confirm, send

An attach button next to the input bar. It opens a small sheet with two
sources:

- **Gallery** — Android Photo Picker (`PickVisualMedia`); no storage
  permission on any supported API level.
- **Clipboard** — the Android `ClipboardManager`'s primary clip when its item
  carries an image URI (a copied screenshot). Compose's
  `LocalClipboardManager` is text-only, so this goes through the platform
  manager and `contentResolver`. The option is disabled when the clipboard has
  no image.

The picked image is decoded with EXIF orientation applied **before** scaling
— otherwise portrait photos arrive rotated — then scaled so the longest side
is 1568 px and encoded as JPEG q85. That is the size Claude's API resizes to
anyway, so the agent loses nothing; measured on phone photos it is 200–500 KB
against 3–8 MB originals. Screenshots are usually under 1568 px already and
pass through unscaled but re-encoded.

A confirmation sheet shows a thumbnail, the size that will be sent, and a
"send original (N MB)" switch (decision 6 greys it out above the cap, decision
7 words the EXIF note). This mirrors the existing multi-line paste
confirmation: the phone should not fire multi-megabyte uploads on a mis-tap.

Send goes through `DeliveryTracker.send` like every other up-frame, so the
existing seq/ack machinery reports delivery. Two things it does not do well
for an upload, both addressed:

- `ACK_STALE_MS` is 1.5 s. A 400 KB frame on mobile data can take longer, an
  original far longer, and the UI would show "delayed" mid-upload. The tracker
  gets a per-message stale threshold derived from the frame size, so the
  status stays "sending" for as long as an upload of that size plausibly
  takes.
- OkHttp refuses to enqueue past 16 MiB of unsent websocket data and
  `send` returns false; the app already maps that to "never left the phone".
  The cap in decision 6 keeps a single frame well under that.

`Bitmap` is unavailable in plain JVM tests; Robolectric is already in the
project. The sizing arithmetic (target dimensions, `inSampleSize`) is a pure
function with plain unit tests; the bitmap operations stay thin and get one
Robolectric test each.

### What the agent sees

A path, as if the user had typed it. Claude Code turns it into an attached
image; a shell gets a path it can `open`; any other agent gets whatever it
does with a path. The bridge does not know or care what is in the pane, which
is the same stance it takes on every keystroke.

## Security

- **Crypto unchanged** (invariant 4): the frame is one more AEAD-sealed
  terminal up-frame with the existing replay-protected counter.
- **Relay stays blind**: it tunnels the socket and never sees the frame type.
- **The phone never names a file on the Mac.** Path = bridge-chosen directory
  + timestamp + random + sniffed extension.
- **Content is validated by magic bytes**, and a non-image is refused before
  it is written.
- **No image bytes in logs.**
- **Bounded**: a size cap on the frame, a read limit on the socket, and a
  retention rule on disk.
- **Originals carry EXIF** (decision 7). Downscaled images do not — the
  re-encode drops it.

## What this does not do

- Inbox replies (decision 3).
- Multiple images per send; one at a time.
- Camera capture — the gallery picker covers "take a photo, then attach it".
- HEIC originals: the file is written as `.heic` and pasted, but whether the
  agent can read it is the agent's business. Downscaled sends are always JPEG,
  so the default path never produces one.
- Anything on the Mac side beyond a file and a paste. No clipboard, no
  `open`, no AppleScript.

## How this gets believed

1. Unit tests on both sides of the wire field, the sniff/cap/write/paste
   sequence on the bridge, and the sizing arithmetic on the phone.
2. A Robolectric test that a rotated EXIF sample comes out upright and under
   the size limit.
3. Live: a gallery photo and a clipboard screenshot each attached from the
   phone to a Claude Code pane, over the relay on mobile data, and Claude
   describes the image. The bridge log shows the path and byte count and
   nothing else; the attachments directory holds `0600` files.
