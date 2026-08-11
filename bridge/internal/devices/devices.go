// Package devices implements the agent-facing device-admin HTTP routes
// served, byte-identically, by both the relay (internal/relay) and direct
// mode (internal/server). It exists so an operator can enumerate and revoke
// paired devices using an identifier the system actually exposes -- the
// token hash -- rather than the raw bearer token only the device itself
// holds (cmux-app-vkq).
//
// Deliberately separate from internal/pairing: these routes act on devices
// that finished pairing long ago, and folding them in would make that
// package's name a lie. The TenantResolver seam is shared with it, since
// "how does this request resolve to a tenant" is the same question in both.
package devices

import (
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/httpjson"
	"github.com/sodre90/cmux-bridge/internal/pairing"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

type handlers struct {
	store  *auth.Store
	tenant pairing.TenantResolver
}

// Mount registers the device-admin routes onto mux, backed by store, with
// tenant deciding which tenant the caller acts for.
//
// Both routes are agent-facing: the relay passes its verified-mTLS-CN
// resolver, so a tenant reaches only its own devices. Direct mode passes
// ConstantTenant and therefore applies no per-request identity check at all
// -- the same posture MountDirectPairing documents, where that listener's
// access boundary is Tailscale's network ACLs rather than anything here.
// That means any tailnet peer can list and revoke direct-paired devices.
// Accepted: the listing carries no secrets, revocation is the safe direction
// in which to fail, and the same caller can already mint pairing codes.
func Mount(mux *http.ServeMux, store *auth.Store, tenant pairing.TenantResolver) {
	h := &handlers{store: store, tenant: tenant}
	mux.Handle("GET /agent/devices", http.HandlerFunc(h.listDevices))
	mux.Handle("POST /agent/devices/{tokenHash}/revoke", http.HandlerFunc(h.revokeDevice))
}

func (h *handlers) listDevices(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := h.tenant(req)
	if !ok {
		httpjson.Error(w, http.StatusForbidden, "forbidden")
		return
	}
	found := h.store.ListByTenant(tenantID)
	out := make([]wire.AgentDevice, 0, len(found))
	for _, dev := range found {
		out = append(out, wire.AgentDevice{
			Name:      dev.Name,
			TokenHash: dev.TokenHash,
			CreatedAt: dev.Created.UTC().Format(time.RFC3339),
			HasFCM:    dev.FCM != "",
		})
	}
	httpjson.Write(w, http.StatusOK, wire.AgentDeviceListResp{Devices: out})
}

func (h *handlers) revokeDevice(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := h.tenant(req)
	if !ok {
		httpjson.Error(w, http.StatusForbidden, "forbidden")
		return
	}
	err := h.store.RevokeByHash(tenantID, req.PathValue("tokenHash"))
	switch {
	case errors.Is(err, auth.ErrNotFound):
		httpjson.Error(w, http.StatusNotFound, "unknown_device")
	case err != nil:
		slog.Error("devices: revoke", "tenant_id", tenantID, "err", err)
		httpjson.Error(w, http.StatusInternalServerError, "internal_error")
	default:
		httpjson.Write(w, http.StatusOK, struct{}{})
	}
}
