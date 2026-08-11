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

// DevicePairResp is the response to POST /devices/pair.
type DevicePairResp struct {
	Token    string `json:"token"`
	TenantID string `json:"tenant_id"`
}

// PairStatusResp is the response to GET /devices/pair-status/{code}, the
// route a phone polls to learn whether the operator accepted it. It carries
// one enum value -- pending, confirmed or refused -- and deliberately nothing
// else: no token, no key material, no tenant id, because the route is
// unauthenticated by design (see internal/pairing).
type PairStatusResp struct {
	State string `json:"state"`
}
