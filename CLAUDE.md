# cmux-app — agent operating card

Android (Kotlin/Compose) phone client + two Go binaries (`cmux-relay` home-server
daemon, `cmux-bridge agent` on the Mac). Full context: `docs/improvement-guide.md`
(read it before any non-trivial change). Architecture/security model: `README.md`.

## Build & verify (run before every commit)

```bash
cd bridge && go build ./... && go vet ./... && go test ./...
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Note: `testDebugUnitTest` needs a JDK 21+ runtime (a test-only dependency,
`lazysodium-java`, ships Java 21 class files) even though app code targets JVM 17.

## Non-negotiable invariants

1. **cmux is a black box.** Only talk to it via `cmux rpc` / `cmux events`.
   Never copy cmux source. Never add workspace create/close/restore to the
   bridge — it is deliberately read/terminal-I/O/feed-reply/rename only.
2. **`internal/relay/multitenant_test.go` must always pass** — it enforces
   tenant isolation, not just a test.
3. **Wire-format lockstep.** The app<->bridge protocol is hand-mirrored in
   Kotlin (`model/Dtos.kt`, `data/e2e/*`) and Go (`internal/server/*`,
   `internal/e2e/*`) — pairing DTOs also exist a third time in
   `internal/relay/relay.go`. Any field change lands on ALL copies in the same
   commit, with tests on every side touched.
4. **Never weaken e2e crypto**: X25519+HKDF, AEAD on every body/terminal
   frame, replay-protected counters (validate+commit is atomic — keep it so).
   The relay must stay blind to content.
5. **Never log secrets**: typed input, tokens, key material must never reach
   logcat or Go logs.
6. **Commits**: one logical item per commit, authored solely by the human
   developer, no AI co-author trailers.

## Working conventions

- Locate code by symbol (grep), not by line numbers in docs — they drift.
- One guide item = one branch/commit; don't bundle refactor with behavior change.
- For anything **L-sized** (multi-day / needs a design pass): write a spec +
  plan pair under `docs/superpowers/` first (see existing pairs there for the
  format), get it reviewed, then implement.
- Don't act on `docs/improvement-guide.md` §9 (explicit non-goals) — those were
  investigated and refuted.
