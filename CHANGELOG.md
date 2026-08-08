# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This file starts now that the repo is public; changes from before this point
aren't retroactively itemized commit-by-commit here — see `git log` for the
full history. Each section below opens with a one-time snapshot of where the
project stood at that point, grouped by capability rather than by commit, and
itemizes changes individually from there on. Purely internal refactors
(string extraction, renames, test-only changes) are deliberately omitted.

## [Unreleased]

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
- A connection indicator showing when the app has failed over from the relay
  to the direct (Tailscale) slot, which until now happened silently.
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
- Attention push bodies now carry the real prompt text from `feed.list` (the
  question verbatim, or `Wants to run <tool>: <command>`) instead of cmux's
  general last-activity preview, which could surface an unrelated system
  banner as the apparent reason an agent needed you.
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
- Answered prompts leave the Inbox immediately instead of lingering until the
  next feed update.
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
