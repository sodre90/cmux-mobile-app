# Pairing key-exchange authentication: SAS/fingerprint confirmation design

## Context

A multi-agent security review (7 dimensions, 31 agents, 3-vote adversarial
verification per finding — see memory) confirmed a Critical finding: a
fully compromised relay/relay operator can MITM the e2e key exchange during
device pairing. The user chose "Critical first, design pass" — write this
doc before touching code, since the fix changes pairing UX on both the Go
CLI and the Android app, per repo `CLAUDE.md`'s rule for L-sized changes.

This is a security-property gap, not a functional bug, so "Access-pattern
audit" (the template's usual name for this section, see
`2026-07-10-e2e-store-persistence-design.md`) is renamed **Protocol audit**
below — same purpose, everything cited from the actual code, nothing
assumed.

The original design, `docs/superpowers/specs/2026-07-01-multi-tenant-relay-design.md:45-65`,
states the intended security property explicitly: "a compromised relay
cannot MITM the key exchange (same trust model as Signal/WhatsApp Web
linked devices)," naming "a fully compromised relay host (or a malicious
operator)" as the threat actor this whole system is built to withstand
(repo `CLAUDE.md` invariant 4: "never weaken e2e crypto"). That property
held for QR-only pairing because the QR payload — including the agent's
raw public key — is rendered directly to the Mac's terminal
(`bridge/cmd/cmux-bridge/pair.go:130-136`, `qrterminal.GenerateHalfBlock`)
and reaches the phone purely via camera scan: the relay never touches that
channel, so it cannot substitute a key on that leg.

Manual pairing-code entry (`GET /devices/pair-info/{code}`,
`bridge/internal/pairing/pairing.go:120-141`) was added later, commit
`425b46d2b` ("bridge: add GET /devices/pair-info/{code} for manual pairing
entry", 2026-07-03), with **no accompanying design doc** (confirmed via git
log + repo grep). The original spec had explicitly flagged this as future,
out-of-scope work:
`2026-07-02-android-device-pairing-e2e-encryption-design.md:238-242` — "a
future iteration could add manual code entry, but it's out of scope here."
That flag was never revisited when the feature actually shipped, which is
how this gap went unreviewed.

## Problem statement, precisely scoped

Device pairing is an unauthenticated X25519 ECDH exchange: each side learns
the other's raw public key over a channel the relay operates, then derives
a shared secret via `deriveSharedSecret`/`DeriveSharedSecret`. Standard ECDH
provides confidentiality against passive eavesdroppers but **no
authentication** — whoever controls what each side sees as "the other
party's public key" controls what shared secret each side actually derives,
including a relay that independently substitutes its own keys on both legs
and computes both resulting secrets itself (textbook ECDH MITM).

The fix must ensure that whenever the relay attempts this substitution on
either leg, a human notices before either side commits (persists
credentials/derives a trusted session) — not just detects it after the
fact.

## Protocol audit (confirmed from code)

Two independent trust legs exist, and — this is the key correction to the
review's framing — **both are exploitable today, not just the manual-entry
one the review named**:

### Leg 1 — phone's belief about the agent's public key

- **QR-scan path** (`PairingViewModel.onQrScanned` →
  `parsePairingQr`): the phone reads `agent_pubkey` straight out of the
  scanned QR JSON. The relay never mediates this value. **Safe** — this is
  the leg the original spec's rationale was actually describing.
- **Manual-entry path** (`PairingViewModel.onManualEntrySubmitted` →
  `PairingClient.resolveManualCode` → `resolvePairingCode`,
  `PairingClient.kt:84-103`): the phone asks the relay,
  `GET /devices/pair-info/{code}`, and trusts whatever `agent_pubkey` comes
  back (`pairing.go:129-141`, `h.store.PairingCodeInfo(code)` — server-side
  state the relay operator fully controls). **Exploitable.**

### Leg 2 — agent's (CLI's) belief about the device's public key

- **Unconditional, regardless of which path the phone used**
  (`pollPairingCode`, `pair.go:89-107` → `pairDevice`, `pair.go:142-165`):
  the CLI's only source of truth for "which device just paired" is
  `GET /agent/pairing-code/{code}`'s `device_pubkey` field
  (`pairing.go:98-118`, `h.store.PairingCodeStatus(tenantID, code)` — again
  relay-owned server-side state, not authenticated by anything the CLI
  independently holds). The CLI has no way to know whether the phone used
  QR or manual entry, and no cryptographic tie-back to the code it itself
  minted beyond "the relay says this code is now redeemed." **Exploitable
  on every pairing, including 100% QR-scan ones.**

This means the actual vulnerability is broader than "manual entry can be
MITM'd": **a fully malicious relay can fabricate an entire phantom
pairing — serving `pairingCodeStatus` a `device_pubkey` of its own choosing
— without any real phone ever calling `/devices/pair` at all**, any time an
operator happens to run `cmux-bridge pair-device`. `RedeemPairingCode`
(`pairing.go:165`) and `PairingCodeStatus` (`pairing.go:108`) are both
plain reads/writes against `auth.Store`, a process the relay operator
controls directly — there is no signature or MAC tying a `/devices/pair`
POST to what a later `pairingCodeStatus` poll reports.

For a **full bidirectional live MITM** (attacker silently relays decrypted
terminal I/O both ways, not just a broken/phantom pairing), the attacker
additionally needs the phone to accept a substituted agent key — which,
per Leg 1 above, requires the phone to have used manual entry. So: **manual
entry is what upgrades the always-present agent-side gap into a full live
MITM**, but closing manual entry alone (Direction (b) below) leaves the
phantom-pairing / agent-impersonated-toward-attacker gap wide open. Any
fix must close Leg 2 unconditionally, not just gate Leg 1.

## Directions evaluated

### (a) Mutual SAS/fingerprint confirmation, both sides, every pairing (recommended)

A short authentication string (SAS), Signal/WhatsApp-safety-number style:
both peers independently compute a short human-comparable fingerprint from
**both raw public keys** and a person visually compares the two displayed
values before either side commits.

Why this closes both legs with one mechanism: each side's fingerprint is
`H(own real pubkey, other party's pubkey as told to me)`. The attacker can
make "as told to me" wrong on one or both legs, but cannot make *both*
independently-computed fingerprints match unless it passes every key
through unmodified — because doing so requires
`H(phone_pub, X) == H(agent_pub, Y)` for the values X, Y actually used on
each leg, which only holds when X = agent's real pubkey and Y = phone's
real pubkey (found no feasible collision within a live ~5-minute pairing
deadline — see bit-length justification below). This holds regardless of
whether the phone used QR or manual entry, so both legs get one uniform
gate with zero path-based special-casing and zero new wire fields (each
side already possesses both raw pubkeys locally before deriving the shared
secret).

- **Con**: adds a step to what's currently a one-scan/instant flow on both
  CLI (interactive prompt) and phone (a screen with a Confirm/Cancel
  action). This is the real cost of closing a Critical vuln, named
  explicitly rather than glossed over.
- **Con**: still fundamentally requires a human paying attention — a user
  who reflexively taps "Confirm" without comparing degrades back to today's
  behavior. Same acknowledged limitation Signal/WhatsApp/Bluetooth SSP all
  have; not solvable by a purely technical mechanism.

### (b) Remove manual pairing-code entry, QR-only

Directly undoes commit `425b46d2b`. Closes Leg 1's manual-entry gap.
**Does not close Leg 2** — the CLI's `pollPairingCode` trust gap exists
independent of how the phone paired, so a malicious relay retains the
ability to fabricate phantom device pairings against the CLI even with
manual entry gone entirely. Rejected as insufficient on its own; the
"obvious minimal revert" doesn't actually close the Critical finding's
full blast radius.

### (c) Cryptographically bind the pairing code (MAC/signature over the redemption)

Idea: have the agent's originally-requested code carry a MAC the relay
can't forge, or have the phone's POST include a signature the CLI can
verify against something pre-shared. Dead end: pairing is a **first
contact** between two devices that have never met — there is no
pre-shared secret to MAC or sign anything with yet. Any data whose sole
transport is the relay is, by the explicitly-adopted fully-malicious-relay
threat model, attacker-controllable no matter how it's encoded. The only
way to defeat this is a channel the relay doesn't mediate at all — either
physical (QR) or human-verified (SAS). Rejected; not a working direction,
included here because it's the "add more crypto" instinct and worth
recording why it doesn't apply to a bootstrap/first-contact problem.

### (d) TOFU + out-of-band new-device notification

Accept first-pairing risk, but push a notification to already-paired
devices whenever a new device joins, so a human can react/revoke after the
fact. Doesn't prevent the live MITM this finding is about (real-time
interception during the pairing itself, or a phantom first pairing with no
existing paired devices to notify), so it's not a substitute for (a). Worth
doing later as defense-in-depth once single-device revocation exists (the
separate High finding, tracked independently) — noted as a non-goal below,
not folded into this fix.

## Recommendation

**(a): mutual SAS/fingerprint confirmation**, gating the trust-establishing
action on both sides (Android: the `/devices/pair` POST + local
persistence; CLI: `sessions.AddDevice`) behind an explicit human
confirmation that the fingerprint shown on both screens matches. Applied
uniformly to QR and manual entry alike — no path-based branching.

## Detailed design

### Fingerprint function

New exported function, same algorithm on both sides, order-independent so
either peer can call it with its own two pubkeys in either argument order:

```go
// bridge/internal/e2e/cipher.go, next to DeriveSharedSecret
func PairingFingerprint(pubkeyA, pubkeyB []byte) string {
    lo, hi := pubkeyA, pubkeyB
    if bytes.Compare(lo, hi) > 0 {
        lo, hi = hi, lo
    }
    sum := sha256.Sum256(append(append([]byte{}, lo...), hi...))
    return fmt.Sprintf("%X-%X-%X", sum[0:2], sum[2:4], sum[4:6])
}
```

```kotlin
// android/.../data/e2e/Cipher.kt, next to deriveSharedSecret
fun pairingFingerprint(pubkeyA: ByteArray, pubkeyB: ByteArray): String {
    val (lo, hi) = if (lexicographicallyGreater(pubkeyA, pubkeyB)) pubkeyB to pubkeyA else pubkeyA to pubkeyB
    val digest = MessageDigest.getInstance("SHA-256").digest(lo + hi)
    return "%02X%02X-%02X%02X-%02X%02X".format(
        digest[0], digest[1], digest[2], digest[3], digest[4], digest[5],
    )
}
```

(`lexicographicallyGreater` is a small unsigned-byte-array comparison
helper — Kotlin's `ByteArray` has no built-in lexicographic compare;
implemented as a manual loop over `.toUByte()` values, mirroring Go's
`bytes.Compare` semantics exactly so both sides canonicalize identically.)

Output: 3 groups of 2 hex bytes (`AB12-CD34-EF56`), 48 bits. **Why 48, not
Bluetooth-SSP's 6-digit/~20-bit convention**: this is an *active,
real-time* forgery, not a passive/precomputed one — an attacker must find a
keypair producing a matching fingerprint against the *real* other party's
key, live, inside the pairing deadline (`pair-device --timeout`, default 5
minutes). At a generously optimistic 10M ECDH+hash attempts/sec, 5 minutes
bounds an attacker to roughly 2^31 attempts; 48 bits leaves a wide safety
margin above that. Not sized to Signal's 60-digit safety numbers (~199
bits) — that's calibrated for a different, higher-value threat model
(long-lived identity keys, nation-state adversaries with precomputation
budgets); this is a short-lived, freshly-generated-per-session bootstrap
secret, so proportionate sizing is appropriate, not maximal sizing.

### Android: split "resolve" from "commit"

`PairingUiState` gains a new state between `Pairing` and `Success`:

```kotlin
data class AwaitingConfirmation(val fingerprint: String, val qr: PairingQr) : PairingUiState
```

`PairingClient` splits `pairInternal` into two functions:

- `prepare(qr: PairingQr): String` — validates `qr.hasSafePairUrl()` (the
  one choke point both paths already share, `PairingClient.kt:122`),
  computes `pairingFingerprint(identity.publicKey, decode(qr.agentPubkey))`.
  **No network call.** Returns the fingerprint string.
- `commit(qr: PairingQr)` — exactly today's `pairInternal` body (POST
  `/devices/pair`, derive secret, `onSetPairing`/`onSetBaseUrl`/`onSetToken`)
  unchanged, just invoked from a new `onConfirmed` callback instead of
  immediately after resolve.

`PairingViewModel.onQrScanned`/`onManualEntrySubmitted` call `prepare` and
transition to `AwaitingConfirmation` instead of going straight to
`Pairing`→`Success`. New `onConfirmed()`/`onRejected()` methods: confirmed
calls `commit` (existing error handling unchanged); rejected returns to
`Scanning` with **no network call ever made** — since the POST hasn't
happened yet, a rejected fingerprint burns nothing server-side (unlike the
CLI side, see below — an intentional asymmetry, not an oversight, driven by
which side's protocol step is the actual point of commitment).

`PairingScreen` gets a new `is PairingUiState.AwaitingConfirmation` branch:
displays the fingerprint prominently, "Confirm" and "Cancel" buttons, with
copy telling the user to compare it against what the CLI printed.

### CLI: confirm before `AddDevice`

`pairDevice` (`pair.go:116`) gains an `in io.Reader` parameter (mirrors the
existing `out io.Writer` testability pattern). Between deriving `secret`
(`pair.go:162-165`) and calling `sessions.AddDevice` (`pair.go:166`):

```go
fingerprint := e2e.PairingFingerprint(identity.PublicKey().Bytes(), devicePub.Bytes())
fmt.Fprintf(out, "\nVerify this code matches the phone's confirmation screen: %s\n", fingerprint)
confirmed, err := confirmFingerprint(in, out)
if err != nil {
    return fmt.Errorf("read confirmation: %w", err)
}
if !confirmed {
    return fmt.Errorf("pairing aborted: fingerprint not confirmed for code %s", code)
}
```

`confirmFingerprint(in io.Reader, out io.Writer) (bool, error)` prompts
`"Confirm? [y/N]: "`, reads one line via `bufio.NewReader(in).ReadString('\n')`,
trims/lowercases, returns true only for `"y"`/`"yes"`. EOF or any other
input is treated as **not confirmed** (fail closed).

`runPairDevice` passes `os.Stdin`. The three existing tests in
`pair_test.go` (`TestPairDeviceStopsOnRedemption`,
`TestPairDeviceRetriesOnTransientError`) pass `strings.NewReader("y\n")`
for `in` — a deliberate, explicit "yes" in test fixtures, not a bypass flag
(see the rejected-alternative note below).

**No `--yes`/non-interactive bypass flag.** A repo-wide grep for
`pair-device` usage in `.md`/`.sh`/`.yml`/`.yaml` (76 matches, 13 files)
found zero scripted/CI invocations — every reference is documentation for
interactive terminal use. Adding a flag that skips the one check that
defeats the MITM, with no found automation need for it, would just hand
both attackers and careless copy-pasted run scripts an easy way to disable
the fix. If a real automation need for `pair-device` surfaces later, that's
a separate decision to make deliberately, not a default included here.

Because `RedeemPairingCode` has already run by the time the CLI's poll
reports `redeemed: true` (redemption is phone-initiated; the CLI only
learns about it asynchronously), a "no" answer here cannot un-consume the
pairing code — it can only stop the CLI from trusting the resulting key
material (skips `AddDevice`, so no session is persisted). The code is
already burned either way. This is a real, asymmetric gap relative to the
Android side's clean no-network-call-yet rejection path, and it overlaps
with the separately tracked High finding ("no practical single-device
token revocation") — noted below as a non-goal of this design, not solved
here.

### Wire format

**No changes.** Every value the fingerprint needs (own raw pubkey, other
party's raw pubkey as already resolved by the existing QR/manual-entry/poll
paths) is already present on both sides before this design's new
confirmation step runs. This is a pure local computation layered on top of
existing data, satisfying repo `CLAUDE.md` invariant 3 (wire-format
lockstep) trivially — there's no wire format to keep in lockstep here.

### `bridge/README.md` correction

`bridge/README.md:200-230`'s claim "Same single-use code, same handshake,
same e2e result — just without a scan" describing manual entry is
inaccurate even after this fix (it undersells the new confirmation step)
and was already inaccurate before it (it glossed over the fact that manual
entry, unlike QR, was relay-mediated). Gets corrected as part of
implementation, not scoped as its own task.

## Acceptance test definitions

1. **Fingerprints agree for a genuine pairing.** Go unit test:
   `PairingFingerprint(agentPub, devicePub) == PairingFingerprint(devicePub, agentPub)`
   for random key pairs (order-independence). Kotlin unit test: same
   property for `pairingFingerprint`. Cross-check (documented, not
   necessarily automated across languages): a fixed known key pair produces
   byte-identical output from both implementations — pasted as a table in
   the plan's task for this function, verified by hand once.

2. **Different keys produce different fingerprints.** Table test over
   several distinct key pairs on both sides — asserts no accidental
   collision/constant-output bug (not a cryptographic proof, a
   regression guard).

3. **Android: rejecting a fingerprint makes zero network calls.** Unit
   test around `PairingViewModel`/`PairingClient.prepare` +
   `onRejected()`: assert the (fake/mock) `OkHttpClient` never receives a
   request, and `onSetPairing`/`onSetBaseUrl`/`onSetToken` are never
   invoked.

4. **Android: confirming calls `commit` exactly once with the prepared
   `qr`.** Existing `PairingClientTest` coverage of `pairInternal`'s POST
   +derive+persist behavior is preserved unchanged (it becomes `commit`'s
   test, same assertions), plus a new test that `prepare` alone doesn't
   trigger it.

5. **Go: `pairDevice` calls `AddDevice` only when `confirmFingerprint`
   returns true.** Extend `pair_test.go`'s existing
   `TestPairDeviceStopsOnRedemption` fixture with a sibling test
   `TestPairDeviceAbortsOnRejectedFingerprint` passing
   `strings.NewReader("n\n")` for `in`: assert `pairDevice` returns a
   non-nil error and `sessions.SharedSecret(tokenHash)` reports not-found
   afterward (mirrors the existing test's positive-path assertion at
   `pair_test.go:82-84`, inverted).

6. **Go: EOF/garbage input is treated as rejection, not a crash or silent
   accept.** `TestPairDeviceConfirmFingerprintFailsClosed` passing
   `strings.NewReader("")` — asserts abort, not a panic or accidental
   "confirmed" default.

## Explicit non-goals

- **Not** solving single-device token revocation — separate High finding
  from the same review, tracked and sequenced independently per the user's
  chosen order ("Critical first... then the other 7").
- **Not** adding TOFU-style new-device push notifications (Direction (d)) —
  good future defense-in-depth, deliberately not folded into this fix.
- **Not** un-burning a pairing code when the CLI operator rejects a
  fingerprint — the code is already consumed by the time the CLI can react
  (protocol-shape limitation, explained above); revisit once revocation
  exists.
- **Not** automating or removing the human-judgment step — SAS-style
  confirmation is inherently "a human must actually compare the two
  strings"; this design cannot and does not attempt to make that
  foolproof, only to make the correct information available for a human to
  act on, matching the precedent this repo already cites (Signal/WhatsApp
  safety numbers, Bluetooth SSP numeric comparison).
- **Not** touching any of the other 6 remaining findings from the
  multi-agent review (rate limiting, `FLAG_SECURE`, WS message-size limits,
  unsanitized route IDs, exported `MainActivity` intent extras, relay
  server timeouts) — out of scope for this doc by the user's chosen
  sequencing.
