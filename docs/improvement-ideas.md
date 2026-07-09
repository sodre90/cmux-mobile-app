# Improvement ideas

A backlog of ideas from a broad pass over the repo (2026-07-07). Not commitments,
just candidates — pick off whatever's highest value.

## Reliability / CI

- **No CI.** There's no `.github/workflows` at all. Given the security model
  leans on tests actually passing (multitenant isolation test, e2e encryption
  tests), running `go test ./...` and `./gradlew testDebugUnitTest` only
  locally is risky. Highest-value single addition.
- **No linter/static-analysis config** — no `golangci-lint`, `ktlint`, or
  `detekt`. Cheap to add, pairs naturally with CI.
- **Android test coverage is JVM-unit-only** (23/68 files, 0 instrumented
  Compose tests). Worth a handful of Compose UI tests for the highest-stakes
  flows: pairing, YOLO badge, terminal render.
- **No Dependabot/Renovate config** for `go.mod` or Gradle — dependencies can
  drift out of security patches silently.

## Security / resilience

(Already strong: mTLS, e2e encryption, per-tenant adversarial isolation test.)

- **No rate limiting on relay auth endpoints** (pairing redeem, device
  register) beyond single-use codes. The pairing code itself has solid
  entropy (8 chars, 32-char alphabet ≈ 1.1 trillion combos) plus a short TTL,
  so brute force isn't realistic today — but per-IP throttling on auth routes
  is cheap defense-in-depth for a relay that's reachable from the internet.
- The in-progress **`FastPath` direct-socket client**
  (`bridge/internal/cmux/client.go` / `socket_client.go`) is a nice latency
  win, and its comments already correctly flag the idempotency risk of
  falling back mid-request. Worth an adversarial test specifically for
  "auth succeeds, send partially fails" once it lands.
- **No crash/ANR reporting on Android** (no Crashlytics/Sentry). Since this
  isn't Play Store distributed, a crash on someone's phone is invisible
  unless they notice and report it.

## Observability

- Relay has `/healthz` but **no metrics** (tunnel count, request/error
  rates). A few counters would surface breakage before it requires
  spelunking (cf. the push-notification root-cause hunt).
- **Logging is scattered**, not structured. Adopting `log/slog` consistently
  with tenant ID as a structured field would make multi-tenant issues much
  faster to trace.

## Code quality / tech debt

- `TerminalInputDiff.kt` is explicitly a prefix-only diff (the comment
  acknowledges it over-erases on mid-line edits). Correct but not minimal —
  a real cursor-aware edit script would send fewer keystrokes. Low priority;
  correctness isn't at risk.
- Android release build has `isMinifyEnabled = false` — enabling R8
  shrink/obfuscate is a one-line change for a smaller, slightly hardened
  release APK.
- `tailscale-direct-transport` branch still exists remotely even though that
  work merged to main on 2026-07-04 — worth confirming and deleting.

## Process

- The current uncommitted diff spans 19 files across android + bridge in one
  lump (input-diffing, fast-path socket client, terminal changes together).
  The git log otherwise shows disciplined one-feature-per-commit history —
  worth splitting this before committing, for easier bisection later.
- No CHANGELOG.md — now that the repo is public, a lightweight changelog
  would save future reconstruction of "what shipped when" via `git log`.
- Docs are excellent (a spec+plan pair per feature) but there's no single
  onboarding script tying relay + nginx + cert bootstrap together — the
  README's 5 manual steps could become one `bridge/deploy` script for faster
  re-deploys.

## Minor

- No accessibility pass noted (`contentDescription` on the D-pad/terminal
  controls) — worth a quick TalkBack check given the app is meant for
  on-the-go phone use.

## Usability / UX

Grounded in a read of the four main screens (`SessionsScreen`,
`TerminalScreen`, `InboxScreen`, pairing/`ConnectionSettingsScreen`).

- **Rename and YOLO mode are only reachable via long-press, with zero visual
  hint that a long-press menu exists.** `WorkspaceCard` has no "⋮" affordance
  — a first-time user has no way to discover these actions except by
  accident. Add a small overflow icon that opens the same `DropdownMenu`,
  keep long-press as the power-user shortcut.
- **`Bypass` YOLO mode is visually identical to the other three options**
  despite being the most consequential — it mirrors Claude Code's
  `--dangerously-skip-permissions` and removes the safety net entirely for
  that workspace. It's one accidental tap away in a plain radio list. Worth
  a distinct (warning-colored) treatment or a second confirmation tap before
  it takes effect.
- **The top-bar "Inbox" button carries no unread badge.** `hasUnread` is
  already tracked per-workspace (the small red dot on each card) but never
  aggregated onto the button that actually opens the inbox — so checking
  for pending prompts always costs a tap, even when there's nothing there.
- **No search/filter across the workspace list.** Fine at today's scale, but
  the whole point of the app is running many parallel cmux agent sessions —
  that's exactly the scenario where scrolling to find one workspace gets
  tedious.
- **The "Waiting first" sort toggle isn't persisted** (`remember` only),
  unlike the drag order which explicitly saves to `WorkspaceOrderStore`. A
  user who prefers the attention-sorted view has to re-toggle it after every
  app restart — likely an oversight given the two live right next to each
  other in the same composable.
- **The terminal key bar only exposes three hardcoded Ctrl combos** (^C, ^D,
  ^Z). Anything else readline/vim/tmux use constantly — Ctrl+L, Ctrl+A/E,
  Ctrl+R — is unreachable from the phone. A toggleable "Ctrl" modifier chip
  that composes with the next tapped key would generalize this instead of
  hardcoding more one-offs over time.
- **Inbox rows have no link back to the originating workspace's live
  terminal.** If a pending question is ambiguous without surrounding
  context, the user has to back out to Sessions and find the workspace by
  hand rather than jumping straight to it from the inbox card.
- **No in-app way to verify push actually works** after Firebase setup —
  given how much debugging the initial push setup already took (missing
  `google-services.json` + relay FCM creds), a "send test notification"
  button in Connections/Settings would turn future push issues into a
  30-second check instead of a repeat investigation.
- **No at-a-glance summary of which workspaces are in `Always`/`Bypass`
  mode** across the whole list — each row shows its own badge, but there's
  no single place to confirm "nothing is currently on autopilot" before,
  say, stepping away from the phone for a while.
