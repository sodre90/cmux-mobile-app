package wire

import "time"

// PairingCodeTTL is how long a self-service pairing code stays redeemable.
const PairingCodeTTL = 10 * time.Minute

// PairingConfirmTTL is how long the operator has to answer the agent's
// fingerprint prompt before a redeemed pairing resolves itself to refused.
// Long enough for a human to walk to the Mac, short enough that a phone left
// waiting gets a real answer instead of hanging.
const PairingConfirmTTL = 5 * time.Minute

// The DTOs below are the self-service pairing wire contract, served
// identically by the relay (internal/relay) and direct mode
// (internal/server).

// PairingCodeResp is the response to POST /agent/pairing-code.
type PairingCodeResp struct {
	Code      string `json:"code"`
	ExpiresAt string `json:"expires_at"`
	TenantID  string `json:"tenant_id"`
}

// NewPairingCodeReq is the request body for POST /agent/pairing-code.
type NewPairingCodeReq struct {
	AgentPubkey string `json:"agent_pubkey"`
}

// PairingCodeStatusResp is the response to GET /agent/pairing-code/{code}.
type PairingCodeStatusResp struct {
	Redeemed     bool   `json:"redeemed"`
	DevicePubkey string `json:"device_pubkey,omitempty"`
	TokenHash    string `json:"token_hash,omitempty"`
}

// AbortPairingResp is the response to DELETE /agent/pairing-code/{code}.
// Revoked distinguishes an abort that destroyed a credential the phone was
// already holding from one that arrived before any redemption.
type AbortPairingResp struct {
	Revoked bool `json:"revoked"`
}

// PairingCodeInfoResp is the response to GET /devices/pair-info/{code}.
type PairingCodeInfoResp struct {
	AgentPubkey string `json:"agent_pubkey"`
	ExpiresAt   string `json:"expires_at"`
	TenantID    string `json:"tenant_id"`
}

// DevicePairReq is the request body for POST /devices/pair.
type DevicePairReq struct {
	Code         string `json:"code"`
	DevicePubkey string `json:"device_pubkey"`
	Name         string `json:"name"`
}

// FCMClientConfig is the Firebase client configuration a phone needs to
// initialise FCM itself, handed over once at pairing so the app no longer has
// to be rebuilt with a google-services.json compiled into its resources.
//
// None of these four values is a secret: Firebase ships all of them in the
// clear inside every distributed APK that has push configured, and none of
// them authorises sending a message -- that needs the service-account key,
// which stays on this machine (fcm_credentials) and is never sent anywhere.
type FCMClientConfig struct {
	ProjectID string `json:"project_id"`
	AppID     string `json:"app_id"`
	APIKey    string `json:"api_key"`
	SenderID  string `json:"sender_id"`
}

// Configured reports whether every field needed to build Firebase's
// client-side options is present. Firebase rejects a partial set, so a
// half-filled config is treated as no config at all rather than sent to a
// phone that could only fail on it.
func (c FCMClientConfig) Configured() bool {
	return c.ProjectID != "" && c.AppID != "" && c.APIKey != "" && c.SenderID != ""
}

// PartiallyConfigured reports a config the operator started and did not
// finish: at least one field set, but not all four. That combination is
// silently useless -- the block is withheld, so every phone pairs without
// push and nothing on the phone can say why -- which is exactly the case
// worth warning about at startup.
func (c FCMClientConfig) PartiallyConfigured() bool {
	if c.Configured() {
		return false
	}
	return c.ProjectID != "" || c.AppID != "" || c.APIKey != "" || c.SenderID != ""
}

// DevicePairResp is the response to POST /devices/pair.
//
// FCM is omitted entirely unless the operator configured all four client
// fields, so a phone paired against a bridge without push sees exactly the
// response it saw before this field existed.
type DevicePairResp struct {
	Token    string           `json:"token"`
	TenantID string           `json:"tenant_id"`
	FCM      *FCMClientConfig `json:"fcm,omitempty"`
}

// PairStatusResp is the response to GET /devices/pair-status/{code}, the
// route a phone polls to learn whether the operator accepted it. It carries
// one enum value -- pending, confirmed or refused -- and deliberately nothing
// else: no token, no key material, no tenant id, because the route is
// unauthenticated by design (see internal/pairing).
type PairStatusResp struct {
	State string `json:"state"`
}
