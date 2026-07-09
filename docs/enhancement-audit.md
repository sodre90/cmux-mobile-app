# Enhancement audit (2026-07-08)

A deeper pass over the codebase than `improvement-ideas.md`. Each finding is
backed by a `path:line` citation, with a one-line suggested fix. Produces from a
read-only investigation — no commits, no behavior changes.

Grouped by side (bridge / android) then by severity. Severity reflects
correctness/security impact, not effort.

Some items overlap the earlier `improvement-ideas.md` (rate limiting, R8,
backoff, accessibility) — kept here for completeness of the audit pass; the
overlap is noted inline.

---

## bridge (`/Users/perdos/prj/cmux-app/bridge`)

### Critical

- `internal/e2e/store.go:104,117,210` — `ValidateRecvCounter` and
  `CommitRecvCounter` are two separate locked load/save cycles. Between them a
  second concurrent `DecryptBody`/`DecryptFrame` for the same captured counter
  passes `Validate` (high-water not yet committed), `AEAD.Open` succeeds
  deterministically (identical nonce/ciphertext → identical plaintext), then
  both `Commit`. The concurrent replay window is the full Open+disk-save
  duration (JSON marshal + atomic rename). Fix: combine validate+commit into
  one atomic locked transaction that checks-and-updates the window **before**
  `Open`; reject already-seen counters before attempting decrypt.
- `internal/server/encryption.go:51-64` — `io.ReadAll(r.Body)` on the e2e
  envelope body with no `MaxBytesReader`. A device paired with a bogus pubkey
  can submit arbitrarily large ciphertext to OOM the agent. Fix: wrap with
  `http.MaxBytesReader(w, r.Body, 1<<20)` before `io.ReadAll`.
- `internal/relay/relay.go:309-323` — `handleRegister` decodes
  `/devices/register` with no body cap (only `handleRegisterTenant:344` and
  `handleDevicePair:399` cap). A valid device bearer can exhaust relay memory.
  Fix: `req.Body = http.MaxBytesReader(w, req.Body, 4<<10)` mirroring pair.
- `internal/relay/relay.go:340-387` + `deploy/nginx-cmux-relay-bootstrap.conf`
  — public bootstrap `/tenants/register` has no rate limit, no bound on tenant
  count, no abuse control (comment explicitly defers it). Anyone reaching
  `:8444` can mint unlimited tenants/certs, growing the SQLite store and CA
  serial ledger unboundedly. Fix: nginx `limit_req` zone + relay-side per-IP
  rejection + a global cap on tenant count. Overlaps
  `improvement-ideas.md` "rate limiting".
- `internal/relay/relay.go:116-126,176-200` (verifiedAgentTenant / handleTunnel)
  + `internal/relay/registry.go` Set/Clear — tenant revocation only takes
  effect on the agent's next tunnel connect; an already-connected revoked
  tenant keeps proxying device traffic until its tunnel dies. Fix: drop the
  tenant's session synchronously inside `RevokeTenant` (call `reg.Clear`,
  invoking its stored `stop`), or expose a revoke hook that cancels the active
  session.
- `deploy/nginx-cmux-bridge.conf` / `nginx-cmux-relay.conf` — neither vhost
  sets `ssl_protocols`, `ssl_ciphers`, `ssl_prefer_server_ciphers`, HSTS, or
  any rate-limit directive. nginx defaults to TLS1.0/1.1 on older builds and
  permit weak ciphers. Fix: pin `ssl_protocols TLSv1.2 TLSv1.3;` + a modern
  cipher suite, add HSTS, add `limit_req` on `/devices/pair` and
  `/tenants/register`.

### High

- `internal/relay/pushmon.go:60-78` (`subscribeOnce`) — on any `ReadJSON`
  error that is not session-close, `MonitorAgent` loops with a flat 1s sleep
  re-dialing `ws://agent/events` forever; a persistent frame-parse mismatch
  (cmux schema drift) produces a tight re-dial storm (once per second). Fix:
  add backoff, or break out on non-transport errors (decode errors) to avoid
  hammering the agent.
- `internal/server/events.go:233-243` (`RunEvents`) — only 1s sleep between
  cmux event process restarts, no backoff/jitter. Combined with
  `cmd/cmux-bridge/agent.go:65` (`nextBackoff` cap=30s without jitter) →
  thundering herd on relay/cmux recovery when many agents reconnect
  simultaneously. Fix: exponential + jittered backoff in both loops. Overlaps
  `improvement-ideas.md`.
- `cmd/cmux-bridge/agent.go:286-296` — context cancellation (SIGTERM) breaks
  the for-loop but never explicitly tears down the active `dialAndServe`
  http.Serve goroutine/wsConn; the process exits while in-flight requests to
  cmux (e.g. `mobile.terminal.input`) are killed mid-execution via
  `exec.CommandContext` SIGKILL, with no graceful drain. Fix: cancel ctx →
  drain active streams for a bounded period before returning.
- `cmd/cmux-bridge/agent.go:188-217` (`serveDirect`) — `tls.Config{...}` sets
  no `MinVersion`, no `CipherSuites`, no `NextProtos`. Go's server-side default
  now enforces TLS1.2+ but explicit pinning is safer and consistent with
  `loadTLS()`. Fix: `MinVersion: tls.VersionTLS12` + `NextProtos: []string{"h2","http/1.1"}`.
- `internal/server/terminal.go:110-116` — `bytes.Equal(next.Grid, lastGrid)`
  dedupe: a cmux render_grid that embeds any volatile field (clock, spinner
  state byte) makes this never match, producing a 250ms-spammed output frame
  downstream; meanwhile content-only changes are masked if cmux changes a
  non-grid byte we happen to equal. Fix: use a content hash (e.g. sha256 of
  cell data) instead of raw byte equality.
- `internal/server/events.go:396-401` + `internal/server/terminal.go:110-116`
  — when e2e is enabled, `deviceID := r.Header.Get("X-Device-ID")` is trusted
  from the relay's proxy `Director` with no agent-side check that it matches a
  paired device's secret beyond the `SharedSecret` lookup; the lookup only
  distinguishes 401/409 by header presence. Currently OK (tunnel is
  bearer-gated) — document as defense-in-depth to revisit: assert deviceID is
  non-empty AND secret present in one combined check, never log raw deviceID.
- `internal/cmux/socket_client.go:97-118` (`connectLocked`) — sends the cmux
  control password in cleartext over the unix socket write
  (`"auth " + password + "\n"`). AF_UNIX exposure is limited, but any process
  on the same Mac that can peer into the socket fds (e.g. via debug ptrace, or
  a malicious user sharing the same home) recovers it. Fix: at minimum assert
  the password file is 0600 and warn otherwise; consider salting the auth.

### Medium

- `internal/relay/relay.go:177-200` (`handleTunnel`) — `tunnel.Accept`
  upgrades before recording the session in the registry; a slow or malicious
  agent can complete the WS upgrade, hold it open, and churn upgrades without
  ever sending data, occupying the upgrade goroutine. yamux keepalive (15s)
  eventually kills idle, but no per-tunnel dial timeout caps
  upgrade-to-first-data latency. Fix: add an idle deadline after upgrade
  before declaring tunnel "up".
- `internal/e2e/store.go:104,117,210` — every counter op loads JSON →
  marshals → renames → fsyncs the whole session file. For a phone issuing many
  `/terminal` input frames per second this is heavy disk churn. Fix: cache the
  parsed store in memory (struct + sync.Mutex), persist on a debounced write
  or transactionally per N increments.
- `internal/server/feed.go:42-46` (`FeedReply`) — comments admit `exitPlan`
  and exact reply param keys are "not confirmed live"; `feedMethod` for
  `exitPlan` returns `feed.exit_plan.reply` which may be wrong against a real
  exit-plan prompt today, silently breaking that reply path. Fix: confirm
  against live cmux, drop unknown kinds to 400 until confirmed, or feature-flag.
- `internal/server/push.go:55-77` (`buildEncryptedPush`) — encrypts the FCM
  push body for `s.sessions.DeviceIDs()` ONCE for every device the agent ever
  paired, including long-revoked/direct-and-relay duplicates, on every
  NeedsAttention frame. Fix: track "currently paired devices" separately from
  "ever paired" history.
- `internal/relay/relay.go:46` (`agentCertValidity = 365*24*time.Hour`) +
  `internal/ca/ca.go:50,89` — long-lived (1y) client cert with no
  rotation/revocation path; CA root key has 10-year validity and no CRL/OCSP
  utility. Fix: at minimum document this limitation prominently; consider a
  `cmux-relay certs revoke` subcommand that drops the `agent_certs` row and the
  tenant.
- `internal/relay/registry.go:39-54` (`Set`) — `Set` locks, swaps, then
  unlocks and closes the old session; if `oldSess.Close()` blocks (outstanding
  writes), a third `Set` for the same tenant can run concurrently and
  overwrite a freshly-stored session while the older close is still in flight,
  risking use-after-close on yamux. Fix: close the old session before
  unlocking, or use a per-tenant close-gate so the swap has happens-before
  relations on close.
- `internal/server/encryption.go:93-104` (`encryptingResponseWriter.flush`) —
  buffers the *entire* plaintext response before encrypting and writing; a
  handler that streams a large payload (none currently, but `/sessions` could
  grow) sits fully in memory. Fix: add a cap and 500 if exceeded, or document
  the single-body contract.
- `cmd/cmux-bridge/pair.go:75-106` (`requestPairingCode`/`pollPairingCode`) —
  no per-call HTTP timeout on the `*http.Client` passed in (callers build
  `&http.Client{Transport:...}` with no `Timeout` in `agent.go:233` and
  `pair.go:265`). A stuck relay connection holds the poll indefinitely. Fix:
  set `client.Timeout` or use a per-request context with deadline.
- `internal/cmux/socket_client.go:158` (rpc goroutine) — the
  `select { case <-ctx.Done(): sc.conn.SetDeadline(time.Now()) ... }` reinstates
  a deadline on the connection that lingers for the *next* rpc call if the
  previous rpc was cancelled by ctx but the conn survived. After ctx
  cancellation the conn deadline becomes "now" which will fail the next read;
  usually fine (cancelled call comes back first) but ordering is racy. Fix:
  reset deadline to a sane default at the start of each call regardless of ctx.
- `cmd/cmux-bridge/agent.go:195` (cert dir) — direct-mode cert/key files
  default to `filepath.Join(filepath.Dir(cfg.DirectAuthStore), "direct-certs")`.
  `tailscaleCert` writes the key with whatever perms the tailscale CLI chooses.
  Fix: confirm key file perms after write and chmod to 0600.
- `cmd/cmux-bridge/agent.go:60` (`loadTLS`) — when `ca_cert` is empty the
  agent trusts the system root store for `wss://relay_url` (Let's Encrypt
  design), but there is no pinning of the expected server hostname beyond the
  cert's SAN. Acceptable for public CA; document that empty `ca_cert` MUST be
  paired with a real public DNS name — `agent.example.toml` ships `ca_cert = ""`
  which is fine for public CA only.
- `internal/config/agent.go` (`agentDefaults`) — `IdentityKey`,
  `SessionStore`, `YoloStore`, `DirectAuthStore` default under
  `~/.config/cmux-bridge/`. `agent.go` does `MkdirAll 0700` inside
  `e2e.OpenStore`/`OpenStore` on save, but if the parent dir is created first
  by some other tool it could leak 0777. Fix: ensure the
  `~/.config/cmux-bridge/` dir is created 0700 at agent first-run.

### Low

- `internal/relay/relay.go:50` + `internal/server/direct_pairing.go:23` —
  `const pairingCodeTTL = 10 * time.Minute` declared in two packages; risk of
  drift if one is bumped. Fix: move to a shared `internal/pairing` constant.
- `internal/relay/relay.go` types (`pairingCodeResp`, `newPairingCodeReq`,
  `pairingCodeStatusResp`, `pairingCodeInfoResp`, `devicePairReq`,
  `devicePairResp`) duplicate `internal/server/direct_pairing.go:30-69` —
  near-verbatim port per comment, two struct copies to maintain. Fix: extract
  to a shared `internal/pairing` package.
- `internal/server/terminal.go:34-45` — outbound `TerminalDown.Ok bool` is
  `json:"ok"` with no `omitempty` so non-ack frames carry `"ok":false` on the
  wire (intentional per comment) — semantically muddy. Consider splitting the
  ack-frame into a separate `Ack` type carrying only `Seq`/`Ok`.
- `internal/server/sessions.go:138-156` (`parseWorkspaces`) — recursive walk
  over arbitrary cmux JSON has no depth cap; cmux is trusted-local so low
  risk, but a corrupted/malicious cmux socket could feed deeply nested data to
  exhaust stack. Fix: add a depth counter.
- `internal/server/events.go:289-296` (`ingestEvents` Scanner) —
  `sc.Buffer(..., 4*1024*1024)`; malformed line >4MB is silently dropped
  (`json.Unmarshal` failure → continue) with no log. Fix: one-line log of
  dropped oversized/malformed frames so operators can correlate missing events.
- `internal/auth/store.go:130` (`Verify`) — comment claims hash-lookup leaks
  no timing signal — true for the single lookup, but `rows.Scan` into
  `fcm sql.NullString` etc. returns `Device{},false` on any error, masking DB
  corruption. Fix: log err before returning false.
- `cmd/cmux-bridge/main.go:30` + `cmd/cmux-relay/main.go:23` +
  `cmd/cmux-relay/commands.go` — `fmt.Println` to stdout for `version` and CLI
  list output, mixing with `log.Printf` for errors via stderr. No structured
  logger (`slog`) anywhere in the codebase; every `log.Printf` is unstructured
  text with no levels. Fix: adopt `log/slog` with a JSON handler + tenant ID
  field. Overlaps `improvement-ideas.md`.
- `internal/relay/relay.go:141` — `/healthz` returns plain `200 ok`; no
  liveness signal beyond "the process is up". Fix: a richer readiness that
  checks the SQLite store is open and (optionally) the CA loaded.
- `internal/e2e/cipher.go:26` (`buildInfo`) — HKDF `salt` argument is `nil`
  (RFC 5869 treats as HashLen zeros — fine), paired with constant info string
  `cmux-bridge e2e v1|...` — the only per-pair entropy is the ECDH shared
  secret + sorted public keys (already bound). Acceptable; document that `v1`
  is the protocol version and that bumping it requires re-pairing.
- `internal/server/encryption.go:30` + `internal/e2e/envelope.go:11`
  (`bodyEnvelope.V int json:"v"` + `env.V != 1` rejection) — the protocol has
  a version field but `internal/version/version.go` (`v = "0.1.0-dev"`) has no
  published tunnel/e2e protocol version separate from the binary version;
  there is no negotiation or downgrade protection. Fix: introduce an explicit
  protocol-version constant negotiated (or at least logged) at tunnel
  handshake.
- `internal/server/terminal.go:88` — `c.SetWriteDeadline(time.Now().Add(10 *
  time.Second))` per output write; if a slow phone stalls the WS, the 10s
  write deadline fires and the terminal loop returns — logged as "output write
  failed" but no reconnect hint to the app. Fix: forward a distinct
  "backpressure" frame or rely on app-side reconnect.
- `internal/yolo/store.go:38-69` — replays the same JSON-load/file-rename
  pattern as `e2e.Store`; same per-op fsync churn concern (lower impact since
  YOLO writes are rare). Adopt the in-memory-cache pattern for consistency.
- `internal/version/version.go:8` — `v = "0.1.0-dev"` is the only
  build-time-overridable piece; no `gitCommit`, `buildDate`, or capability
  flags exposed in `/healthz` or `cmux-bridge version` for support/debug. Fix:
  ldflags-injected commit/date and surface in version output.

### Polish / test gaps (bridge)

- `cmd/cmux-relay/commands.go:1-99` (`runDevices`/`runTenants`) — no `_test.go`;
  revoke paths (store.Revoke/RevokeTenant return values, error messages, "no
  such token" branch) are untested.
- `internal/testutil/fakecmux.go` — fixtures file with no `_test.go`; add a
  smoke test asserting the fake's RPC dispatch matches the real cmux CLI shape
  used in `client.go`.
- `internal/e2e/store_test.go` — covers `canAcceptRecvCounter` boundary cases
  but no test for the Critical concurrent Validate→Open→Commit interleaving
  above. Add an adversarial test that fires two `DecryptBody`/`DecryptFrame`
  calls with identical ciphertext in parallel and asserts exactly one returns
  plaintext + one returns `decrypt_failed`.
- No fuzz tests anywhere (`grep 'testing.F' bridge` empty). Obvious fuzz
  targets: `internal/server/sessions.go` `parseWorkspaces`,
  `internal/server/events.go` `classify`,
  `internal/server/terminal.go` `fetchReplay` (JSON parse paths over external
  cmux output). Add `FuzzParseWorkspaces` / `FuzzClassify` / `FuzzFetchReplay`.
- No adversarial test for `handleTunnel` racing two agents in the same tenant
  (`registry.Set` replacing the first). `multitenant_test.go` exists but does
  not cover same-tenant replacement. Add one.
- `internal/relay/pushmon_test.go` exists but has no test for the storm-on-
  parse-error loop (High finding). Add one.
- `internal/server/encryption_test.go` (330 lines) — no test for the
  `io.ReadAll`-without-bound case; add a test sending a multi-MB bogus envelope
  and asserting it doesn't OOM once the `MaxBytesReader` is added.
- `deploy/docker-compose.yml:1-26` — healthcheck `wget -qO-` works (busybox
  ships `wget` on alpine), but no `init:` process, no `--read-only` rootfs, no
  `cap_drop: ALL`, no `security_opt: no-new-privileges`. The Containerfile runs
  as container-root under rootful podman. Fix: `USER` a non-root uid for
  rootless.
- `deploy/Containerfile:1-26` — `CGO_ENABLED=0 go build` + `modernc.org/sqlite`
  (pure-Go) is good, but `go build` uses no reproducible-build flags beyond
  `-trimpath -ldflags="-s -w"`; add `-buildvcs=false` and pin
  `GOFLAGS=-mod=mod` or vendor `vendor/`. No binary version embedding
  (`-X internal/version.v=...`) so the relay reports `0.1.0-dev` in
  production.
- `deploy/cmux-relay.service` — no `LimitNOFILE=`, `LimitNPROC=`,
  `PrivateTmp=`, `PrivateDevices=`, `RestrictAddressFamilies=`. Add the
  standard systemd hardening profile.
- `deploy/com.sodre90.cmux-bridge.plist` — no `ThrottleInterval`, no
  `ProcessType`, no `StandardInPath`/`ExitTimeOut`; on a wedged agent
  `KeepAlive=true` respawns unbounded. Add `ThrottleInterval` and a
  successful-exit condition on `KeepAlive`.
- `deploy/nginx-cmux-bridge.conf:24` — `proxy_set_header X-Client-Cert-CN
  $ssl_client_s_dn;` (legacy LAN vhost) trusts the CN with no
  `X-Client-Cert-Verify` header and `ssl_verify_client on;` — correct today,
  but unlike `nginx-cmux-relay.conf` there is no belt-and-suspenders
  `X-Client-Cert-Verify SUCCESS` check on the Go side. If a future operator
  flips `ssl_verify_client` to `optional` on this vhost the relay's
  `notAgent`/`verifiedAgentTenant` semantics still apply (shared relay) — add
  a comment aligning the two configs to avoid misleading future operators.

---

## android (`/Users/perdos/prj/cmux-app/android`, package `com.sodre90.cmuxremote`)

### Critical

- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt:243`
  — `Log.d` prints typed input (`text=${...describeForLog(...)}`); printable
  chars pass through unredacted, leaks passwords/pasted secrets to logcat. Fix:
  strip payload from log, log only seq/sent/ok.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt:270`
  — `android.util.Log.d("TerminalInput","onValueChange old=... new=... diff=...")`
  logs every keystroke diff (printable passthrough via `describeForLog`).
  Fix: remove or redact to a non-sensitive summary.
- `app/src/main/java/com/sodre90/cmuxremote/data/Settings.kt:39` /
  `Identity.kt:32` / `e2e/Session.kt:67-106` — `EncryptedSharedPreferences`
  instances constructed and key/counter reads-writes run on main thread inside
  `CmuxApp.onCreate`/composition; every send/recv counter write is a
  synchronous `apply()` to encrypted prefs per keystroke/frame. Fix: move to
  background, batch/defer IO.
- `app/src/main/java/com/sodre90/cmuxremote/data/e2e/Session.kt:97-106` —
  `canAcceptRecvCounter` and `commitRecvCounter` re-read `ReplayWindow` from
  prefs each call and write per-frame; under burst output this is heavy disk
  IO and races durability. Fix: cache window in memory, persist lazily.
- `app/src/main/java/com/sodre90/cmuxremote/data/pairing/PairingQr.kt:23-31`
  — `parsePairingQr` accepts any `pairUrl` (incl. `http://`) with no scheme
  allowlist; malicious QR can redirect pairing to attacker host. Fix: require
  `https://` (and reject userinfo/host overrides) before issuing pair POST.

### High

- `app/src/main/java/com/sodre90/cmuxremote/data/Mtls.kt:23-36` — no
  certificate pinning and `BearerInterceptor` adds the token to all
  requests/redirects through the client; OkHttp follows cross-host redirects by
  default, leaking bearer to redirect target. Fix: set `followRedirects=false`
  or filter `Authorization` on cross-host, consider pinning for self-hosted
  relays.
- `app/src/main/java/com/sodre90/cmuxremote/data/TerminalSocket.kt:39-64` —
  `callbackFlow` uses default REPLAY buffering; `trySend` drops frames silently
  under burst output (default `ExtraBufferCapacity=0` /
  `Channel.OPTIONAL`). Fix: explicit `buffer(Channel.BUFFERED)` +
  `onBufferOverflow=DROP_OLDEST` and surface backpressure.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt:118-137`
  — heavy work in composition: `buildLine` (run-length grouping over every
  row's cells) called inside `Text(...)` every recomposition, every frame, for
  every visible + scrollback row with no `remember`. Fix: cache/precompute the
  `AnnotatedString` list in `remember(grid, styles, colors, cursor…)`.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt:76-77`
  — `remember(grid){ grid.scrollbackLines + grid.lines }` allocates the full
  buffer per frame; no bound/server cap on `scrollbackLines` size means OOM
  risk on heavy output. Fix: cap scrollback rows rendered/persisted
  client-side.
- `app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt:73-89` —
  `CoroutineScope(SupervisorJob()+IO)` created per `registerFcmToken` call and
  never cancelled; same leak shape in `CmuxMessagingService.kt:29` (scope
  leaked, no onDestroy cancel). Fix: use a single scoped holder or cancel in
  onDestroy.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt:120-129`
  — `while(isActive){ delay(500); recomputeDeliveryStatus }` busy loop and
  `loadYoloMode` runs unguarded network on init every time the Terminal route
  is entered; no tests for delivery/ack FSM (`pendingAcks`/`neverSentQueue`/
  `inFlightInputSeq`) — critical untested path.

### Medium

- `app/build.gradle.kts:31` — release `isMinifyEnabled = false`; no R8/shrinking,
  larger APK, no dead-code removal. Enable with the existing `proguard-rules.pro`
  (serialization keep rules already present) and add `-keep` for lazysodium/JNA
  natives if needed. Overlaps `improvement-ideas.md`.
- `app/build.gradle.kts:73` — `androidx.datastore.preferences` declared but
  unused (app uses SharedPreferences in `WorkspaceOrderStore.kt:13`); either
  migrate `WorkspaceOrderStore` to DataStore or drop the dep.
- `gradle/libs.versions.toml` (`securityCrypto = "1.1.0-alpha06"`) —
  `EncryptedSharedPreferences` pulled from alpha; known bugs in alpha06 (e.g.
  API 33 upgrade path). Fix: stabilize to latest stable or pin risk note in
  README.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt:104,128,131`
  — `var input`, `userZoom`, `wrap` via `remember` not `rememberSaveable`;
  config change (rotation) drops captured-but-unsent keystrokes, zoom/wrap
  prefs. Fix: use `rememberSaveable`.
- `app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt:113,119,296`
  — `mutableStateMapOf<String,Boolean>()`, `customOrder`, `showActionMenu` via
  `remember` not `rememberSaveable`; expanded-state and custom order lost on
  rotation. Fix: `rememberSaveable` (with a saver for the map).
- `app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt:38-40` —
  `POST_NOTIFICATIONS` requested unconditionally at first launch even when
  push disabled (no `google-services.json`). Fix: gate the permission request
  on Firebase being configured.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalScreen.kt:295-305`
  — physical keyboards (tablets, DeX, Bluetooth) only get Backspace
  special-cased; Escape, arrows, Tab, Ctrl combos from a real key event fall
  through to `BasicTextField`'s text capture (which mangles them). Fix: add
  `onPreviewKeyEvent` mapping for `Key.Direction`/`Escape`/`Tab`/`Enter` →
  `vm.sendText()`.
- `app/src/main/java/com/sodre90/cmuxremote/data/AppContainer.kt:42-68` —
  cached `OkHttpClient` keyed by `"$baseUrl|$token|$paired"`; OkHttp instances
  are never evicted/shutdown on `forgetSlot` (`clients.remove(slot)` drops the
  ref but doesn't `dispatcher().executorService().shutdown()`/
  `connectionPool().evictAll()`). Connection pool leaks until process death.
  Fix: tear down the evicted client.
- `app/src/main/java/com/sodre90/cmuxremote/ui/CmuxNavHost.kt:95-97` —
  `remember(forgetGeneration){ ... }` re-reads `settings.bridgeConfig()` only
  when `forgetGeneration` bumps, but pairing success navigates via
  `popBackStack()` without bumping — `ConnectionSettingsScreen` can show
  stale paired status after returning from a successful pair. Fix: bump
  generation in `onPaired`/`navigate` or derive from a `StateFlow`.
- `app/src/main/java/com/sodre90/cmuxremote/model/RenderGrid.kt:146-172` —
  `layout()` writes a wide char's first cell as the glyph cell and the next as
  blank filler even when `cell_width` under-declares; astral glyphs tagged
  narrow produce orphan trailing blank that misaligns following spans. Fix:
  add a regression test for mis-tagged width.
- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/TerminalViewModel.kt:171-211`
  — reconnect loop sets `relayDownUntil` only on no-frame RELAY failure; a
  partial-success RELAY connect that drops framing is not penalized, and
  DIRECT fallback selection relies on the per-call `terminalSocket()` order
  (`primarySlot.other()`) which silently sticks to RELAY on transient drops.
  Fix: explicit fallback on consecutive `close(t)` from RELAY.
- `app/src/main/java/com/sodre90/cmuxremote/data/FallbackBridgeClient.kt:33-52`
  — `call()` retries on `IOException` from primary (including 5xx) and re-runs
  the block on fallback; mutating calls (`replyFeed`/`renameWorkspace`/
  `setYoloMode`) are wrapped — IOException here is transport only
  (`BridgeException` for 5xx), so re-run is OK, but `registerDevice` re-run
  only on real transport-OK; verify relay/direct both expect idempotent
  re-registration.

### Low

- `app/src/main/java/com/sodre90/cmuxremote/ui/terminal/RenderGridView.kt:141-146`
  — `FloatingActionButton{ Text("▼") }` has no `contentDescription`; screen
  readers announce nothing for jump-to-bottom. Fix:
  `Modifier.semantics{ contentDescription = "Jump to bottom" }` or an Icon
  with description. Overlaps `improvement-ideas.md`.
- `app/src/main/java/com/sodre90/cmuxremote/ui/sessions/SessionsScreen.kt:164-170,360-368`
  — disabled drag handle Icon `contentDescription = null`, action menu items
  lack accessibility actions; touch targets on `RadioButton`/`Checkbox` rows
  (`InboxScreen.kt:141` padding=4dp) may fall under 48dp min. Fix: increase
  row min touch size, label icons.
- `app/src/main/java/com/sodre90/cmuxremote/MainActivity.kt:43,77` —
  `registerFcmToken()` runs on every `onCreate`; token callback invokes network
  on IO scope even after Activity recreated. Fix: gate to once-per-process
  (container-level flag or store last-registered token).
- `app/src/main/java/com/sodre90/cmuxremote/data/WorkspaceOrderStore.kt:13` —
  plain `SharedPreferences` for custom order; works, but committed via
  `apply()` from composition (`vm.saveOrder` in `SessionsScreen.kt:127`) on
  every drag move — spammy. Fix: debounce save or use `edit().commit()` once
  per drag end.
- `app/src/main/AndroidManifest.xml` — no
  `<application android:dataExclusionRules>`/`fullBackupContent`;
  `allowBackup=false` covers but has been de-emphasized on API 31+ in favor of
  `dataExtractionRules`. Fix: add
  `android:dataExtractionRules="@xml/data_extraction_rules"` excluding the
  encrypted prefs files explicitly.
- `app/src/main/AndroidManifest.xml` — `<activity launchMode="singleTask">`
  plus `enableOnBackInvokedCallback="true"` is good, but no
  `android:windowSoftInputMode` and no `adjustResize` handling beyond
  `windowInsetsPadding`; on some IMEs the transparent `BasicTextField` in
  `TerminalScreen` may not pan into view. Fix: set `windowSoftInputMode`
  explicitly.
- `app/src/main/res/values/themes.xml:2` — theme parent
  `android:Theme.Material.NoActionBar` (platform, not AppCompat/Material3) is
  fine for `ComponentActivity` but inconsistent with `CmuxTheme`'s
  `MaterialTheme`; ensure no Material3 theming relies on parent attributes.

### Polish / test gaps (android)

- `app/src/test/...` has no tests for `MainActivity`, `CmuxApp`,
  `CmuxNavHost`, `InboxViewModel`, `SessionsViewModel`, `TerminalViewModel`,
  `PairingViewModel`, `ConnectionSettingsScreen`, `PairingScreen`,
  `SessionsScreen`, `InboxScreen`, `TerminalScreen`, `CmuxMessagingService`. Add
  JVM tests for delivery FSM, events reconnect, push decrypt path, navigation
  deep-link resolution.
- `app/src/androidTest` absent despite `testInstrumentationRunner` configured
  in `app/build.gradle.kts:26`; no instrumented tests / Baseline Profile. Add
  androidTest scaffolding or drop the runner config. Overlaps
  `improvement-ideas.md`.
- `app/build.gradle.kts` has no `signingConfigs`/release build flavor; release
  build cannot be installed without manual signing. Document or add
  debug-signing default.
- `app/src/main/java/com/sodre90/cmuxremote/data/e2e/Envelope.kt` / `Frame.kt`
  hardcode envelope version `v=1`; no client-side rejection/future-proofing if
  the bridge sends `v=2`. Fix: surface a distinct error vs.
  `DecryptFailedException`.
- `README.md` (android) doesn't mention `POST_NOTIFICATIONS` auto-prompt or
  absence of certificate pinning — document the trust model.

---

## Cross-cutting themes

A handful of recurring patterns, grouped so they can be fixed systematically
rather than touched one file at a time:

- **Backoff everywhere lacks jitter** — `bridge/cmd/cmux-bridge/agent.go:60`
  (`nextBackoff`), `bridge/internal/server/events.go:233` (cmux process
  restart), `bridge/internal/relay/pushmon.go:60` (re-dial),
  implicitly the Android reconnect loop. Synchronized outages → synchronized
  reconnect waves. One helper + 3 call sites.
- **Per-op fsync churn for structured-on-disk counter stores** —
  `bridge/internal/e2e/store.go`, `bridge/internal/yolo/store.go`,
  `android/.../e2e/Session.kt`. Same pattern on both sides of
  the wire, both fixable the same way: in-memory cache + debounced/batched
  persistence.
- **`remember` where `rememberSaveable` belongs** — recurring across Android
  UI (`TerminalScreen`, `SessionsScreen`, `CmuxNavHost`, waiting-first sort toggle
  in `improvement-ideas.md`). One pass: any `remember` holding UI/scroll/pref
  state that should survive rotation goes to `rememberSaveable`.
- **No structured logging anywhere** — both bridge (`log.Printf` everywhere)
  and android (`android.util.Log.d`). Adopting `log/slog` (bridge) and a
  tagged, redacted `Timber`-style wrapper (android) folds several of the
  Log-discovery and observability findings at once. Overlaps
  `improvement-ideas.md`.
- **Two pairing-code paths duplicated** — `bridge/internal/relay/relay.go` ↔
  `bridge/internal/server/direct_pairing.go` ship near-verbatim
  structs/constants with a "copy per comment" note. A shared
  `internal/pairing` package kills the drift risk (one of the Low findings)
  and is the natural home for the rate-limit/TTL constants too.
- **No CI / no static analysis** — `improvement-ideas.md` already flags this as
  the single highest-value addition; the test-gap findings above make it more
  acute (Critical-concurrency test for the e2e replay window, fuzz targets,
  adversarial same-tenant replacement test, JVM FSM tests for terminal ack
  delivery).