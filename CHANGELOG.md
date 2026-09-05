# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This file starts now that the repo is public; changes from before this point
aren't retroactively itemized commit-by-commit here — see `git log` for the
full history. The 0.1.0 section is therefore a one-time snapshot of where the
project stood at first release, grouped by capability rather than by commit;
every section after it itemizes changes individually. Purely internal refactors
(string extraction, renames, test-only changes) are deliberately omitted.

## [Unreleased]

## [0.2.0] - 2026-09-05

A security release. Every credential in the system now has a way to be taken
back: a paired device can be revoked from either end, revocation terminates the
sessions already running on it, and each pairing gets its own keypair so one
device's key can never speak for another's. Pairing also gained an explicit
operator-approval step. Upgrade both sides together — see *Compatibility*.

### Added

- `cmux-bridge devices` — list and revoke the devices paired with an agent, so
  a lost or retired phone can actually be cut off. `cmux-relay devices
  list|revoke` gains the tenant-scoped equivalent, keyed by token hash, and
  `devices revoke` accepts the identifier `devices list` prints.
- `POST /devices/self-revoke`, letting a device retire its own token — this is
  what makes the app's **Forget** action revoke server-side instead of only
  clearing local state.
- An operator-approval step in pairing: the agent now signals that the person
  at the Mac said yes, and the phone waits for that answer rather than assuming
  it. A pairing has a distinct state between *redeemed* and *finished*.
- Per-slot credential health: the app records which slots a server has rejected,
  reports what each slot said when registering the device, and surfaces a
  rejected credential *before* it is the only one left rather than after
  connectivity is already gone.
- Each workspace's cmux-picked color renders as an identifying dot on its card.
- Vertical swipes page through TUIs that own their own scrolling (opencode and
  friends, which enable DEC mouse reporting and keep their PTY scrollback
  empty); they become PgUp/PgDn, which those parsers actually consume.

### Changed

- **Each pairing now mints its own keypair** instead of reusing one persistent
  device identity, so compromise of one pairing cannot decrypt another's
  traffic. The agent rejects a shared secret already paired to a different
  device.
- Re-pairing retires the credential it replaces, and a refused pairing revokes
  the token it had already minted, instead of leaving either valid forever.
- The app pauses its streaming sockets and event-driven refetches while it is
  backgrounded — the single biggest lever on idle cellular usage, since the
  subscriptions previously ran with the screen off. Push still covers attention
  while paused. The Inbox badge also refetches only on feed frames, so terminal
  output churn no longer costs a second full request per burst.
- The terminal refreshes immediately after input rather than waiting out the
  remaining poll tick, which made remote scrolling feel seconds behind the
  finger.
- Connection status reports the transport actually in use rather than the one
  preferred, and the direct listener reports what it is doing rather than only
  that it bound.
- The FCM token is registered on every configured slot, not just the first
  reachable one — previously a failover left push registered against the slot
  that was up at launch.
- A failed tunnel dial reports every address it tried.
- `detekt` is now part of the commit gate it had been missing from; it enforces
  import ordering and declaration spacing nothing else catches.

### Fixed

- A duplicate push could downgrade an already-delivered notification to a
  placeholder, replacing the real prompt text with a generic body.
- The terminal could replay a line that no longer existed. Terminal replay also
  gets its own deadline instead of inheriting the request's.
- Auto-scroll fought the user: on an actively streaming pane, the
  stick-to-bottom effect restarted every frame and snapped back before an
  upward swipe could take, making the pane feel unscrollable while output
  flowed.
- A socket is returned to the relay once the relay recovers, instead of staying
  on the fallback transport for the rest of the session.
- The relay admin CLI answered from a store it had just created (so it reported
  nothing), and accepted a `--config` stranded behind the subcommand.
- A relay 401 is no longer read as more than it can actually claim; a slot is
  marked rejected on any 401, not only on the launch probe.
- Attention push bodies carry the real pending prompt from `feed.list` instead
  of cmux's general last-activity preview, which could surface an unrelated
  system banner as the apparent reason an agent needed you.
- Relay → Tailscale failover is visible in the UI instead of silent.
- Answered prompts leave the Inbox immediately rather than lingering until the
  next feed update.

### Security

- Per-pairing key separation (see *Changed*) — the headline of this release.
- Revocation now terminates live state, not just future auth: unpairing or
  revoking a device closes its relay connections and its open sockets, and
  replacing a slot's credentials ends the sockets still running on the old
  ones. Previously a revoked device kept its established sessions.
- Shared secrets that no server has a device row for are reaped, so a secret
  cannot outlive the device it was minted for. (The converse — device rows
  outliving their shared secret, `cmux-app-2vz` — is still open.)
- The Android receive path performs validate/decrypt/commit as one atomic step,
  and a replay-window rejection is reported distinctly from a decrypt failure
  so the two stop being conflated in diagnostics.

### Compatibility

Per-pairing keys and the new pairing-confirmation state change the pairing
protocol. Update the app, `cmux-bridge` and `cmux-relay` together; existing
pairings continue to work, but pairing a 0.2.0 app against a 0.1.0 agent (or the
reverse) is not supported. Re-pair after upgrading both ends.

### Known issues

Carried into this release and tracked in `.beads/`: FCM token re-registration
fails silently after an app update until the next launch (`cmux-app-2cm`); push
titles are generic rather than naming the workspace (`cmux-app-17r`); dead FCM
tokens are indistinguishable from live ones (`cmux-app-6u7`);
`status.json`'s `direct_last_served_at` is stamped by the reaper's own probe and
so cannot report standby health (`cmux-app-8d3`); relay device rows can outlive
their shared secret (`cmux-app-2vz`); aborting an already-confirmed pairing
drives the phone-facing state backwards from confirmed to refused
(`cmux-app-05w`); the relay tunnel flaps on IPv6 handshake failures
(`cmux-app-to8`); a sustained relay outage floods the agent log, which has no
rotation (`cmux-app-5v1`).

## [0.1.0] - 2026-07-20

### Added

- Android client (Kotlin/Compose): workspaces list, a live terminal that
  renders cmux's `render-grid` cell grid (styles, colors, cursor,
  scrollback), an agent inbox for replying to blocking prompts, workspace
  rename, drag-to-reorder, and per-workspace YOLO auto-reply modes
  (Off/Always/All tools/Bypass).
- `cmux-relay` (home-server daemon) and `cmux-bridge agent` (Mac) Go
  binaries, with self-service QR/manual device pairing and a multi-tenant
  relay serving many independent Mac agents behind one mTLS edge.
- End-to-end encryption between phone and Mac agent (X25519 + HKDF-derived
  shared secret, AEAD on every HTTP body and terminal WebSocket frame,
  replay-protected counters) — the relay routes traffic by tenant but
  cannot read its contents, including push-notification bodies.
- Optional FCM push notifications ("an agent needs you"), off by default
  and requiring no Firebase config to build.
- Direct (Tailscale) connection mode as an additive alternative to the
  relay, with automatic dual-pairing fallback between relay and direct
  slots.
- Terminal UX: fit-to-width sizing, pinch-to-zoom, a word-wrap toggle, text
  selection, a compact D-pad, an Enter key, DECCKM-aware cursor keys, a
  latching Ctrl modifier chip, and TalkBack accessibility semantics.
- Operability: structured logging (`log/slog`), `expvar` metrics at the
  relay/pairing/push/e2e choke points, a `cmux-bridge status` subcommand,
  rate limiting on device pairing, GitHub Actions CI (bridge + android),
  `golangci-lint`/`detekt`, and Dependabot.
- Permission-request prompts render their content in the Inbox and can be
  answered there; previously only `AskUserQuestion` items had anything to
  show and a `permissionRequest` rendered an empty card.
- A terminal-pane picker when an attention notification can't resolve a
  single pane — cmux reports no per-pane id on the events that raise a push,
  so multi-pane workspaces previously dumped you on the sessions list.
- A persistent terminal font-size setting.

### Changed

- Migrated the agent-local `internal/e2e` and `internal/yolo` stores from
  a JSON file to SQLite, closing a cross-process clobber and a
  full-file-rewrite-per-terminal-frame cost.
- Migrated `internal/auth`'s device/token store to a multi-tenant
  SQLite-backed store with hashed tokens.
- Extracted the app-facing wire contract into a shared `internal/wire` Go
  package and deduplicated the pairing handlers previously duplicated
  between the relay and direct-mode server.
- Android toolchain moved to compileSdk 36, AGP 8.13, Kotlin 2.3, Compose BOM
  2026.06 and Gradle 8.14. Unit tests now require a JDK 21+ runtime (app code
  still targets JVM 17).
- Removed the "N workspaces on autopilot" banner — each workspace card
  already badges its own YOLO mode.

### Fixed

- A set of correctness and security findings from an internal audit (see
  `docs/enhancement-audit.md` and `docs/enhancement-audit-validation.md`):
  a replay-counter validate/commit race, unbounded reads on decrypt input,
  a cross-tenant push-registration leak, and related hardening.
- A stale pooled connection to cmux's control socket (after a cmux restart or
  a sleep/wake) surfaced as a spurious `cmux unavailable` 502 that only
  cleared on a manual refresh; the request is now retried.
- Inbox items are matched to their workspace by cwd, so `/sessions` and
  `/feed/pending` now canonicalize it — on macOS cmux reports the same
  location as both `/tmp/foo` and `/private/tmp/foo`, which broke the match.
- The Inbox badge counted workspaces with cmux's `has_unread` flag, which
  fires on any new output, so it could show a count while the Inbox was
  empty. It now counts actual pending items.
- Inbox "open terminal" routed to the wrong workspace when several shared a
  cwd prefix.
- Attention notifications are cancelled on open, repeat taps deep-link
  correctly, and notification-tap navigation no longer stacks duplicate
  screens on the back stack.
- The Connections screen scrolls, so its lower fields are reachable on
  shorter screens.
- Test pushes (`type=test`) no longer fail the client-side type check.

### Security

- Attention push-notification content is now end-to-end encrypted, so the
  relay operator cannot read a push's title/body even in relay-routed mode.
- Pairing now requires confirming a short fingerprint (SAS) of the exchanged
  public keys on both the phone and the Mac before either side trusts the
  other's key. The relay brokers that exchange, so without this step a
  malicious or compromised relay could substitute its own key and read all
  traffic for a newly paired device. The fingerprint stays visible after
  pairing completes so it can be re-checked.
- The Android e2e send/receive counters are now persisted durably *before*
  use, closing an AEAD nonce-reuse risk if the app process died between
  encrypting a frame and recording the counter.
