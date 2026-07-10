# App↔bridge wire compatibility policy: design note

## Context

`docs/improvement-guide.md` §8 (Phase 5 — Polish backlog) flags that the
app↔bridge wire schema "has no version negotiation beyond the envelope's
`v:1`, while fields are still churning," and asks for a compat-policy note
"anchored to the `wire` package from 5.2" (the `internal/wire` extraction,
already shipped as commit `c655a2e`), proposing `X-Cmux-API-Version` + an
additive-only field rule as a starting point — "decide the story before
many app versions are installed. Don't implement negotiation machinery
until it's actually needed." This is an **L**-sized, design-only guide
item per repo `CLAUDE.md`'s rule; this document is that design note. **No
production code changes accompany it, and none are proposed for immediate
implementation** — see "What to actually do now" below for the one
narrow exception the guide's own wording allows (documentation, not code).

Invariant §1.3 (wire-format lockstep: "Any field change must land on both
sides in the same commit, with tests on both sides") is the existing
governing rule this document extends, not replaces.

## Current state (confirmed from code)

**The e2e envelope's `v` field is a hardcoded equality check, not a
negotiation point, on both sides:**

- Go: `bridge/internal/e2e/envelope.go:10` declares `V int \`json:"v"\`` on
  `bodyEnvelope`; `EncryptBody` always writes `V: 1` (envelope.go:28);
  `DecryptBody` rejects anything else outright —
  `if err := json.Unmarshal(...); err != nil || env.V != 1 { return nil,
  fmt.Errorf("decrypt_failed") }` (envelope.go:33). There is no branch for
  "an older/newer version, handle differently" — value 1 is accepted,
  every other value fails closed the same way a malformed envelope would.
- Kotlin: `data/e2e/Envelope.kt:8,24` mirrors the same shape
  (`BodyEnvelope(val v: Int, ...)`, always encoded with `v = 1`) and
  `decryptBody` performs the identical hard equality check
  (`if (envelope.v != 1) throw DecryptFailedException()`, Envelope.kt:40).

**The terminal frame format has no version marker at all** —
`bridge/internal/e2e/frame.go`'s `EncodeFrame`/`DecodeFrame` produce a raw
8-byte big-endian counter followed by ciphertext (frame.go:9-18), no JSON,
no version byte, no envelope of any kind. This is a real asymmetry worth
naming: the body envelope's `v:1` at least exists as a field, even though
it isn't used as a negotiation mechanism today; the terminal frame format
has nothing to check even in principle. Any future terminal-frame format
change has zero structural forward-compat signal to lean on.

**`internal/wire`'s own package doc comment already states the intended
discipline, as prose, not as an enforced mechanism:** "Every type here is
hand-mirrored in the Android app (`model/Dtos.kt`) — any field change must
land on both sides in the same commit, with the JSON staying byte-identical
on the wire" (`bridge/internal/wire/events.go:1-5`). This is the same rule
as invariant §1.3, restated at the point of use — good practice, but
nothing currently checks it automatically; it relies entirely on the
implementer (human or agent) reading and following the comment.

**No API-version HTTP header, or any version negotiation signal, exists
anywhere in the codebase today** — confirmed by grep across
`android/app/src/main/java/` and `bridge/` for `X-Cmux-*`, `Api-Version`,
`apiVersion`: the only `X-Cmux-*` header in use is `X-Cmux-Encrypted`
(set at `bridge/internal/server/encryption.go:111`, read at
`E2eInterceptor.kt:66`, a content-type-ish marker, not a version). The
existing custom-header vocabulary — `X-Cmux-Encrypted`, `X-Relay-Token`
(`bridge/internal/server/trusted.go:18`), `X-Device-ID`
(`bridge/internal/server/encryption.go:35`) — is a natural, precedented
home for a version header if one is ever added; naming it `X-Cmux-API-Version`
would fit the existing convention exactly.

**Both sides are, today, already additive-only-safe by construction — as
an accidental byproduct of library defaults, not deliberate policy:**

- Kotlin: `model/Dtos.kt:15-19` configures the shared codec with
  `ignoreUnknownKeys = true` (a field the server adds that an older client
  doesn't know about is silently skipped, not an error) and every DTO field
  in the file carries a default value (e.g. `Workspace`'s `id: String = ""`,
  `attention: String = ""`, `terminals: List<TerminalPane> = emptyList()`,
  Dtos.kt:24-33) — a field the *client* doesn't yet send, or a field
  *missing* from an older server's response, decodes to that default
  instead of throwing.
- Go: confirmed via `grep -rn "DisallowUnknownFields" bridge/` returning no
  results — `encoding/json.Unmarshal`'s own default behavior (ignore
  unknown keys, leave missing fields at their zero value) applies
  everywhere in this codebase, with no opt-in stricter mode anywhere.

So the guide's proposed "additive-only rule" is not a new constraint to
introduce — it is *already* how both serialization layers behave by
default, today, for every DTO in `internal/wire` and `model/Dtos.kt`. The
gap is that this is nowhere written down as a rule future changes must
deliberately preserve — someone could add `DisallowUnknownFields()` on the
Go side, or a Kotlin field without a default, without realizing they were
breaking an implicit contract nothing tests or documents.

**Wire fields churn regularly, and this repo has already shipped at least
one genuinely non-additive (breaking) change, handled the only way
available today — simultaneous deployment:** `model/Dtos.kt` has 8 commits
touching it over this repo's short history (field additions like
`attention`, `yolo_mode`, the `kind` pane badge). More significantly,
`docs/superpowers/specs/2026-06-30-terminal-surface-id-design.md` (§
"Bridge: `/sessions` response") records a top-level JSON envelope key
**rename** — `{"sessions": [...]}` → `{"workspaces": [...]}` — which
`ignoreUnknownKeys`/defaults cannot paper over (an old client parsing a
`workspaces`-keyed response with a `Workspace(val id...)` list bound to the
`sessions` key gets an empty list, not a helpful degradation). That change
shipped by updating the Go response builder and the Kotlin DTOs in the same
commit, per invariant §1.3 — no compatibility shim, no negotiation, because
none was needed: **this app's actual deployment model makes "redeploy both
sides together" trivial**, not a workaround. Per this repo's own README
(Quick start) and `android/README.md`, there is no Play Store / staged
rollout — the phone app is a self-built debug APK
(`./gradlew :app:assembleDebug`) installed by the same operator who deploys
the relay and Mac agent binaries. Today, the person upgrading the bridge is
definitionally the person upgrading the app.

## Evaluating the guide's proposed mechanism against actual need

The guide names two pieces: an `X-Cmux-API-Version` header, and an
additive-only field rule. They are not equally weighted:

- **The additive-only rule is already ~fully true today, for free**, per
  the audit above. Endorsing it costs nothing to adopt because it isn't
  actually a change — it's naming and preserving an existing, working
  default. The only real work is closing the two gaps that *aren't*
  covered by "additive fields decode safely": (a) renames/retypes, which
  additive-only explicitly does not solve and never can — those need
  either simultaneous deployment (today's working answer) or a real
  compatibility shim (deferred, see below); and (b) nothing currently
  *tests or enforces* that a future change stays additive — that's a
  process gap, not a mechanism gap.
- **A live `X-Cmux-API-Version` header only has teeth once something reads
  it and branches on it** — serving a different response shape to old
  clients, rejecting too-old clients with a clear "please update" error,
  or logging a version histogram to know what's actually deployed. Sending
  a header nobody consumes is not "version negotiation," it's inert
  metadata — cheap to add, but it doesn't solve the churn problem the
  guide is worried about by itself. Building the *consuming* half (branch
  logic, deprecation windows, multi-shape response serving) is exactly the
  "negotiation machinery" the guide says not to build yet, and this app's
  current single-operator, simultaneous-deploy reality (see above) gives
  it nothing to protect against today: there is no scenario, as currently
  deployed, where the relay and the app are running materially different
  wire-format expectations against each other for longer than one `git
  pull` + rebuild takes.

This app's README frames it explicitly as a personal/small-scale tool (a
single phone talking to a single Mac via a home-server relay one operator
runs) — not a multi-tenant SaaS with independently-updating clients in the
wild. The multi-tenant relay design
(`docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md`) does
add *multiple Mac agents* behind one relay, but does not change *this*
picture: each tenant's Mac agent and the phone(s) pairing with it are still
deployed by that tenant, together, the same way. Building real version
negotiation now would be solving a fleet-compatibility problem this app
does not have, at the cost of exactly the kind of "flexibility that isn't
needed yet" the operating discipline for this repo (`CLAUDE.md` point 2 in
the maintainer's own global instructions, and this guide's repeated
"don't over-engineer" framing) warns against.

## Recommendation

**Endorse the additive-only rule in full — codify it, don't just rely on
it continuing to hold by accident. Defer the `X-Cmux-API-Version` header
entirely, with named trigger conditions for revisiting it, rather than
building even a diagnostic-only version of it now.**

This is a deliberate scale-down from the guide's own suggested starting
point, justified by the audit above: the header's only concrete value
(observability into what's deployed where) doesn't matter yet, because
there is exactly one deployment to observe.

### What to actually do now (documentation only — matches this guide item's own scope)

1. **Extend `internal/wire`'s package doc comment** (currently
   `bridge/internal/wire/events.go:1-5`) to state the additive-only
   contract explicitly as a rule, not just describe the hand-mirroring
   fact: new fields must be optional on the Go struct (`omitempty` or a
   pointer/zero-value-safe type) and carry a Kotlin default value; a field
   already on the wire must never be repurposed to mean something
   different under the same JSON key; renaming or removing a field, or
   changing its type or meaning, is a **breaking change** and must ship as
   a single simultaneous commit across both `internal/wire` and
   `model/Dtos.kt` (today's already-working pattern, per the
   `sessions`→`workspaces` precedent above) — not attempted piecemeal.
2. **Cross-reference this rule from `CLAUDE.md`'s existing wire-format-lockstep
   invariant** (§ "Non-negotiable invariants," point 3) so it's discoverable
   from the one place every agent session already reads, rather than living
   only inside the `wire` package comment.
3. No test, linter, or CI check is proposed for this now — for a ~15-field
   wire surface reviewed by (today) one operator on every commit, a written
   rule referenced from the two places a future change is most likely to
   be authored (`internal/wire`'s doc comment, `CLAUDE.md`) is proportionate;
   a custom static-analysis rule to enforce "every new field has
   `omitempty`" is exactly the kind of machinery this note recommends
   deferring, for the same reason the header is deferred — see trigger
   conditions below.

Both of these are documentation edits to existing prose (a package comment,
a repo-root `CLAUDE.md` section) — they do not touch generated wire-format
JSON, DTO struct definitions, or any request/response handling code, so
they stay inside this guide item's "docs-only" scope. They are listed here
as the concrete near-term action; whether to make them a follow-up commit
under this same guide item, or a separate tiny item, is left to
implementation-time judgment — no code change of any kind is proposed by
this design note itself.

### What to explicitly defer, and why

- **A live, branch-on-able `X-Cmux-API-Version` request/response header**
  — no server-side logic reads it, no client-side logic sends it. Building
  the sending half without a consuming half is inert; building both is the
  "negotiation machinery" the guide says to defer, and per the audit above
  this app has no current scenario where it would do anything.
- **Any compatibility shim for non-additive changes** (e.g. serving both
  an old and new field/shape simultaneously, a versioned endpoint path) —
  today's answer to a genuine breaking change is "ship both sides in one
  commit," which the audited `sessions`→`workspaces` precedent shows
  already works cleanly at this app's actual deployment scale. Building a
  shim mechanism now would be solving for a deployment model (independently-
  updating clients that can't all be redeployed at once) this app does not
  have.
- **Enforcement tooling** (linters, CI checks) for the additive-only rule
  — the rule is cheap to state and, per the audit, already almost entirely
  self-enforcing via the serialization libraries' own defaults; tooling to
  catch the rare deviation is more machinery than a ~15-field surface
  reviewed by one operator currently justifies.

### Trigger conditions for revisiting this deferral (concrete, not "someday")

Any of the following should prompt actually building the negotiation
machinery deferred above, at that time, sized to whatever the situation
then requires:

1. The app moves off self-built debug APKs onto a distribution channel
   with staged/independent rollout (Play Store, TestFlight-equivalent, or
   even just handing signed release builds to a second household) —
   the point at which "the operator upgrading the app" and "the operator
   upgrading the bridge" can stop being the same event.
2. A second, independently-operated relay+bridge deployment exists that
   this app's maintainer does not personally control the update cadence
   of (e.g. another user of the now-public repo self-hosting their own
   instance and asking for compatibility guarantees).
3. A genuinely non-additive wire change is needed (rename/retype/remove,
   as `sessions`→`workspaces` already was once) at a moment when
   simultaneous deployment of both sides is no longer guaranteed —
   i.e., trigger 1 or 2 has already happened.

None of these are true today, per the deployment-model audit above; this
note exists so the decision is made deliberately once one of them becomes
true, rather than reactively.

## Explicit non-goals (this design)

- **No version-negotiation code of any kind** — per the guide: "Don't
  implement negotiation machinery until it's actually needed." This
  document proposes none, including no diagnostic-only header, no
  version-echoing, no server-side branch logic.
- **No changes to `internal/wire` struct definitions or `model/Dtos.kt`** —
  this is a policy document; the two documentation edits named above touch
  comments and `CLAUDE.md` prose only, not wire types.
- **No enforcement tooling** (linter, CI check, or test) for the
  additive-only rule, per the reasoning above — revisit only if a real
  violation actually ships and causes a problem, not preemptively.
- **No change to the e2e envelope's `v:1` field or its hardcoded equality
  check** (`bridge/internal/e2e/envelope.go`, `data/e2e/Envelope.kt`) —
  that field belongs to the encryption layer (invariant §1.4) and is out
  of scope for the app-facing DTO compatibility question this note
  addresses; conflating the two would risk exactly the kind of scope creep
  this note otherwise argues against.
