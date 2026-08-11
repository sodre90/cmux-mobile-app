package wire

// The DTOs below are the agent-facing device-admin contract, served
// identically by the relay (internal/relay) and direct mode
// (internal/server) via internal/devices.
//
// Unlike the pairing DTOs next door, these have no Kotlin mirror: the phone
// never calls these routes. They are agent<->server only, so the
// wire-lockstep invariant in CLAUDE.md has nothing to keep in step here.

// AgentDevice is one paired device as reported to the agent that owns it.
//
// HasFCM is a bool rather than the registration token itself: the agent has
// no use for that value, and a credential that does not need to travel
// should not.
type AgentDevice struct {
	Name      string `json:"name"`
	TokenHash string `json:"token_hash"`
	CreatedAt string `json:"created_at"`
	HasFCM    bool   `json:"has_fcm"`
}

// AgentDeviceListResp is the response to GET /agent/devices.
type AgentDeviceListResp struct {
	Devices []AgentDevice `json:"devices"`
}
