package wire

import (
	"bytes"
	"compress/flate"
	"errors"
	"io"
)

// A terminal render grid is large and highly repetitive -- measured at 186,821
// bytes on the wire for one 282x79 pane, of which 143KB is scrollback -- and
// nothing in the stack compressed it. Transport-level deflate cannot help
// either: every terminal frame is sealed before it is written, and ciphertext
// does not compress. So compression has to happen on the plaintext, inside the
// AEAD, which is what these two functions are for.
//
// A compressed payload is the JSON with a one-byte codec tag in front, and the
// whole thing is then sealed. Putting the tag inside the AEAD means it is
// authenticated along with the payload and stays invisible to the deliberately
// blind relay.
//
// Mirrored in the app as data/e2e/PayloadCodec.kt -- keep the two in step.
const (
	// PayloadIdentity marks a payload that is already the plaintext JSON.
	// Frames too small to be worth compressing use this, so a tagged stream
	// never grows a frame.
	PayloadIdentity byte = 0

	// PayloadDeflate marks a payload compressed with raw DEFLATE (RFC 1951),
	// with no zlib or gzip wrapper. The app inflates it with Inflater(nowrap
	// = true) to match.
	PayloadDeflate byte = 1

	// PayloadDeflateStream marks one frame's worth of a DEFLATE stream that
	// stays open for the life of a socket, so each frame is compressed against
	// everything already sent on it rather than from scratch.
	//
	// Consecutive terminal frames are nearly identical, and that repetition is
	// invisible to a compressor restarted every frame. Measured over twelve
	// live frames: 607 bytes each compressed alone, 61 bytes each once the
	// window had warmed -- the first output frame after a replay still costs
	// ~400 bytes, because the only thing behind it is a frame of a different
	// shape.
	//
	// The cost is that frames stop standing alone. A frame that never arrives,
	// or arrives and will not decode, leaves the decompressor unable to read
	// anything after it, so the app closes the socket and resyncs from a fresh
	// replay instead of skipping the frame (see TerminalSocket's
	// DesyncException). That is the same bargain delta frames already made.
	PayloadDeflateStream byte = 2
)

// maxPayloadSize bounds Decode's output. Frames are authenticated, so an
// oversized one means a compromised or broken agent rather than an attacker
// -- this just makes that show up as a dropped frame instead of an
// out-of-memory. Comfortably above the largest real grid measured (~370KB for
// a full-screen pane with scrollback).
const maxPayloadSize = 8 << 20

// ErrPayloadTooLarge is returned when a payload inflates past
// [maxPayloadSize].
var ErrPayloadTooLarge = errors.New("payload too large")

// EncodePayload tags plaintext for a client that has negotiated compression,
// deflating it when that actually helps.
//
// Compression is skipped whenever the result would not be smaller, so acks and
// other short frames -- which deflate makes bigger, not smaller -- go out as
// [PayloadIdentity]. A compression failure falls back to identity too: a
// frame that costs its full size is strictly better than no frame at all.
func EncodePayload(plaintext []byte) []byte {
	deflated, err := deflate(plaintext)
	if err != nil || len(deflated) >= len(plaintext) {
		return append([]byte{PayloadIdentity}, plaintext...)
	}
	return append([]byte{PayloadDeflate}, deflated...)
}

// DecodePayload reverses [EncodePayload]. Only used by tests on this side --
// the app is the real consumer -- but it keeps the pair honest by round-trip.
func DecodePayload(payload []byte) ([]byte, error) {
	if len(payload) < 1 {
		return nil, errors.New("empty payload")
	}
	body := payload[1:]
	switch payload[0] {
	case PayloadIdentity:
		return body, nil
	case PayloadDeflate:
		r := flate.NewReader(bytes.NewReader(body))
		defer func() { _ = r.Close() }()
		out, err := io.ReadAll(io.LimitReader(r, maxPayloadSize+1))
		if err != nil {
			return nil, err
		}
		if len(out) > maxPayloadSize {
			return nil, ErrPayloadTooLarge
		}
		return out, nil
	default:
		return nil, errors.New("unknown payload codec")
	}
}

// deflateLevel is flate.DefaultCompression (6). Measured on a real 186,786-byte
// grid frame: level 1 gave 6.5x at 0.47ms, level 3 7.5x at 0.62ms, level 6 8.6x
// at 1.70ms, level 9 only 9.1x at 7.14ms. Level 9's cost is not worth 6% more
// ratio; level 6's 1.7ms is noise beside the 1.0-4.45s the replay RPC that
// produced the frame already took (see [replayTimeout] in server/terminal.go).
const deflateLevel = flate.DefaultCompression

func deflate(plaintext []byte) ([]byte, error) {
	var buf bytes.Buffer
	w, err := flate.NewWriter(&buf, deflateLevel)
	if err != nil {
		return nil, err
	}
	if _, err := w.Write(plaintext); err != nil {
		return nil, err
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// StreamEncoder compresses a socket's frames against one shared DEFLATE
// window. Create one per socket; never copy it, since the flate.Writer holds a
// pointer to the buffer field.
//
// Not safe for concurrent use, and it must be called in the same order the
// frames go out on the wire: the app inflates them through a single decoder,
// so a chunk that arrives before the one it was compressed against cannot be
// read. Both of those hold in server/terminal.go because encode, encrypt and
// write share one mutex-guarded critical section.
type StreamEncoder struct {
	buf bytes.Buffer
	w   *flate.Writer
}

// NewStreamEncoder returns an encoder whose window starts empty, which is what
// makes a reconnect a clean resync: the client's decoder starts empty too.
func NewStreamEncoder() (*StreamEncoder, error) {
	e := &StreamEncoder{}
	w, err := flate.NewWriter(&e.buf, deflateLevel)
	if err != nil {
		return nil, err
	}
	e.w = w
	return e, nil
}

// Encode returns plaintext as a tagged chunk of the ongoing stream.
//
// Unlike [EncodePayload] there is no identity fallback for frames compression
// does not shrink. Skipping the stream for one frame would leave the app's
// decoder a frame behind for every frame after it, so a rare larger ack is
// accepted as the price of the window staying in step.
//
// Flush, not Close: Close would end the stream and throw the window away.
// Flush ends the chunk with an empty stored block (00 00 FF FF) and keeps
// everything written so far available to compress the next frame against.
func (e *StreamEncoder) Encode(plaintext []byte) ([]byte, error) {
	e.buf.Reset()
	if _, err := e.w.Write(plaintext); err != nil {
		return nil, err
	}
	if err := e.w.Flush(); err != nil {
		return nil, err
	}
	// Copies, which matters: buf.Bytes() aliases storage that Reset reuses on
	// the next call.
	return append([]byte{PayloadDeflateStream}, e.buf.Bytes()...), nil
}
