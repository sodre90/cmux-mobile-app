package wire

// TestPushDeviceReq and TestPushDeviceResp are bridge-internal only: the
// relay's own /devices/test-push handler (internal/relay/testpush.go) sends
// this to the tenant's agent, over the already-established trusted tunnel,
// asking it to build one e2e-encrypted test-push payload for a specific
// already-paired device -- the relay holds the FCM credentials and the
// calling device's identity for relay-mode push, but only the agent holds
// the e2e keys needed to encrypt real content for it (see
// internal/server/test_push.go's buildTestPushCiphertext). The app-facing
// endpoint devices actually call (POST /devices/test-push) takes no request
// body and returns a bare {"ok":true}/{"error":"..."} like
// /devices/register already does, so neither of these two types is ever
// sent to or parsed by the Android app -- there is no Kotlin mirror for
// them, unlike every other type in this package.
type TestPushDeviceReq struct {
	DeviceID string `json:"device_id"`
}

// TestPushDeviceResp carries the resulting ciphertext, ready for the relay
// to embed under its own FCM push's "e2e" data key exactly like
// EventFrame.EncryptedPush already does for a real attention push.
type TestPushDeviceResp struct {
	Ciphertext string `json:"ciphertext"`
}
