package server

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/httpjson"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

// MountDirectPairing registers the four pre-auth pairing routes direct mode
// needs onto mux, backed by store and scoped to the single implicit tenant
// tenantID (direct mode has exactly one, created once at agent startup --
// see runAgent). Each handler is a near-verbatim port of the matching
// internal/relay/relay.go handler, minus that file's agentOnly/mTLS-CN
// tenant resolution: there's no second tenant to disambiguate from here,
// and the real access boundary for this whole listener is Tailscale's own
// network ACLs, not a per-request identity check on these four routes.
func MountDirectPairing(mux *http.ServeMux, store *auth.Store, tenantID string) {
	mux.Handle("POST /agent/pairing-code", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		var rq wire.NewPairingCodeReq
		if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.AgentPubkey == "" {
			httpjson.Error(w, http.StatusBadRequest, "missing agent_pubkey")
			return
		}
		code, err := store.NewPairingCode(tenantID, rq.AgentPubkey, wire.PairingCodeTTL)
		if err != nil {
			httpjson.Error(w, http.StatusInternalServerError, "internal_error")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(wire.PairingCodeResp{
			Code:      code,
			ExpiresAt: time.Now().Add(wire.PairingCodeTTL).UTC().Format(time.RFC3339),
			TenantID:  tenantID,
		})
	}))

	mux.Handle("GET /agent/pairing-code/{code}", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		code := req.PathValue("code")
		pubkey, hash, redeemed, ok := store.PairingCodeStatus(tenantID, code)
		if !ok {
			httpjson.Error(w, http.StatusNotFound, "not_found")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(wire.PairingCodeStatusResp{
			Redeemed:     redeemed,
			DevicePubkey: pubkey,
			TokenHash:    hash,
		})
	}))

	mux.Handle("GET /devices/pair-info/{code}", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		code := req.PathValue("code")
		agentPubkey, tid, expiresAt, ok := store.PairingCodeInfo(code)
		if !ok {
			httpjson.Error(w, http.StatusGone, "pairing_code_invalid")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(wire.PairingCodeInfoResp{
			AgentPubkey: agentPubkey,
			ExpiresAt:   expiresAt,
			TenantID:    tid,
		})
	}))

	mux.Handle("POST /devices/pair", http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		req.Body = http.MaxBytesReader(w, req.Body, 4<<10)
		var rq wire.DevicePairReq
		if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.Code == "" || rq.DevicePubkey == "" {
			httpjson.Error(w, http.StatusBadRequest, "missing code or device_pubkey")
			return
		}
		name := rq.Name
		if name == "" {
			name = "phone"
		}
		tok, tid, ok := store.RedeemPairingCode(rq.Code, name, rq.DevicePubkey)
		if !ok {
			httpjson.Error(w, http.StatusGone, "pairing_code_invalid")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(wire.DevicePairResp{Token: tok, TenantID: tid})
	}))
}
