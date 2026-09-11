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

## [0.5.1] - 2026-09-11

### Fixed

- Panes on the alternate screen can be panned again. 0.5.0 routed swipes there
  to the pane itself, but did it by claiming the drag before the grid's own
  scrolling could start -- and the grid is 79 rows against a phone viewport, so
  only whichever rows it happened to sit at were reachable, on Claude, `less`
  and `vim` alike. A swipe now pans the grid first and pages the pane only with
  the part the grid could not absorb. Found on a pane where that local panning
  was the only scrolling that could have worked at all: an idle shell stranded
  on the alternate screen after an agent exited without restoring the primary
  buffer, which pages on nothing, so the pane read as completely frozen.
- Mouse-reporting panes on the primary screen page the same way, so they now
  pan their grid before they page. This is a behaviour change on those panes
  (opencode among them), which used to page from the first swipe.

### Compatibility

No wire-format, config or pairing change; nothing on the bridge moved. Phone
only, and the two halves stay interchangeable in both directions across this
release.

## [0.5.0] - 2026-09-10

### Added

- Push is configured on the bridge instead of being baked into the app. The
  bridge now carries the client half of the Firebase configuration --
  `fcm_app_id`, `fcm_api_key` and `fcm_sender_id`, alongside the
  `fcm_project_id` it already had -- and hands it to a phone on the pairing
  response, which builds its own `FirebaseOptions` from it. Push used to be a
  build-time decision: an APK built without a `google-services.json` could
  never register an FCM token however the bridge was configured, so anyone
  handed the APK needed an Android toolchain to turn push on at all. None of
  those four values is a secret -- Firebase ships all of them in the clear
  inside every push-enabled APK, and none authorises sending, which still
  needs the service-account key in `fcm_credentials` that never leaves the
  machine.
- Both binaries warn at startup when they find a half-filled client config, or
  credentials with no client half. The config is withheld unless all four
  fields are set, and withholding is silent on the wire -- a phone has no way
  to report what it never received, so a relay would otherwise log that push
  is enabled while every phone paired against it goes on receiving nothing.

### Fixed

- Panes on the alternate screen scroll again. Such a pane has no scrollback of
  its own, so a swipe had nothing local to move and the pane read as frozen;
  swipes there now route to the pane itself. Two things had to be fixed for
  that to work: the drag never reached the handler (a descendant consumed it
  on the Main pass), and a step sized at half the viewport was longer than a
  thumb travels, so no step was ever emitted.
- A swipe that starts on a key-bar button no longer sends that key. Compose
  ends a tap only when something consumes the movement, and a vertical drag
  over the horizontally-scrolling key row was consumed by nothing -- so a
  scroll that began on Esc still fired it on lift-off. In a Claude pane one
  Escape interrupts a running agent and two open the rewind overlay, which is
  what made rewind keep appearing while scrolling.
- A pairing that turns push on now asks for the notification permission. With
  the config arriving at pairing, the startup prompt runs before there is
  anything to prompt about; the phone then registered a token and the bridge
  started sending into a device that had never been asked, which on API 33+
  drops every notification silently until the app is relaunched.

### Compatibility

No breaking change, in either direction. A bridge with push unconfigured sends
exactly the pairing response it sent before this field existed, so an older app
decodes it unchanged; a 0.4.0 app against a push-configured 0.5.0 bridge
ignores the field it does not know.

The `fcm_app_id`, `fcm_api_key` and `fcm_sender_id` settings are optional and
new. An agent or relay that does not set them behaves exactly as it did on
0.4.0, and phones paired against it receive no push unless their APK has a
`google-services.json` compiled in.

A phone running a release APK must re-pair to receive push. The config is
delivered at pairing and nothing else carries it, so a phone paired before
0.5.0 has none stored and upgrading the app alone does not bring push up.

A phone whose APK has a `google-services.json` compiled in is unaffected and
need not re-pair: `FirebaseInitProvider` creates the default app before any of
this runs, and that baked-in config is left alone as the more specific of the
two sources. If it and a bridge-supplied one name different projects, the phone
registers against one while the bridge sends from the other and push fails
silently, so use the same project for both.

## [0.4.0] - 2026-09-09

### Added

- `GET /version` on the agent, and an About card at the bottom of the
  Connections screen showing the app's version and the agent's. The two halves
  ship separately -- the app from a release APK, the agent from a binary on the
  Mac -- so "what am I running" has two answers, and neither was visible on the
  phone. The route sits inside the authenticated route set: a build number tells
  an unauthenticated caller which fixes an agent is missing.

### Compatibility

No breaking change. An app on 0.4.0 talking to a 0.3.0 agent shows the bridge
version as "unknown" (the route 404s) and is otherwise unaffected; a 0.3.0 app
against a 0.4.0 agent simply never calls the route.


## [0.3.0] - 2026-09-08

A reliability and observability release. Every issue listed as *Known* in 0.2.0
is fixed here. The theme is transports and terminals that recover on their own
instead of failing quietly: a standby listener that retries, a terminal socket
that survives a slow backend, push registration that keeps trying, and a status
command that can finally answer "is the other transport actually working?".

### Added

- `cmux-bridge status` now prints the agent's counters (pushes sent and failed,
  pairing codes issued/redeemed/expired, e2e decrypt failures, terminal replay
  failures). The agent serves no `/debug/vars`, so these had been incrementing
  where nothing could read them.
- `cmux-bridge status` also reports, per transport slot, when that slot was last
  reached end to end by the hourly device round -- and carries the value across
  agent restarts, so it can show an outage that began before the current
  process. A slot that has never answered reads differently from one that was
  never configured.
- The agent writes to a size-bounded log file of its own, so a sustained outage
  can no longer fill the disk.
- Pastes are sent bracketed (`ESC[200~`/`ESC[201~`) when the pane has bracketed
  paste enabled, so a multi-line paste arrives as one paste instead of running
  line by line as it lands.
- The terminal falls back to a font that has the box-drawing and block glyphs
  agent output uses, instead of drawing them as missing-glyph boxes.
- The key bar shows where it continues off-screen, and asks for confirmation
  before pasting a script into a shell.
- When the relay refuses the agent's tunnel upgrade, the log now names the HTTP
  status the relay actually answered with, which distinguishes an auth refusal
  from a proxy misconfiguration from a dead backend.
- Relay binaries built in the container are stamped with their version.

### Changed

- Unread state is no longer conveyed by the workspace colour, and expandable
  cards carry a chevron, so "has unread" and "which workspace" stop competing
  for the same signal.
- The Connections screen can be exited, reports zoom honestly, and no longer
  nags about re-pairing.
- The jump-to-bottom button is translucent, so the terminal rows underneath it
  stay readable.
- A retry loop that is already failing logs once per outage plus a periodic
  reminder, instead of one line per attempt.
- An agent whose session store predates the SQLite layout is imported in place
  rather than ignored.

### Fixed

- A slow `mobile.terminal.replay` no longer tears down a live terminal socket.
  One failure used to end the handler, and the phone's reconnect issued another
  full replay against the backend that was already too slow to serve one. The
  pane is now held on its last grid and recovers on the next successful poll,
  bounded so a backend that never returns cannot hold the socket forever
  (`cmux-app-8a0`).
- The app no longer reconnects forever to a terminal surface cmux no longer
  has. The agent closes such a socket with a distinct code and the app reports
  it instead of retrying every 5s behind a spinner (`cmux-app-34c`).
- The direct (Tailscale) listener retries instead of giving up on its first
  failure. A single "bind: can't assign requested address" at startup, before
  Tailscale had assigned its address, previously ended the standby transport for
  the life of the process -- and nothing noticed, because the relay was fine the
  whole time (`cmux-app-t5x`).
- `status.json`'s direct-mode health no longer counts the agent's own probe as a
  served request, which had made the field advance hourly whether or not a phone
  had ever reached the standby (`cmux-app-8d3`).
- FCM registration is retried until a slot accepts it, instead of failing
  silently after an app update until the next launch (`cmux-app-2cm`).
- A registration token FCM reports as `UNREGISTERED` is now dropped, so dead
  tokens stop being indistinguishable from live ones (`cmux-app-6u7`).
- Drifted credentials are reaped in both directions, so a relay device row can
  no longer outlive the shared secret it depends on (`cmux-app-2vz`).
- Aborting an already-confirmed pairing no longer drives the phone-facing state
  backwards from confirmed to refused (`cmux-app-05w`).
- A repeat push no longer re-alerts a notification already on screen
  (`cmux-app-17r`, `cmux-app-3ud`).
- Both Settings "Done" paths guard the back stack instead of leaving it
  inconsistent.

### Compatibility

No wire-format change. The only protocol addition is a WebSocket close code
(4404) that an older app treats as an ordinary close, so either side can be
upgraded alone. Upgrading both is still recommended: the two terminal fixes
above are one agent-side and one app-side, and they complement each other.

### Known issues

Carried into this release and tracked in `.beads/`: a poisoned replay window can
make a slot permanently undecryptable, and re-pairing does not recover it
(`cmux-app-a3g`); a direct-slot credential can die silently, producing a 401
lockout the moment the relay goes down (`cmux-app-hr1`); replay still exceeds
even the 20s budget on roughly 1.25% of terminal opens, which tracks the cmux
process's age rather than anything in this repo (`cmux-app-8a0`); attention
pushes are sent twice to a phone paired on both slots (`cmux-app-8jb`);
subprocess cmux RPC errors are untyped, so `not_found` handling only works on
the fast path (`cmux-app-aft`); opening an agent pane costs a burst of heavy
frames, and watching a running agent costs a sustained one (`cmux-app-dfl`); the
relay's `/healthz` can answer 200 while the agent's own tunnel handshake still
fails (`cmux-app-hxn`).

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
