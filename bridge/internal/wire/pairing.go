package wire

import "time"

// PairingCodeTTL is how long a self-service pairing code stays redeemable.
const PairingCodeTTL = 10 * time.Minute

// The six DTOs below are the self-service pairing wire contract, served
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
