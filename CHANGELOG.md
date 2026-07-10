# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This file starts now that the repo is public; changes from before this point
aren't retroactively itemized commit-by-commit here — see `git log` for the
full history. The summary below is a one-time snapshot of where the project
stood at that point, grouped by capability rather than by commit.

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

### Changed

- Migrated the agent-local `internal/e2e` and `internal/yolo` stores from
  a JSON file to SQLite, closing a cross-process clobber and a
  full-file-rewrite-per-terminal-frame cost.
- Migrated `internal/auth`'s device/token store to a multi-tenant
  SQLite-backed store with hashed tokens.
- Extracted the app-facing wire contract into a shared `internal/wire` Go
  package and deduplicated the pairing handlers previously duplicated
  between the relay and direct-mode server.

### Fixed

- A set of correctness and security findings from an internal audit (see
  `docs/enhancement-audit.md` and `docs/enhancement-audit-validation.md`):
  a replay-counter validate/commit race, unbounded reads on decrypt input,
  a cross-tenant push-registration leak, and related hardening.

### Security

- Attention push-notification content is now end-to-end encrypted, so the
  relay operator cannot read a push's title/body even in relay-routed mode.
