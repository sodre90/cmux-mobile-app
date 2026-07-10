package server

import (
	"net/http"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/pairing"
)

// MountDirectPairing registers the four pre-auth pairing routes direct mode
// needs onto mux, backed by store and scoped to the single implicit tenant
// tenantID (direct mode has exactly one, created once at agent startup --
// see runAgent). The handlers are the same shared implementation the relay
// mounts (internal/pairing), minus that side's agentOnly/mTLS-CN tenant
// resolution: there's no second tenant to disambiguate from here, and the
// real access boundary for this whole listener is Tailscale's own network
// ACLs, not a per-request identity check on these four routes. Same reason
// /devices/pair isn't rate-limited here either (pairing.AllowAll): this
// listener only binds this Mac's own Tailscale IPv4 address (serveDirect),
// so it's never internet-reachable the way the relay's copy is.
func MountDirectPairing(mux *http.ServeMux, store *auth.Store, tenantID string) {
	pairing.Mount(mux, store, pairing.ConstantTenant(tenantID), pairing.AllowAll)
}
