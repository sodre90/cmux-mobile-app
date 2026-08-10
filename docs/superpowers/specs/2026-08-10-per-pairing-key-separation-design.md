# Per-pairing key separation (cmux-app-1fx)

Status: proposed
Issue: cmux-app-1fx (P0, bug) — blocks cmux-app-a3g

## Problem

Every pairing between a given phone install and a given agent install derives
a **bit-identical** AEAD key, while each pairing resets its frame counters to
zero. Since the XChaCha20-Poly1305 nonce is a pure function of
`(direction, counter)`, this is direct nonce reuse under a repeated key.

Observed live on 2026-08-10 in `~/.config/cmux-bridge/sessions.db`:

```
secret_digest  rows  devices
57635755…      18    fb3fc38c,478d1e04,…,55063982,5a20227c,e70edadd,10f73853,ff36f2b8
77306747…      1     ba76e6c2
```

Eighteen device rows, one key. Row `55063982` reached `send_counter =
246413` under that key; every pairing since has replayed nonces `0, 1, 2, …`
against it.

## Root cause

Three independently reasonable decisions compose into the defect:

1. `bridge/internal/e2e/identity.go` — the agent's X25519 identity key is
   generated once and persisted at `~/.config/cmux-bridge/identity.key`.
2. `android/…/data/e2e/Identity.kt` — the phone's X25519 keypair is likewise
   "generated once on first use and persisted thereafter".
3. `DeriveSharedSecret` (`bridge/internal/e2e/cipher.go`, mirrored in
   `android/…/data/e2e/Cipher.kt`) is a pure function of those two keys:

   ```go
   secret := ECDH(myPriv, theirPub)
   info   := buildInfo(myPub, theirPub)   // "cmux-bridge e2e v1|" + sorted pubkeys
   return hkdf.Key(sha256.New, secret, nil, string(info), 32)   // salt = nil
   ```

   No salt, and `info` carries no per-pairing entropy.

Meanwhile `e2e.Store.AddDevice` deliberately zeroes `send_counter`,
`recv_highest`, `recv_highest_set` and `recv_window_bits` for the new row —
correct in isolation, catastrophic when the key is unchanged.

The pairing mints a fresh bearer token each time, and `device_id` is that
token's SHA-256. So the store *looks* like it is tracking distinct devices
while every row is keyed identically.

### Two distinct consequences

**Cryptographic (the severe one).** Keystream reuse: `C1 ⊕ C2 = P1 ⊕ P2` for
any two frames sharing a nonce, recovering plaintext relationships without
the key. Poly1305 one-time-key reuse additionally enables tag forgery. This
violates README/CLAUDE.md invariant 4.

**Availability (the visible one, cmux-app-a3g).** Because the key is shared,
the phone cannot tell which pairing produced a frame — a frame from *any* row
authenticates. One frame from a higher-counter row slides the phone's
per-slot replay window (width 64) past the live row's counter, after which
every live frame is rejected. `Frame.kt` throws `DecryptFailedException` on
the window check *before* touching the cipher, so this surfaces as
`decrypt_failed` and is indistinguishable from a genuine AEAD failure.

### Not limited to re-pairing

The phone's single keypair is shared across `ConnectionSlot.RELAY` and
`ConnectionSlot.DIRECT`. Dual-pairing one phone to one agent therefore
produces two rows with one key and two independently-restarting counters —
nonce reuse with no re-pairing at all. Any fix must cover this case.

## Requirements

1. Two pairings of the same phone install to the same agent install must
   derive different keys — including the relay/direct pair of one dual-pairing.
2. A counter reset must never reproduce a nonce already used under a live key.
3. Wire-format lockstep (CLAUDE.md invariant 3): any protocol field change
   lands on Kotlin, Go server, and the relay DTO copy in one commit.
4. No weakening of X25519+HKDF, AEAD coverage, or atomic replay-counter
   validate+commit (invariant 4).
5. cmux is untouched; `internal/relay/multitenant_test.go` keeps passing.

## Options considered

### Option A — fresh ephemeral device keypair per pairing (chosen)

The phone generates a new X25519 keypair at the start of each pairing, uses
it for the SAS fingerprint and the `device_pubkey` it submits, derives the
secret from it, and discards the private key once the secret is stored.

- ECDH output is unique per pairing, so the derived key is too.
- **No wire-format change.** `device_pubkey` already carries whatever key the
  phone chooses; the field, the DTOs and both schemas are untouched.
- **No Go change required** for the fix itself.
- Covers the dual-slot case for free: each slot pairs separately, so each
  gets its own keypair.
- Existing pairings keep working — their stored secret is unaffected — so no
  forced re-pair.

Verified precondition: nothing depends on the phone's key being stable.
`device_pubkey` is only stored and echoed through pairing (`auth/store.go`,
`e2e/store.go`, `wire/pairing.go`); it is never used to recognise a device
across pairings and never used for authentication, which is the bearer token
alone. On the phone, `identity.publicKey` is read only by
`PairingClient.prepare` (fingerprint) and `commit` (the pairing POST).

Cost: `prepare`/`commit` are deliberately split so the UI can show the
fingerprint confirmation between them, so the ephemeral keypair must be
created in `prepare` and carried to `commit`.

### Option B — HKDF salt = SHA-256(bearer token)

Both sides already hold this: the phone has the raw token in the pairing
response, the agent stores its hash as `device_id`. No wire change either.

Rejected: it makes the AEAD key a function of an authentication artifact, so
any later token rotation silently invalidates the e2e session, and the
coupling is non-obvious to a future reader. It also leaves the underlying
key-reuse property in place and merely separates domains on top of it.

### Option C — explicit random `pairing_salt` field in the pair response

Semantically clean, and the salt need not be persisted since neither side
re-derives. Rejected: it requires a wire change in three places for no
benefit over Option A, and in relay-mediated pairing the *relay* mints the
response — a malicious relay could replay a salt and deliberately reproduce
this exact bug. Option A puts the per-pairing entropy on the phone, which is
the party the relay cannot impersonate.

## Chosen design

Option A, plus a defence-in-depth guard on the agent.

**Phone.** `PairingClient` generates a per-pairing keypair rather than
reading the persistent `Identity`. `prepare` creates it and computes the
fingerprint from it; `commit` consumes the same one. `Identity`'s persistence
is what encodes the defect, so the class is removed rather than left as a
trap for the next reader; `AppContainer.identity` goes with it.

**Agent.** `e2e.Store.AddDevice` rejects a device whose `shared_secret`
already exists on a different `device_id`. Under Option A that condition is
unreachable, which is precisely why it is worth asserting: it converts any
future regression in key derivation from silent nonce reuse into a loud
pairing failure. This is a local invariant check, not a second mechanism.

Deliberately out of scope: retiring the stale rows already in both databases.
Those are live bearer tokens and belong with the existing token-revocation
work, not here.

## Security analysis

- Forward secrecy is unchanged — neither option claims it; a compromised
  agent identity key still exposes past sessions whose device key is known.
  Option A does mean a compromised *phone* keypair no longer compromises
  every past and future pairing, only the one it was generated for. That is a
  strict improvement.
- The SAS fingerprint (`2026-07-10-pairing-mitm-fingerprint-design.md`) keeps
  working unchanged: it authenticates the two public keys of *that* exchange,
  and an ephemeral device key is still the key being authenticated.
- Post-fix, a frame from a stale row no longer authenticates against the live
  session, so it is rejected by the AEAD rather than accepted into the replay
  window. That is what closes cmux-app-a3g.
- The existing poisoned state is not self-healing: rows already sharing a key
  keep sharing it. Recovery is one re-pair per slot, after which every new row
  is independently keyed.

## Test plan

Go (`bridge`):
- `AddDevice` returns an error when a second `device_id` presents an existing
  `shared_secret`; the existing row is left untouched.
- Existing `e2e` and `server` suites keep passing unchanged; several fixtures
  call `AddDevice` with a literal shared secret and will need distinct values
  per device.
- `internal/relay/multitenant_test.go` unchanged and passing.

Kotlin (`android`):
- Two successive `prepare`/`commit` cycles against the same agent public key
  yield different `device_pubkey` values and different derived secrets.
- `prepare` and the following `commit` use the *same* keypair — asserted via
  the fingerprint matching the key actually submitted.
- A counter-reset regression test: given two sessions derived from different
  pairings, a frame sealed under one must fail to open under the other,
  rather than being accepted and advancing the replay window.

Manual, on hardware: pair the direct slot, confirm `sessions.db` shows a
`shared_secret` distinct from every existing row, then pair the relay slot and
confirm it differs again.
