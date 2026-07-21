---
type: research-note
description: "Backlog of improvement ideas from a broad 2026-07-07 repo pass (CI, security, observability, code quality, process, UX) — candidates, not commitments."
status: provisional
sources: ["../docs/improvement-ideas.md"]
created: 2026-07-21
author: sodre90
tags: ["research", "provisional", "backlog", "ux"]
---

## Summary

A backlog of improvement candidates from a broad pass over the repo on 2026-07-07, source: [docs/improvement-ideas.md](../docs/improvement-ideas.md). Explicitly framed as candidates, not commitments — pick off whatever's highest value. Some items were later superseded by decisions captured in [roadmap](../articles/roadmap.md) (CI, structural cleanup, operability, UX are now phased there) and by findings in [enhancement-audit](../articles/enhancement-audit.md); this note keeps the original raw backlog intact rather than pruning it, so items not yet promoted to a phase are still visible.

## Body

### Reliability / CI

- No CI at all (no `.github/workflows`) — given the security model leans on tests actually passing (multitenant isolation test, e2e encryption tests), running `go test ./...` and `./gradlew testDebugUnitTest` only locally is risky. Flagged as the highest-value single addition.
- No linter/static-analysis config (`golangci-lint`, `ktlint`, `detekt`) — cheap to add, pairs naturally with CI.
- Android test coverage is JVM-unit-only (23/68 files, 0 instrumented Compose tests) — worth a handful of Compose UI tests for the highest-stakes flows: pairing, YOLO badge, terminal render.
- No Dependabot/Renovate config for `go.mod` or Gradle — dependencies can drift out of security patches silently.

### Security / resilience

Already strong at the time of this pass: mTLS, e2e encryption, per-tenant adversarial isolation test (see [pairing-e2e-encryption](../articles/features/pairing-e2e-encryption.md)).

- No rate limiting on relay auth endpoints (pairing redeem, device register) beyond single-use codes. The pairing code itself has solid entropy (8 chars, 32-char alphabet ≈ 1.1 trillion combos) plus a short TTL, so brute force isn't realistic today — but per-IP throttling on auth routes is cheap defense-in-depth for a relay that's reachable from the internet.
- The in-progress `FastPath` direct-socket client (`bridge/internal/cmux/client.go` / `socket_client.go`) is a nice latency win, and its comments already correctly flag the idempotency risk of falling back mid-request. Worth an adversarial test specifically for "auth succeeds, send partially fails" once it lands.
- No crash/ANR reporting on Android (no Crashlytics/Sentry). Since the app isn't Play Store distributed, a crash on someone's phone is invisible unless they notice and report it.

### Observability

- Relay has `/healthz` but no metrics (tunnel count, request/error rates). A few counters would surface breakage before it requires spelunking (cf. the push-notification root-cause hunt — see [push-notifications](../articles/features/push-notifications.md)).
- Logging is scattered, not structured. Adopting `log/slog` consistently with tenant ID as a structured field would make multi-tenant issues much faster to trace.

### Code quality / tech debt

- `TerminalInputDiff.kt` is explicitly a prefix-only diff (the comment acknowledges it over-erases on mid-line edits). Correct but not minimal — a real cursor-aware edit script would send fewer keystrokes. Low priority; correctness isn't at risk.
- Android release build has `isMinifyEnabled = false` — enabling R8 shrink/obfuscate is a one-line change for a smaller, slightly hardened release APK.
- `tailscale-direct-transport` branch still existed remotely even though that work merged to main on 2026-07-04 — worth confirming and deleting.

### Process

- At the time of this pass, an uncommitted diff spanned 19 files across android + bridge in one lump (input-diffing, fast-path socket client, terminal changes together), against an otherwise disciplined one-feature-per-commit git history — worth splitting before committing, for easier bisection later.
- No CHANGELOG.md — now that the repo is public, a lightweight changelog would save future reconstruction of "what shipped when" via `git log`.
- Docs are extensive (a spec+plan pair per feature under `docs/superpowers/`) but there was no single onboarding script tying relay + nginx + cert bootstrap together — the README's manual steps could become one `bridge/deploy` script for faster re-deploys.

### Minor

- No accessibility pass noted (`contentDescription` on the D-pad/terminal controls) — worth a quick TalkBack check given the app is meant for on-the-go phone use.

### Usability / UX

Grounded in a read of the four main screens (`SessionsScreen`, `TerminalScreen`, `InboxScreen`, pairing/`ConnectionSettingsScreen`).

- Rename and YOLO mode are only reachable via long-press, with zero visual hint that a long-press menu exists. `WorkspaceCard` had no "⋮" affordance — a first-time user has no way to discover these actions except by accident. Suggested fix: add a small overflow icon that opens the same `DropdownMenu`, keep long-press as the power-user shortcut.
- `Bypass` YOLO mode was visually identical to the other three options despite being the most consequential — it mirrors Claude Code's `--dangerously-skip-permissions` and removes the safety net entirely for that workspace. One accidental tap away in a plain radio list. Suggested a distinct (warning-colored) treatment or a second confirmation tap.
- The top-bar "Inbox" button carried no unread badge. `hasUnread` was already tracked per-workspace (the small red dot on each card) but never aggregated onto the button that actually opens the inbox — so checking for pending prompts always cost a tap, even when there was nothing there.
- No search/filter across the workspace list. Fine at that scale, but the whole point of the app is running many parallel cmux agent sessions — that's exactly the scenario where scrolling to find one workspace gets tedious.
- The "Waiting first" sort toggle wasn't persisted (`remember` only), unlike the drag order which explicitly saved to `WorkspaceOrderStore`. A user who prefers the attention-sorted view had to re-toggle it after every app restart.
- The terminal key bar only exposed three hardcoded Ctrl combos (^C, ^D, ^Z). Anything else readline/vim/tmux use constantly — Ctrl+L, Ctrl+A/E, Ctrl+R — was unreachable from the phone. Suggested a toggleable "Ctrl" modifier chip that composes with the next tapped key, instead of hardcoding more one-offs over time.
- Inbox rows had no link back to the originating workspace's live terminal. If a pending question was ambiguous without surrounding context, the user had to back out to Sessions and find the workspace by hand rather than jumping straight to it from the inbox card.
- No in-app way to verify push actually worked after Firebase setup — given how much debugging the initial push setup already took (missing `google-services.json` + relay FCM creds), a "send test notification" button in Connections/Settings would turn future push issues into a 30-second check instead of a repeat investigation.
- No at-a-glance summary of which workspaces were in `Always`/`Bypass` mode across the whole list — each row showed its own badge, but there was no single place to confirm "nothing is currently on autopilot" before, say, stepping away from the phone for a while.

### Status

Provisional — not yet triaged into accepted/rejected/superseded per item. Several CI, structural, and operability items were subsequently formalized as phases in [roadmap](../articles/roadmap.md); the UX items here have not yet been cross-checked against that promotion and may still be fully open.

## References

- [docs/improvement-ideas.md](../docs/improvement-ideas.md) — original backlog, source for this note.
- [roadmap](../articles/roadmap.md) — later phased plan that promoted several of these items.
- [enhancement-audit](../articles/enhancement-audit.md) — related point-in-time quality/security review.
