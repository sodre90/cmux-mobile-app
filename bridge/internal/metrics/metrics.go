// Package metrics holds the small set of process-wide expvar counters and
// gauges the relay and agent binaries export at their natural choke points
// (see docs/improvement-guide.md §6.2). It is deliberately not a general
// metrics framework: add a new var here only when a real choke point needs
// one, and increment it right at that choke point rather than threading
// metrics-specific parameters through unrelated call chains.
//
// Every var here is registered exactly once, at package init, via the
// standard library's expvar.NewInt/expvar.NewMap. Both binaries serve them
// at /debug/vars on their existing HTTP listener (relay.Handler,
// server.Server's mux) -- expvar's registry is process-global, so whichever
// vars a given binary actually increments are the ones that show up there.
package metrics

import "expvar"

var (
	// TunnelsActive is a gauge: how many tenant tunnel sessions are
	// currently registered in relay.Registry. Set (not Add'd) on every
	// registry mutation so it always reflects the live count, never drifts
	// from missed decrements.
	TunnelsActive = expvar.NewInt("tunnels_active")

	// ProxyRequestsTotal counts every proxied app request that reaches
	// relay's reverse proxy, keyed by tenant ID, regardless of outcome.
	ProxyRequestsTotal = expvar.NewMap("proxy_requests_total")

	// ProxyAgentOfflineTotal counts, per tenant ID, proxied requests that
	// failed because that tenant had no active agent tunnel.
	ProxyAgentOfflineTotal = expvar.NewMap("proxy_agent_offline_total")

	// PairingCodesIssuedTotal counts pairing codes successfully minted via
	// POST /agent/pairing-code.
	PairingCodesIssuedTotal = expvar.NewInt("pairing_codes_issued_total")

	// PairingCodesRedeemedTotal counts pairing codes successfully redeemed
	// via POST /devices/pair.
	PairingCodesRedeemedTotal = expvar.NewInt("pairing_codes_redeemed_total")

	// PairingCodesExpiredTotal counts redemption/info-lookup attempts
	// against a pairing code whose TTL had already elapsed, distinct from
	// not-found or already-redeemed (see auth.Store.RedeemPairingCode and
	// PairingCodeInfo, the two places that actually detect expiry).
	PairingCodesExpiredTotal = expvar.NewInt("pairing_codes_expired_total")

	// PushSentTotal and PushFailedTotal are running totals of individual FCM
	// send attempts across all tenants (relay/pushmon.go's fanout already
	// computes sent/failed per call; these accumulate them).
	PushSentTotal   = expvar.NewInt("push_sent_total")
	PushFailedTotal = expvar.NewInt("push_failed_total")

	// E2EDecryptFailuresTotal counts failed e2e decrypt attempts on the
	// agent, keyed by call site ("terminal_frame", "body").
	E2EDecryptFailuresTotal = expvar.NewMap("e2e_decrypt_failures_total")
)
