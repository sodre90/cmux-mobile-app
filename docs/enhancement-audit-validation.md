# Enhancement audit — validation pass (2026-07-08)

Independent verification of every finding in `enhancement-audit.md`, done by reading
the cited code directly (not trusting the audit's line numbers or reasoning).
Verdict buckets:

- **CONFIRMED-bug** — code matches the claim, consequence is real.
- **REFUTED** — miscited, a guard already exists, or the claim is factually wrong.
- **OVERSTATED** — code matches but severity/reachability/mechanism is inflated or wrong.
- **ACCURATE-BUT-NOT-A-BUG** — true observation, not actually a problem (often the
  audit itself already hedges this).
- **SUBJECTIVE** — style/design opinion, not a correctness claim.

Overall: of ~80 findings, the large majority are factually accurate observations.
The main pattern in the wrong ones: the auditor traced the vulnerable-looking code
but not the surrounding call chain — missing an upstream auth gate, a library
default (OkHttp strips `Authorization` on cross-host redirects; Go's `tls.Config`
already excludes sub-TLS1.2 when `MinVersion` is unset; coroutines' default
`callbackFlow` buffer is 64 slots, not 0), or a DB-level check that already
neutralizes the "critical" scenario. Two findings are flatly refuted by code that
already handles the case correctly (Android wide-char layout; the bridge
`Registry.Set` "race").

Note: many bridge line-number citations have drifted from current HEAD (offsets of
10–150+ lines in `events.go`/`auth/store.go` especially). Content was locatable
nearby in every case; regenerate citations against current HEAD before using this
doc as a work list.

---

## bridge

### Critical

1. **e2e/store.go replay race (Validate/Commit TOCTOU)** — **CONFIRMED-bug.**
   `ValidateRecvCounter` and `CommitRecvCounter` are independent lock cycles with
   `AEAD.Open` unlocked in between; two concurrent decrypts of the same captured
   ciphertext can both pass Validate, both Open successfully, both Commit. Real
   replay-not-rejected window. Highest-priority fix in the whole audit.

2. **encryption.go unbounded `io.ReadAll`** — **CONFIRMED-bug, narrower reachability.**
   Every other body-reading handler in the codebase wraps with `MaxBytesReader`;
   this one doesn't. But it requires `SharedSecret(deviceID)` to already resolve —
   i.e. an already-paired device, not a fully unauthenticated "bogus pubkey" as
   worded. Still a real OOM vector from a malicious paired device.

3. **relay.go `handleRegister` no body cap** — **CONFIRMED-bug, narrower reachability.**
   Confirmed missing vs. the two sibling handlers that do cap. Gated behind
   `auth.Require` (a valid device bearer), so it's an authenticated-device DoS, not
   anonymous — the missing-cap claim itself is exactly right.

4. **`/tenants/register` no rate limit** — **CONFIRMED-bug (known, documented gap).**
   The "known gap" comment exists verbatim in both the Go handler and the nginx
   bootstrap conf. No rate limiting exists anywhere in the path.

5. **Tenant revocation not synchronous** — **OVERSTATED.**
   `RevokeTenant` really doesn't call into the Registry. But `Verify` checks
   `revoked_at IS NULL` live on every request, so new device traffic from a revoked
   tenant is rejected on the very next request — not "until the tunnel dies" as
   worded. Only an already-open long-lived stream (e.g. an existing terminal WS)
   rides out until it naturally closes. Real gap, much smaller blast radius than stated.

6. **nginx vhosts missing TLS/cipher/HSTS/rate-limit hardening** — **CONFIRMED.**
   Read both configs fully — neither sets `ssl_protocols`, `ssl_ciphers`, HSTS, or
   `limit_req`. Exactly as described.

### High

1. **pushmon flat 1s re-dial, no backoff** — **CONFIRMED-bug.** Any persistent
   non-close `ReadJSON` error re-dials once/second forever.

2. **Thundering herd (events.go + agent.go backoff)** — **OVERSTATED, conflates two
   unrelated loops.** `RunEvents`' flat-sleep loop restarts a *local* subprocess via
   a Unix socket — it cannot itself cause a multi-agent network storm. `nextBackoff`
   (agent.go) really does lack jitter and really is the one component that could
   cause synchronized relay-reconnect storms across agents. Only half the finding is real.

3. **agent.go SIGTERM doesn't drain in-flight requests** — **OVERSTATED, wrong
   mechanism.** No drain logic exists (true), but the claimed kill path is wrong:
   cmux RPC calls use the per-*request* context, never the top-level shutdown
   context, so SIGTERM doesn't SIGKILL in-flight cmux calls via that path. The real
   defect is just that the loop doesn't exit promptly on cancellation.

4. **serveDirect TLS config missing MinVersion/CipherSuites** — **OVERSTATED.** The
   field omission is real, but Go's `crypto/tls` already excludes sub-TLS1.2 for
   servers when `MinVersion` is unset, and default cipher suites are already the
   secure curated set. No actual downgrade risk.

5. **terminal.go `bytes.Equal` dedupe causing spam/masking** — **OVERSTATED,
   speculative.** The raw-byte-compare fact is true, but both consequences are
   unevidenced (no proof cmux embeds a volatile byte) and the "masks real changes"
   half is self-contradictory — a render-grid *is* the content, so identical bytes
   can't represent different content.

6. **X-Device-ID trusted from proxy header** — **ACCURATE-BUT-NOT-A-BUG, audit's own
   caveat holds up.** The relay's Director computes the header server-side from a
   bearer-verified device; direct mode explicitly overwrites any client-supplied
   value. No untrusted party can inject a rogue deviceID today, matching the
   audit's own "currently OK" framing.

7. **socket_client.go cleartext password over Unix socket** — **ACCURATE-BUT-NOT-A-BUG.**
   Confirmed verbatim. Not exploitable beyond local-process access, and the
   cleartext protocol is dictated by cmux itself, not a choice this code made.

### Medium

1. **handleTunnel: Accept before registry write, no upgrade timeout** — **CONFIRMED-bug**
   (mitigated in practice by requiring a verified mTLS tenant cert to reach this path).

2. **e2e/store.go per-op fsync churn** — **OVERSTATED.** `Validate` only reads (no
   write); only `Commit`/`Send` write. And `save()` never actually calls `Sync()` —
   the "fsyncs" detail is factually wrong. One full-file rewrite per Commit is
   real; the amplification described is not.

3. **feed.go `exitPlan` reply path unconfirmed** — **ACCURATE-BUT-NOT-A-BUG.** This
   is the author's own documented known-unknown (comment says so verbatim), not a
   defect the audit discovered.

4. **push.go encrypts for every ever-paired device** — **CONFIRMED-bug.** `DeviceIDs()`
   really does return all-time paired devices; no cleanup path exists anywhere.
   Real, if minor, unbounded growth.

5. **1yr agent cert / 10yr CA, no CRL/OCSP** — **OVERSTATED.** Constants match, but
   revocation is enforced at the app/DB layer (`TenantActive` checked on every
   tunnel connect and every device request) — a revoked cert is functionally
   worthless immediately, even though cryptographically still valid.

6. **registry.go `Set` race / use-after-close** — **ACCURATE-BUT-NOT-A-BUG.**
   Unlock-before-blocking-Close is confirmed, but each `Set` only closes the
   session *it* personally displaced — no double-close or live-session
   use-after-close path was found.

7. **encryption.go buffers entire response before encrypting** — **ACCURATE-BUT-NOT-A-BUG,
   hypothetical only.** True today, but no current handler streams a large payload.

8. **pair.go missing HTTP timeout** — **CONFIRMED-bug (citations drifted).** Actual
   sites are pair.go:203/219, not :265 — both genuinely lack `.Timeout` and never
   pass a deadline context.

9. **socket_client.go deadline "lingers" for next call** — **REFUTED (literal claim),
   plausible narrower race underneath.** The deadline is reset at the start of
   every rpc call, so it doesn't passively linger. A much narrower timing race
   exists if ctx-cancellation and a successful read race the watcher goroutine, but
   that's a different bug than the one described.

10. **agent.go direct-cert dir/perms** — **ACCURATE-BUT-NOT-A-BUG.** True as stated,
    but `tailscale cert` is documented to write keys at 0600 itself.

11. **loadTLS empty `ca_cert` / no hostname pinning** — **ACCURATE-BUT-NOT-A-BUG**
    (this one was a sanity check per the audit's own framing — passes).

12. **config/agent.go dir perms 0700** — **ACCURATE-BUT-NOT-A-BUG.** Purely
    hypothetical — no code path in this repo creates the dir with looser perms first.

### Low / Polish

Mostly **CONFIRMED-accurate** as mechanical observations (pairingCodeTTL/struct
duplication, `/healthz` shallow, `parseWorkspaces` no depth cap, `Ok bool` no
omitempty, HKDF salt nil, protocol-version field with no negotiation, yolo/store.go
fsync-pattern match, 10s write deadline, `auth/store.go Verify` swallowing scan
errors, no `slog`/structured logging, no fuzz tests, missing `_test.go` files,
missing systemd hardening directives). Three worth flagging specifically:

- **events.go oversized-line handling** — **OVERSTATED, wrong mechanism, actually
  worse.** `bufio.Scanner`'s 4MB cap fires *inside* `Scan()` itself and ends the
  entire event-ingestion loop (no post-loop `sc.Err()` check) — it doesn't hit
  `json.Unmarshal`/`continue` as described. One oversized line kills the whole
  stream, not just gets skipped. "No log line" is still true.

- **docker-compose.yml "rootful podman"** — **REFUTED.** The Containerfile
  explicitly documents running as container-root under **rootless** podman (maps
  to the unprivileged host user) — a deliberate, safer design, opposite of the claim.

- **plist `KeepAlive` respawns a wedged agent unbounded** — **OVERSTATED.**
  launchd's `KeepAlive` only respawns on process *exit*; a hung-but-alive process
  isn't touched by it regardless of `ThrottleInterval`.

- **nginx CN-trust asymmetry between vhosts** — **OVERSTATED.** The bridge vhost's
  Go code never reads the CN header at all (auths via bearer token instead) and
  requires `ssl_verify_client on`, so the missing header check is decorative, not
  a real trust-boundary gap.

- **fakecmux.go "no test"** — **OVERSTATED.** No dedicated `_test.go`, but it's
  transitively exercised by 9 other test files including one that directly asserts
  the RPC dispatch shape.

---

## android

### Critical — **all 5 CONFIRMED-bug**, highest-confidence bucket in the whole audit.

1. `TerminalViewModel.kt:243` Log.d leaks typed text — confirmed verbatim;
   `describeForLog` only escapes control chars, passes printable text through raw.
2. `TerminalScreen.kt:270` same leak on every keystroke — confirmed.
3. EncryptedSharedPreferences built/used on main thread — confirmed for
   construction (`CmuxApp.onCreate` → `AppContainer` field init, no dispatcher
   hop) and for the send-counter path (composition → `dispatch()` → synchronous
   `TerminalSocket.send()`). Minor imprecision: the *recv*-counter path runs on an
   OkHttp callback thread, not literally "composition."
4. `Session.kt` recv-counter re-reads/re-writes prefs every single call — confirmed,
   no in-memory cache anywhere.
5. `PairingQr.kt` accepts `http://` with no scheme check, traced all the way to the
   POST call — confirmed, no `https://` requirement anywhere in the chain.

### High

1. **Mtls.kt: no pinning, cross-host redirect leaks bearer** — **OVERSTATED.**
   No pinning is real, but disassembly of the actual resolved OkHttp 4.12.0 jar
   confirms it strips `Authorization` on cross-host redirects automatically — the
   leak doesn't happen.
2. **TerminalSocket.kt: callbackFlow drops frames under burst** — **OVERSTATED.**
   `trySend`'s result is genuinely discarded, but the default `callbackFlow` buffer
   (verified in the actual coroutines-core jar) is 64 slots with `SUSPEND` overflow,
   not 0/`OPTIONAL` as claimed — drops require a much larger backlog than implied.
3. **RenderGridView.kt buildLine no `remember`** — **CONFIRMED-bug.** Runs on every
   recomposition for every row, no memoization.
4. **RenderGridView.kt scrollback allocation per frame, no cap** — **CONFIRMED
   mechanism** (full reallocation every WS frame, no ceiling anywhere); OOM framing
   is speculative but the underlying waste is real.
5. **MainActivity/CmuxMessagingService coroutine scope leak** — **CONFIRMED-bug.**
   New scope per call in MainActivity, no `onDestroy` cancellation in either class.
6. **TerminalViewModel "busy loop" + untested FSM** — **split verdict.** Calling the
   500ms `delay()`-based poll a "busy loop" is wrong (it suspends, near-zero CPU);
   `loadYoloMode` is wrapped in try/catch so "unguarded" overstates crash risk. Zero
   test coverage for the delivery FSM is confirmed accurate.

### Medium

**CONFIRMED-bug:** `isMinifyEnabled=false`; unused DataStore dependency; alpha
`securityCrypto` version; `remember` vs `rememberSaveable` in both TerminalScreen
and SessionsScreen; unconditional `POST_NOTIFICATIONS` request; missing physical-
keyboard key handling; stale `forgetGeneration` after pairing; reconnect loop's
`relayDownUntil` sticking to RELAY after a post-connect drop.

**OVERSTATED:**
- **AppContainer OkHttpClient "leaks until process death"** — OkHttp's dispatcher
  and connection pool self-clean idle threads/connections automatically; not
  explicitly shutting down the evicted client is a real code-quality gap but not
  the leak described.
- **FallbackBridgeClient retry/idempotency** — the audit's own stated premise
  (BridgeException is a separate type from IOException) is wrong — `BridgeException
  extends IOException`, caught by the same block; 4xx exclusion happens via an
  in-block status-code check. The behavioral conclusion (retry is fine) happens to
  still hold, but the reasoning was incorrect.

**REFUTED:**
- **RenderGrid.kt wide-char layout bug** — the code already computes
  `width = maxOf(singleWidth, chars.size)`, explicitly guarding the exact scenario
  alleged; a doc comment confirms this is intentional. No orphan blank is produced.

### Low / Polish

Mostly **CONFIRMED-accurate**: missing `dataExtractionRules`, missing
`windowSoftInputMode`, `registerFcmToken` re-running every `onCreate`, disabled
drag-handle icon missing `contentDescription`, no tests for 13 named
classes/screens, `androidTest` absent despite configured runner, no
`signingConfigs`, README omissions.

**OVERSTATED:**
- **FAB "▼" announces nothing to screen readers** — TalkBack does read the `Text`
  node; the real (milder) issue is an undescriptive raw glyph, not silence.
- **Action menu items "lack accessibility actions"** — refuted; stock Material3
  `DropdownMenuItem` already implements click semantics.
- **Envelope/Frame "hardcode v=1 with no future-proofing"** — Envelope.kt does
  explicitly reject unknown versions (just folded into a generic exception, which
  is the more precise version of the claim); Frame.kt has no version field at all,
  so lumping it into the same claim overstates that file's applicability.

**ACCURATE-BUT-NOT-A-BUG:** WorkspaceOrderStore `apply()` per drag-move (cheap in
practice); themes.xml parent mismatch (pre-Compose window chrome only, doesn't
need to match Compose's MaterialTheme).

---

## What this means for prioritization

If picking real fixes from this audit, in order of confidence + impact:

1. **bridge e2e replay race** (Critical #1) — the one finding where "Critical" is
   fully earned; combine validate+commit into one atomic transaction.
2. **Android Critical #1/#2 (logcat leaks of typed input)** — trivial fix
   (strip payload from the log line), real secret-leak risk, ship this immediately.
3. **Android Critical #5 (PairingQr scheme allowlist)** and **#3/#4 (main-thread
   crypto IO)** — straightforward, real.
4. **bridge encryption.go / relay.go missing body caps** (Critical #2/#3) — real,
   but reachability is "malicious already-paired device," not anonymous — still
   worth the one-line `MaxBytesReader` fix.
5. Everything marked OVERSTATED above is worth a mention in a backlog but not an
   urgent fix — the underlying code-quality point (add jitter, add `remember`,
   tear down the OkHttp client explicitly) is still good practice, just not the
   emergency the audit implies.
6. Skip/deprioritize anything marked REFUTED — acting on those would be wasted
   effort (the "rootful podman" fix, the "wide-char" regression test, the
   Registry.Set relock).
