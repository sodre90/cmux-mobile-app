# Device Pairing + E2E Content Encryption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the operator-driven `cmux-relay pair` CLI with self-service QR-code device pairing, and add end-to-end content encryption between `cmux-bridge agent` and paired devices, so relay/proxy operators (and anyone who compromises the relay host) cannot read session/terminal/event content — only the (already mTLS-protected) transport metadata.

**Architecture:** A new `internal/e2e` package provides X25519 identity keys, HKDF-derived per-device shared secrets, and an XChaCha20-Poly1305 AEAD cipher with a direction-tagged nonce (see Global Constraints — this deviates from the literal spec text for a critical security reason). `internal/auth`'s SQLite store gains a `device_pubkey` column and a non-destructive (UPDATE-based) pairing-code redemption flow so a code can be polled for status after redemption. `cmux-relay` exposes two new unauthenticated-but-CN-gated endpoints (`POST /agent/pairing-code`, `GET /agent/pairing-code/{code}`) plus one fully public endpoint (`POST /devices/pair`) that a phone hits directly after scanning a QR code rendered by a new `cmux-bridge pair-device` CLI command. `cmux-bridge`'s HTTP/WebSocket handlers gain an opt-in encryption layer (`Server.SetSessions`) that only activates when `runAgent` wires it up — every existing test continues to exercise the unencrypted code path unchanged.

**Tech Stack:** Go 1.26 stdlib `crypto/ecdh` + `crypto/hkdf`; `golang.org/x/crypto v0.53.0` (`chacha20poly1305`); `github.com/mdp/qrterminal/v3 v3.2.1` for terminal QR rendering; `modernc.org/sqlite` (already a dependency) for the schema migration.

## Global Constraints

- **Full enforcement, no coexistence.** The old `cmux-relay pair` CLI is deleted entirely (Task 6, folded into the Issue signature change), not deprecated. `auth.Issue` always requires a device public key going forward — there is no manual-pairing fallback path.
- **Accepted lockout.** The user's currently-paired phone loses relay access the moment this ships. It cannot regain access until the separate, not-yet-started Android QR-scanning work also ships. This is a deliberate, already-approved consequence — do not add a compatibility shim to soften it.
- **Cipher construction is direction-tagged, NOT the spec's literal text.** The approved spec describes the nonce as `[16 zero bytes][8-byte counter]` with one shared key for both directions and each direction's counter independently starting at 0. **This is insecure**: it causes the agent's first message and the device's first message to both be sealed under nonce=0 with the same key (catastrophic AEAD nonce reuse). The binding construction for this plan is:
  ```go
  func Nonce(direction byte, counter uint64) []byte {
      n := make([]byte, chacha20poly1305.NonceSizeX) // 24 bytes
      n[15] = direction                                // byte 15, was always zero before
      binary.BigEndian.PutUint64(n[16:], counter)       // bytes 16-23
      return n
  }
  const (
      DirAgentToDevice byte = 0x00
      DirDeviceToAgent byte = 0x01
  )
  ```
  `DirAgentToDevice = 0x00` is bit-for-bit indistinguishable from the spec's original all-zero prefix, so it does not change wire format for that direction. `DirDeviceToAgent = 0x01` creates a fully disjoint nonce space for the other direction under the same key. Every task below already assumes this construction.
- **HKDF info string binds both public keys, sorted.** `buildInfo(pubA, pubB)` byte-compares the two 32-byte public keys and always orders the lexicographically smaller first, so both peers derive an identical `info` parameter regardless of which side computes it.
- **No in-memory caching in `e2e.Store`.** Every method re-reads the full JSON session-store file and writes back atomically (temp file + `os.Rename`) on every call — this lets the long-running `cmux-bridge agent` process and the separate `cmux-bridge pair-device` CLI invocation (different OS processes) see each other's writes with no reload/SIGHUP mechanism. A narrow last-write-wins race is accepted, consistent with this project's single-operator home-lab risk tolerance — do not add file locking.
- **Two-phase replay-counter validate/commit.** `ValidateRecvCounter` (read-only) must be called and the AEAD tag must verify successfully BEFORE `CommitRecvCounter` (which persists the advance) is called. Never collapse these into one atomic check-and-advance step — doing so lets one corrupted/forged message with an unused counter permanently desync the legitimate sequence.
- **Send/receive counters are durable and shared across HTTP and WS, NOT per-WS-connection.** The spec's literal text says the WS frame counter "reset[s] to 0 on reconnect, since it's scoped to that connection's lifetime, not global." Under a persistent shared secret (which this plan does persist to disk, unlike the spec's in-memory-only assumption), resetting to 0 on every reconnect would reuse `(direction, counter)` nonce pairs across different WS connections — the same class of AEAD nonce-reuse bug as the direction issue above, just triggered by reconnects instead of by the two directions. This plan's fix: `e2e.Store` (Task 3) persists one monotonic counter per direction per device, shared by both the HTTP body envelope (Task 4) and every WS connection's frame envelope (Task 5) — counters never reset. This is a second, related deviation from the literal spec text, on top of the direction-tag fix, and must be disclosed alongside it.
- **Encryption is opt-in via `Server.SetSessions`.** No existing test calls `SetSessions`. Every encryption-touching change in `internal/server` must leave all pre-existing tests passing completely unmodified — verify this explicitly after Tasks 11-13.
- **Exact dependency versions.** `golang.org/x/crypto@v0.53.0`, `github.com/mdp/qrterminal/v3@v3.2.1`. Add via `go get <module>@<version>` then `go mod tidy` — do not hand-edit `go.mod`.
- **Real module path is `github.com/sodre90/cmux-bridge`.** Every new file that imports another internal package must use this full path (e.g. `github.com/sodre90/cmux-bridge/internal/e2e`), confirmed by reading `bridge/go.mod` directly — do not use a bare `cmux-bridge/...` shorthand.
- **`ssl_verify_client` becomes `optional`, and this REQUIRES new Go-side verification — it is not a free relaxation.** `bridge/deploy/nginx-cmux-relay.conf`'s main vhost currently sets `ssl_verify_client on;` for `location /`, which covers **all** traffic including the agent tunnel. A phone that only ever receives a bearer token from `/devices/pair` (no client certificate) would be rejected by nginx before reaching the Go relay at all, so Task 8 relaxes this to `ssl_verify_client optional;`. **This is a real security gap if left there alone**: with `optional`, nginx forwards `$ssl_client_s_dn` (→ `X-Client-Cert-CN`) for *any* presented certificate, verified or not — a trivial self-signed cert with `CN=agent:<victim-tenant-id>` would let an attacker hijack that tenant's agent-tunnel registration or its new pairing-code endpoints. The fix (Task 8): nginx also forwards `X-Client-Cert-Verify: $ssl_client_verify`, and every place in Go that trusts an agent CN (`handleTunnel`, `notAgent`, the new `agentOnly` middleware) goes through a new `verifiedAgentTenant` helper that requires `X-Client-Cert-Verify == "SUCCESS"` before parsing the CN at all. `/devices/pair` and `/tenants/register` remain intentionally open to certless callers by design (bearer-token/pairing-code is their own proof), so they are not gated by this helper.
- **No separate bootstrap-vhost change needed for `/devices/pair`, unlike an earlier draft of this plan assumed.** Once the main vhost's `ssl_verify_client` is `optional`, a certless phone can already complete a TLS handshake against the *main* vhost and reach its `location /` catch-all, which already proxies to the relay's `Handler()` mux — and `POST /devices/pair` is mounted there, fully public, with no cert or bearer-token requirement of its own. So `bridge/deploy/nginx-cmux-relay-bootstrap.conf` (the separate no-mTLS vhost used only for `POST /tenants/register`) needs **no changes at all** for pairing. This replaces an earlier, unconfirmed assumption in a prior draft of this plan that `/devices/pair` would need its own `location` block added to the bootstrap vhost — that assumption is now moot, not merely resolved.
- **`POST /devices/pair`'s response and request shapes are simplified from the spec's literal sketch, and this is deliberate, not an oversight.** The spec sketches a `{token, tenant_id, agent_pubkey}` response and implies a `{tenant_id, code, device_pubkey}` request. This plan drops `agent_pubkey` from the response (the phone already has it from the QR code's own content, so the relay never needs to hold or forward it — keeping the relay fully blind to E2E key material end-to-end, in both directions) and drops `tenant_id` from the request entirely (pairing codes are a single global, unguessable 33^8-space namespace — see `randomCode` — so `code` alone is sufficient and authoritative; requiring a client-supplied `tenant_id` would add a field the server cannot safely validate without either trusting it blindly or wastefully consuming the one-time code before validation). The response keeps `tenant_id` (informational, so the app knows which workspace it just joined).
- **`auth.Store.PairingCodeStatus` returns 4 values, not the spec's unsketched 2-3.** `func (s *Store) PairingCodeStatus(tenantID, code string) (devicePubkey, tokenHash string, redeemed, ok bool)`. The `tokenHash` field is new: `cmux-bridge pair-device`'s polling loop needs to know the exact key (the SHA-256 hash of the phone's freshly issued bearer token) to store the derived shared secret under in `e2e.Store.AddDevice`, since that identical value is what the relay injects as `X-Device-ID` on every future proxied request from that device (Task 9). `RedeemPairingCode` (Task 6) writes the device row and back-fills `token_hash`/`device_pubkey`/`redeemed_at` onto the same `pairing_codes` row inside one database transaction, not as a separate best-effort step — a database fault partway through rolls back the whole redemption (no token is minted) rather than risking a device that's paired but whose token hash never reached `pairing_codes`, which would silently strand the agent's poll loop with no way to discover it.
- **Task numbering in this plan is 1-16, not the 1-17 an earlier draft used.** An earlier draft would have left `cmd/cmux-relay`'s old `pair` CLI calling a stale 2-arg `Issue` after the Task that changes `Issue` to 3-arg, breaking the build until a later task deleted it. This plan folds that deletion into the same task as the signature change (Task 6) so every task leaves `go build ./...`/`go test ./...` green.
- **Do not touch `internal/server/server.go`'s `Handler()`/`authWrap`.** It is confirmed dead code in production (only `TrustedHandler()` is used by `runAgent`) but is the primary harness for existing tests (`newTestServer` in `sessions_test.go`). This is a pre-existing code smell, out of scope for this plan — do not fix it, just don't break it.
- **Commit authorship:** every commit must be authored solely by the human developer. Never add `Co-Authored-By: Claude` or any AI attribution trailer.

---

### Task 1: `internal/e2e` identity keys

**Files:**
- Create: `bridge/internal/e2e/identity.go`
- Test: `bridge/internal/e2e/identity_test.go`

**Interfaces:**
- Produces: `type Identity struct { Priv *ecdh.PrivateKey }`, `func LoadOrCreateIdentity(path string) (*Identity, error)`, `func (id *Identity) PublicKey() *ecdh.PublicKey`

- [ ] **Step 1: Write the failing tests**

```go
// bridge/internal/e2e/identity_test.go
package e2e

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadOrCreateIdentityPersistsAcrossCalls(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")

	first, err := LoadOrCreateIdentity(path)
	if err != nil {
		t.Fatalf("first LoadOrCreateIdentity: %v", err)
	}
	second, err := LoadOrCreateIdentity(path)
	if err != nil {
		t.Fatalf("second LoadOrCreateIdentity: %v", err)
	}
	if !bytes.Equal(first.PublicKey().Bytes(), second.PublicKey().Bytes()) {
		t.Fatal("expected same public key across LoadOrCreateIdentity calls")
	}
}

func TestLoadOrCreateIdentityFilePermissions(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")

	if _, err := LoadOrCreateIdentity(path); err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("expected 0600 permissions, got %o", perm)
	}
}

func TestLoadOrCreateIdentityRejectsCorruptFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key")
	if err := os.WriteFile(path, []byte("not a valid key"), 0o600); err != nil {
		t.Fatalf("write corrupt file: %v", err)
	}
	if _, err := LoadOrCreateIdentity(path); err == nil {
		t.Fatal("expected error loading corrupt identity file, got nil")
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/e2e/... -run TestLoadOrCreateIdentity -v`
Expected: FAIL — package `e2e` / function `LoadOrCreateIdentity` does not exist.

- [ ] **Step 3: Write the implementation**

```go
// bridge/internal/e2e/identity.go
package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
)

type Identity struct {
	Priv *ecdh.PrivateKey
}

func LoadOrCreateIdentity(path string) (*Identity, error) {
	raw, err := os.ReadFile(path)
	if err == nil {
		priv, err := ecdh.X25519().NewPrivateKey(raw)
		if err != nil {
			return nil, fmt.Errorf("parse identity key %s: %w", path, err)
		}
		return &Identity{Priv: priv}, nil
	}
	if !os.IsNotExist(err) {
		return nil, fmt.Errorf("read identity key %s: %w", path, err)
	}

	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate identity key: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for identity key: %w", err)
	}
	if err := os.WriteFile(path, priv.Bytes(), 0o600); err != nil {
		return nil, fmt.Errorf("write identity key %s: %w", path, err)
	}
	return &Identity{Priv: priv}, nil
}

func (id *Identity) PublicKey() *ecdh.PublicKey {
	return id.Priv.PublicKey()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -run TestLoadOrCreateIdentity -v`
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/e2e/identity.go bridge/internal/e2e/identity_test.go
git commit -m "e2e: add X25519 identity key persistence"
```

---

### Task 2: `internal/e2e` cipher primitives

**Files:**
- Create: `bridge/internal/e2e/cipher.go`
- Test: `bridge/internal/e2e/cipher_test.go`
- Modify: `bridge/go.mod`, `bridge/go.sum` (via `go get`/`go mod tidy`)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `const DirAgentToDevice byte = 0x00`, `const DirDeviceToAgent byte = 0x01`, `func Nonce(direction byte, counter uint64) []byte`, `func DeriveSharedSecret(myPriv *ecdh.PrivateKey, theirPub *ecdh.PublicKey) ([]byte, error)`, `func Seal(key, nonce, plaintext []byte) ([]byte, error)`, `func Open(key, nonce, ciphertext []byte) ([]byte, error)`.

- [ ] **Step 1: Add the dependency**

Run: `cd bridge && go get golang.org/x/crypto@v0.53.0 && go mod tidy`
Expected: `go.mod`/`go.sum` updated with `golang.org/x/crypto v0.53.0` as a direct dependency.

- [ ] **Step 2: Write the failing tests**

```go
// bridge/internal/e2e/cipher_test.go
package e2e

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/hex"
	"testing"
)

func TestFixedCipherVector(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	plaintext := []byte("cmux-bridge e2e test vector")
	nonce := Nonce(DirAgentToDevice, 42)

	ct, err := Seal(key, nonce, plaintext)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	want := "3adf930c2c38c2dc6de9e1fab5be816f607fea9f2d9e503a7f22277d65a588c593c28255c0dc93cac7a52a"
	if got := hex.EncodeToString(ct); got != want {
		t.Fatalf("ciphertext mismatch:\n got: %s\nwant: %s", got, want)
	}

	pt, err := Open(key, nonce, ct)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if string(pt) != string(plaintext) {
		t.Fatalf("round-trip mismatch: got %q want %q", pt, plaintext)
	}
}

func TestDeriveSharedSecretFixedVector(t *testing.T) {
	agentRaw := make([]byte, 32)
	deviceRaw := make([]byte, 32)
	for i := range agentRaw {
		agentRaw[i] = 0x01
		deviceRaw[i] = 0x02
	}
	agentPriv, err := ecdh.X25519().NewPrivateKey(agentRaw)
	if err != nil {
		t.Fatalf("agent NewPrivateKey: %v", err)
	}
	devicePriv, err := ecdh.X25519().NewPrivateKey(deviceRaw)
	if err != nil {
		t.Fatalf("device NewPrivateKey: %v", err)
	}

	wantAgentPub := "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209"[:64]
	wantDevicePub := "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"[:64]
	if got := hex.EncodeToString(agentPriv.PublicKey().Bytes()); got != wantAgentPub {
		t.Fatalf("agent pubkey mismatch:\n got: %s\nwant: %s", got, wantAgentPub)
	}
	if got := hex.EncodeToString(devicePriv.PublicKey().Bytes()); got != wantDevicePub {
		t.Fatalf("device pubkey mismatch:\n got: %s\nwant: %s", got, wantDevicePub)
	}

	agentSide, err := DeriveSharedSecret(agentPriv, devicePriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret (agent side): %v", err)
	}
	deviceSide, err := DeriveSharedSecret(devicePriv, agentPriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret (device side): %v", err)
	}
	wantSecret := "0c657b7b4a6f6eede1d9f03bad4f9c898e9291c22eeb4cd09f12df79394837d6"[:64]
	if got := hex.EncodeToString(agentSide); got != wantSecret {
		t.Fatalf("agent-side shared secret mismatch:\n got: %s\nwant: %s", got, wantSecret)
	}
	if got := hex.EncodeToString(deviceSide); got != wantSecret {
		t.Fatalf("device-side shared secret mismatch:\n got: %s\nwant: %s", got, wantSecret)
	}

	ct, err := Seal(agentSide, Nonce(DirAgentToDevice, 0), []byte("hello from agent"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	wantCT := "WI1V8JTQV+ypKcvWgSHUxv/C4quvVNDn/NUBnveC+zA="
	if got := base64.StdEncoding.EncodeToString(ct); got != wantCT {
		t.Fatalf("ciphertext mismatch:\n got: %s\nwant: %s", got, wantCT)
	}

	pt, err := Open(deviceSide, Nonce(DirAgentToDevice, 0), ct)
	if err != nil {
		t.Fatalf("Open (device side): %v", err)
	}
	if string(pt) != "hello from agent" {
		t.Fatalf("round-trip mismatch: got %q", pt)
	}
}

func TestOpenRejectsWrongKey(t *testing.T) {
	key1 := make([]byte, 32)
	key2 := make([]byte, 32)
	key2[0] = 0xff
	nonce := Nonce(DirAgentToDevice, 0)
	ct, err := Seal(key1, nonce, []byte("secret"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if _, err := Open(key2, nonce, ct); err == nil {
		t.Fatal("expected Open with wrong key to fail")
	}
}

func TestOpenRejectsWrongDirection(t *testing.T) {
	key := make([]byte, 32)
	ct, err := Seal(key, Nonce(DirAgentToDevice, 0), []byte("secret"))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if _, err := Open(key, Nonce(DirDeviceToAgent, 0), ct); err == nil {
		t.Fatal("expected Open with mismatched direction tag to fail, proving disjoint nonce spaces")
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/e2e/... -run 'TestFixedCipherVector|TestDeriveSharedSecretFixedVector|TestOpenRejects' -v`
Expected: FAIL — symbols undefined.

- [ ] **Step 4: Write the implementation**

```go
// bridge/internal/e2e/cipher.go
package e2e

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"
	"errors"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	DirAgentToDevice byte = 0x00
	DirDeviceToAgent byte = 0x01
)

func buildInfo(pubA, pubB []byte) []byte {
	a, b := pubA, pubB
	if bytes.Compare(a, b) > 0 {
		a, b = b, a
	}
	info := []byte("cmux-bridge e2e v1|")
	info = append(info, a...)
	info = append(info, '|')
	info = append(info, b...)
	return info
}

func DeriveSharedSecret(myPriv *ecdh.PrivateKey, theirPub *ecdh.PublicKey) ([]byte, error) {
	secret, err := myPriv.ECDH(theirPub)
	if err != nil {
		return nil, err
	}
	info := buildInfo(myPriv.PublicKey().Bytes(), theirPub.Bytes())
	return hkdf.Key(sha256.New, secret, nil, string(info), 32)
}

func Nonce(direction byte, counter uint64) []byte {
	n := make([]byte, chacha20poly1305.NonceSizeX)
	n[15] = direction
	binary.BigEndian.PutUint64(n[16:], counter)
	return n
}

func Seal(key, nonce, plaintext []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nil, nonce, plaintext, nil), nil
}

func Open(key, nonce, ciphertext []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	pt, err := aead.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, errors.New("decrypt_failed")
	}
	return pt, nil
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS (all tests in package, including Task 1's)

- [ ] **Step 6: Commit**

```bash
git add bridge/internal/e2e/cipher.go bridge/internal/e2e/cipher_test.go bridge/go.mod bridge/go.sum
git commit -m "e2e: add X25519+HKDF+XChaCha20-Poly1305 cipher with direction-tagged nonces"
```

---

### Task 3: `internal/e2e` session store

**Files:**
- Create: `bridge/internal/e2e/store.go`
- Test: `bridge/internal/e2e/store_test.go`

**Interfaces:**
- Consumes: `ecdh.PublicKey` (stdlib, same as Task 1/2).
- Produces: `func OpenStore(path string) *Store`, `func (s *Store) AddDevice(deviceID string, devicePub *ecdh.PublicKey, sharedSecret []byte) error`, `func (s *Store) SharedSecret(deviceID string) (secret []byte, ok bool)`, `func (s *Store) NextSendCounter(deviceID string) (uint64, error)`, `func (s *Store) ValidateRecvCounter(deviceID string, n uint64) (bool, error)`, `func (s *Store) CommitRecvCounter(deviceID string, n uint64) error`.

- [ ] **Step 1: Write the failing tests**

```go
// bridge/internal/e2e/store_test.go
package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"path/filepath"
	"testing"
)

func testPubKey(t *testing.T) *ecdh.PublicKey {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return priv.PublicKey()
}

func TestAddDeviceAndSharedSecret(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	pub := testPubKey(t)
	secret := []byte("0123456789abcdef0123456789abcdef")

	if err := s.AddDevice("dev1", pub, secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	got, ok := s.SharedSecret("dev1")
	if !ok {
		t.Fatal("expected SharedSecret to find dev1")
	}
	if string(got) != string(secret) {
		t.Fatalf("shared secret mismatch: got %q want %q", got, secret)
	}
}

func TestSharedSecretUnknownDevice(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if _, ok := s.SharedSecret("nope"); ok {
		t.Fatal("expected SharedSecret to fail for unknown device")
	}
}

func TestNextSendCounterIncrementsAndPersistsAcrossInstances(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	s1 := OpenStore(path)
	if err := s1.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	for i, want := range []uint64{0, 1, 2} {
		got, err := s1.NextSendCounter("dev1")
		if err != nil {
			t.Fatalf("NextSendCounter[%d]: %v", i, err)
		}
		if got != want {
			t.Fatalf("NextSendCounter[%d] = %d, want %d", i, got, want)
		}
	}

	s2 := OpenStore(path)
	got, err := s2.NextSendCounter("dev1")
	if err != nil {
		t.Fatalf("NextSendCounter on fresh Store instance: %v", err)
	}
	if got != 3 {
		t.Fatalf("expected counter to persist across Store instances, got %d want 3", got)
	}
}

func TestValidateAndCommitRecvCounter(t *testing.T) {
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	if err := s.AddDevice("dev1", testPubKey(t), []byte("secret")); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	valid, err := s.ValidateRecvCounter("dev1", 0)
	if err != nil || !valid {
		t.Fatalf("expected counter 0 valid on fresh device, got valid=%v err=%v", valid, err)
	}
	if err := s.CommitRecvCounter("dev1", 0); err != nil {
		t.Fatalf("CommitRecvCounter(0): %v", err)
	}

	valid, err = s.ValidateRecvCounter("dev1", 0)
	if err != nil {
		t.Fatalf("ValidateRecvCounter replay check: %v", err)
	}
	if valid {
		t.Fatal("expected counter 0 to be rejected as replay after commit")
	}

	valid, err = s.ValidateRecvCounter("dev1", 1)
	if err != nil || !valid {
		t.Fatalf("expected counter 1 valid after committing 0, got valid=%v err=%v", valid, err)
	}
}

func TestCrossProcessVisibilityNoInMemoryCache(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions.json")
	writer := OpenStore(path)
	reader := OpenStore(path)
	pub := testPubKey(t)

	if err := writer.AddDevice("dev1", pub, []byte("secret")); err != nil {
		t.Fatalf("AddDevice via writer: %v", err)
	}
	if _, ok := reader.SharedSecret("dev1"); !ok {
		t.Fatal("expected a second independent *Store instance on the same file to see the write immediately, with no reload call")
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/e2e/... -run 'TestAddDevice|TestSharedSecret|TestNextSendCounter|TestValidateAndCommit|TestCrossProcess' -v`
Expected: FAIL — symbols undefined.

- [ ] **Step 3: Write the implementation**

```go
// bridge/internal/e2e/store.go
package e2e

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

type deviceSession struct {
	DevicePubKey   string `json:"device_pubkey"`
	SharedSecret   string `json:"shared_secret"`
	SendCounter    uint64 `json:"send_counter"`
	RecvCounter    uint64 `json:"recv_counter"`
	RecvCounterSet bool   `json:"recv_counter_set"`
}

type fileFormat struct {
	Devices map[string]deviceSession `json:"devices"`
}

type Store struct {
	path string
	mu   sync.Mutex
}

func OpenStore(path string) *Store {
	return &Store{path: path}
}

func (s *Store) load() (fileFormat, error) {
	f := fileFormat{Devices: map[string]deviceSession{}}
	raw, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return f, nil
		}
		return f, fmt.Errorf("read session store %s: %w", s.path, err)
	}
	if len(raw) == 0 {
		return f, nil
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		return f, fmt.Errorf("parse session store %s: %w", s.path, err)
	}
	if f.Devices == nil {
		f.Devices = map[string]deviceSession{}
	}
	return f, nil
}

func (s *Store) save(f fileFormat) error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create dir for session store: %w", err)
	}
	raw, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal session store: %w", err)
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return fmt.Errorf("write session store: %w", err)
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) AddDevice(deviceID string, devicePub *ecdh.PublicKey, sharedSecret []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	f.Devices[deviceID] = deviceSession{
		DevicePubKey: base64.StdEncoding.EncodeToString(devicePub.Bytes()),
		SharedSecret: base64.StdEncoding.EncodeToString(sharedSecret),
	}
	return s.save(f)
}

func (s *Store) SharedSecret(deviceID string) (secret []byte, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return nil, false
	}
	d, found := f.Devices[deviceID]
	if !found {
		return nil, false
	}
	secret, err = base64.StdEncoding.DecodeString(d.SharedSecret)
	if err != nil {
		return nil, false
	}
	return secret, true
}

func (s *Store) NextSendCounter(deviceID string) (uint64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return 0, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return 0, fmt.Errorf("unknown device %q", deviceID)
	}
	n := d.SendCounter
	d.SendCounter++
	f.Devices[deviceID] = d
	if err := s.save(f); err != nil {
		return 0, err
	}
	return n, nil
}

func (s *Store) ValidateRecvCounter(deviceID string, n uint64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return false, err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return false, fmt.Errorf("unknown device %q", deviceID)
	}
	if d.RecvCounterSet && n <= d.RecvCounter {
		return false, nil
	}
	return true, nil
}

func (s *Store) CommitRecvCounter(deviceID string, n uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, err := s.load()
	if err != nil {
		return err
	}
	d, ok := f.Devices[deviceID]
	if !ok {
		return fmt.Errorf("unknown device %q", deviceID)
	}
	if d.RecvCounterSet && n <= d.RecvCounter {
		return nil
	}
	d.RecvCounter = n
	d.RecvCounterSet = true
	f.Devices[deviceID] = d
	return s.save(f)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS (all tests in package)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/e2e/store.go bridge/internal/e2e/store_test.go
git commit -m "e2e: add cross-process-safe JSON session store with two-phase replay counters"
```

---

### Task 4: `internal/e2e` HTTP body envelope

**Files:**
- Create: `bridge/internal/e2e/envelope.go`
- Test: `bridge/internal/e2e/envelope_test.go`

**Interfaces:**
- Consumes: `Store.SharedSecret`, `Store.NextSendCounter`, `Store.ValidateRecvCounter`, `Store.CommitRecvCounter` (Task 3); `Nonce`, `Seal`, `Open`, `DirAgentToDevice`, `DirDeviceToAgent` (Task 2).
- Produces: `func (s *Store) EncryptBody(deviceID string, plaintext []byte) ([]byte, error)`, `func (s *Store) DecryptBody(deviceID string, envelope []byte) ([]byte, error)`.

- [ ] **Step 1: Write the failing tests**

```go
// bridge/internal/e2e/envelope_test.go
package e2e

import (
	"encoding/base64"
	"encoding/json"
	"path/filepath"
	"testing"
)

func setupPairedStore(t *testing.T) (*Store, string) {
	t.Helper()
	dir := t.TempDir()
	s := OpenStore(filepath.Join(dir, "sessions.json"))
	pub := testPubKey(t)
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}
	if err := s.AddDevice("dev1", pub, secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	return s, "dev1"
}

func TestEncryptBodyDeviceCanDecrypt(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	envelope, err := s.EncryptBody(deviceID, []byte(`{"hello":"world"}`))
	if err != nil {
		t.Fatalf("EncryptBody: %v", err)
	}

	var env bodyEnvelope
	if err := json.Unmarshal(envelope, &env); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	ct, err := base64.StdEncoding.DecodeString(env.CT)
	if err != nil {
		t.Fatalf("decode ct: %v", err)
	}
	pt, err := Open(secret, Nonce(DirAgentToDevice, env.N), ct)
	if err != nil {
		t.Fatalf("device-side Open: %v", err)
	}
	if string(pt) != `{"hello":"world"}` {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecryptBodyAcceptsDeviceMessage(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	pt, err := s.DecryptBody(deviceID, envelope)
	if err != nil {
		t.Fatalf("DecryptBody: %v", err)
	}
	if string(pt) != `{"reply":"ok"}` {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecryptBodyRejectsReplay(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	if _, err := s.DecryptBody(deviceID, envelope); err != nil {
		t.Fatalf("first DecryptBody: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, envelope); err == nil {
		t.Fatal("expected second DecryptBody with same counter to be rejected as replay")
	}
}

func TestDecryptBodyRejectsCorruptedCiphertextWithoutDesyncingCounter(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	ct, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	ct[0] ^= 0xff // corrupt
	corrupted, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal corrupted envelope: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, corrupted); err == nil {
		t.Fatal("expected DecryptBody to reject corrupted ciphertext")
	}

	// The real message at counter 0 must still be acceptable — a corrupted
	// message must never advance the watermark.
	realCT, err := Seal(secret, Nonce(DirDeviceToAgent, 0), []byte(`{"reply":"ok"}`))
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	real, err := json.Marshal(bodyEnvelope{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(realCT)})
	if err != nil {
		t.Fatalf("marshal real envelope: %v", err)
	}
	if _, err := s.DecryptBody(deviceID, real); err != nil {
		t.Fatalf("expected the real message at counter 0 to still succeed after a corrupted attempt, got: %v", err)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/e2e/... -run 'TestEncryptBody|TestDecryptBody' -v`
Expected: FAIL — symbols undefined.

- [ ] **Step 3: Write the implementation**

```go
// bridge/internal/e2e/envelope.go
package e2e

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
)

type bodyEnvelope struct {
	V  int    `json:"v"`
	N  uint64 `json:"n"`
	CT string `json:"ct"`
}

func (s *Store) EncryptBody(deviceID string, plaintext []byte) ([]byte, error) {
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	n, err := s.NextSendCounter(deviceID)
	if err != nil {
		return nil, err
	}
	ct, err := Seal(secret, Nonce(DirAgentToDevice, n), plaintext)
	if err != nil {
		return nil, err
	}
	return json.Marshal(bodyEnvelope{V: 1, N: n, CT: base64.StdEncoding.EncodeToString(ct)})
}

func (s *Store) DecryptBody(deviceID string, envelope []byte) ([]byte, error) {
	var env bodyEnvelope
	if err := json.Unmarshal(envelope, &env); err != nil || env.V != 1 {
		return nil, fmt.Errorf("decrypt_failed")
	}
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	valid, err := s.ValidateRecvCounter(deviceID, env.N)
	if err != nil {
		return nil, err
	}
	if !valid {
		return nil, fmt.Errorf("decrypt_failed")
	}
	ct, err := base64.StdEncoding.DecodeString(env.CT)
	if err != nil {
		return nil, fmt.Errorf("decrypt_failed")
	}
	pt, err := Open(secret, Nonce(DirDeviceToAgent, env.N), ct)
	if err != nil {
		return nil, fmt.Errorf("decrypt_failed")
	}
	if err := s.CommitRecvCounter(deviceID, env.N); err != nil {
		return nil, err
	}
	return pt, nil
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS (all tests in package)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/e2e/envelope.go bridge/internal/e2e/envelope_test.go
git commit -m "e2e: add JSON body encrypt/decrypt envelope for HTTP responses/requests"
```

---

### Task 5: `internal/e2e` WebSocket frame envelope

**Files:**
- Create: `bridge/internal/e2e/frame.go`
- Test: `bridge/internal/e2e/frame_test.go`

**Interfaces:**
- Consumes: `Nonce`, `Seal`, `Open`, `DirAgentToDevice`, `DirDeviceToAgent` (Task 2); `Store.SharedSecret`, `Store.NextSendCounter`, `Store.ValidateRecvCounter`, `Store.CommitRecvCounter` (Task 3).
- Produces: `func EncodeFrame(key []byte, direction byte, counter uint64, plaintext []byte) ([]byte, error)`, `func DecodeFrame(key []byte, direction byte, frame []byte) (counter uint64, plaintext []byte, err error)`, `func (s *Store) EncryptFrame(deviceID string, plaintext []byte) ([]byte, error)`, `func (s *Store) DecryptFrame(deviceID string, frame []byte) ([]byte, error)`.

- [ ] **Step 1: Write the failing tests**

```go
// bridge/internal/e2e/frame_test.go
package e2e

import "testing"

func TestEncodeDecodeFrameRoundTrip(t *testing.T) {
	key := make([]byte, 32)
	frame, err := EncodeFrame(key, DirAgentToDevice, 7, []byte("payload"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	counter, pt, err := DecodeFrame(key, DirAgentToDevice, frame)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if counter != 7 {
		t.Fatalf("counter mismatch: got %d want 7", counter)
	}
	if string(pt) != "payload" {
		t.Fatalf("plaintext mismatch: got %q", pt)
	}
}

func TestDecodeFrameRejectsWrongDirection(t *testing.T) {
	key := make([]byte, 32)
	frame, err := EncodeFrame(key, DirAgentToDevice, 0, []byte("payload"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if _, _, err := DecodeFrame(key, DirDeviceToAgent, frame); err == nil {
		t.Fatal("expected DecodeFrame with mismatched direction to fail")
	}
}

func TestStoreEncryptDecryptFrameRoundTrip(t *testing.T) {
	s, deviceID := setupPairedStore(t)

	frame, err := s.EncryptFrame(deviceID, []byte("term output"))
	if err != nil {
		t.Fatalf("EncryptFrame: %v", err)
	}
	secret, _ := s.SharedSecret(deviceID)
	counter, pt, err := DecodeFrame(secret, DirAgentToDevice, frame)
	if err != nil {
		t.Fatalf("device-side DecodeFrame: %v", err)
	}
	if counter != 0 || string(pt) != "term output" {
		t.Fatalf("unexpected decode: counter=%d pt=%q", counter, pt)
	}
}

func TestStoreDecryptFrameRejectsReplay(t *testing.T) {
	s, deviceID := setupPairedStore(t)
	secret, _ := s.SharedSecret(deviceID)

	frame, err := EncodeFrame(secret, DirDeviceToAgent, 0, []byte("input"))
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if _, err := s.DecryptFrame(deviceID, frame); err != nil {
		t.Fatalf("first DecryptFrame: %v", err)
	}
	if _, err := s.DecryptFrame(deviceID, frame); err == nil {
		t.Fatal("expected second DecryptFrame with same counter to be rejected as replay")
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/e2e/... -run 'TestEncodeDecodeFrame|TestDecodeFrameRejects|TestStoreEncryptDecryptFrame|TestStoreDecryptFrameRejects' -v`
Expected: FAIL — symbols undefined.

- [ ] **Step 3: Write the implementation**

```go
// bridge/internal/e2e/frame.go
package e2e

import (
	"encoding/binary"
	"errors"
	"fmt"
)

func EncodeFrame(key []byte, direction byte, counter uint64, plaintext []byte) ([]byte, error) {
	ct, err := Seal(key, Nonce(direction, counter), plaintext)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 8+len(ct))
	binary.BigEndian.PutUint64(out[:8], counter)
	copy(out[8:], ct)
	return out, nil
}

func DecodeFrame(key []byte, direction byte, frame []byte) (counter uint64, plaintext []byte, err error) {
	if len(frame) < 8 {
		return 0, nil, errors.New("decrypt_failed")
	}
	counter = binary.BigEndian.Uint64(frame[:8])
	pt, err := Open(key, Nonce(direction, counter), frame[8:])
	if err != nil {
		return 0, nil, err
	}
	return counter, pt, nil
}

func (s *Store) EncryptFrame(deviceID string, plaintext []byte) ([]byte, error) {
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	n, err := s.NextSendCounter(deviceID)
	if err != nil {
		return nil, err
	}
	return EncodeFrame(secret, DirAgentToDevice, n, plaintext)
}

func (s *Store) DecryptFrame(deviceID string, frame []byte) ([]byte, error) {
	if len(frame) < 8 {
		return nil, fmt.Errorf("decrypt_failed")
	}
	n := binary.BigEndian.Uint64(frame[:8])
	secret, ok := s.SharedSecret(deviceID)
	if !ok {
		return nil, fmt.Errorf("no shared secret for device %q", deviceID)
	}
	valid, err := s.ValidateRecvCounter(deviceID, n)
	if err != nil {
		return nil, err
	}
	if !valid {
		return nil, fmt.Errorf("decrypt_failed")
	}
	pt, err := Open(secret, Nonce(DirDeviceToAgent, n), frame[8:])
	if err != nil {
		return nil, fmt.Errorf("decrypt_failed")
	}
	if err := s.CommitRecvCounter(deviceID, n); err != nil {
		return nil, err
	}
	return pt, nil
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/e2e/... -v`
Expected: PASS (entire `e2e` package, all 5 tasks' tests)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/e2e/frame.go bridge/internal/e2e/frame_test.go
git commit -m "e2e: add WebSocket binary frame encrypt/decrypt envelope"
```

---
### Task 6: `internal/auth` schema migration + device-pubkey pairing

**Files:**
- Modify: `bridge/internal/auth/store.go`
- Modify: `bridge/internal/auth/store_test.go`
- Modify: `bridge/internal/auth/middleware_test.go`
- Modify: `bridge/internal/server/sessions_test.go`
- Modify: `bridge/internal/cli/cli_test.go`
- Modify: `bridge/internal/relay/relay_test.go`
- Modify: `bridge/internal/relay/multitenant_test.go`
- Modify: `bridge/internal/relay/proxy_test.go`
- Modify: `bridge/internal/relay/pushmon_test.go`
- Modify: `bridge/cmd/cmux-relay/commands.go`
- Modify: `bridge/cmd/cmux-relay/main.go`

**Interfaces:**
- Consumes: nothing from Tasks 1-5.
- Produces: `func (s *Store) Issue(tenantID, name, devicePubkey string) (string, error)` (signature change — now 3 args, returns an error if `devicePubkey == ""`), `func (s *Store) RedeemPairingCode(code, name, devicePubkey string) (token, tenantID string, ok bool)` (signature change — now 3 args; non-destructive, UPDATE-based, so the code row survives redemption for polling), `func (s *Store) PairingCodeStatus(tenantID, code string) (devicePubkey, tokenHash string, redeemed, ok bool)` (new), `Device` struct gains `DevicePubkey string` and `TokenHash string` fields (the latter is the full SHA-256 hex digest — `HashSuffix` remains the last-6-chars operator-facing field, unchanged).

This task changes `Issue`'s signature, which breaks every existing call site across the whole repo (confirmed exhaustively via `grep -rn '\.Issue(' bridge` — 19 sites total, including this file's own internal call inside the old `RedeemPairingCode`, which this task removes anyway). All 17 test call sites are fixed in this task. The one production call site outside a test, in `cmd/cmux-relay/commands.go`'s `runPair`, can never be given a real device pubkey (the whole point of this plan is that pubkeys come from a device's own e2e keypair, which the operator-driven CLI has no way to obtain) — so this task deletes `runPair` outright, rather than leaving it calling a stale signature or a doomed-to-fail placeholder. This keeps the plan at 16 tasks total instead of carrying a separate later "remove the old CLI" task: every task here leaves `go build ./...`/`go test ./...` green with no transitional broken state in between.

- [ ] **Step 1: Update store_test.go and middleware_test.go with the new/changed call sites and new tests (RED)**

Replace `bridge/internal/auth/store_test.go` in full with:

```go
// bridge/internal/auth/store_test.go
package auth

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"
)

const testPubkey = "test-device-pubkey-b64"

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "d.db"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func newTenant(t *testing.T, s *Store) string {
	t.Helper()
	id, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestCreateTenantActiveRevoke(t *testing.T) {
	s := newStore(t)
	id := newTenant(t, s)
	if !s.TenantActive(id) {
		t.Fatal("freshly created tenant should be active")
	}
	if s.TenantActive("nonexistent") {
		t.Fatal("unknown tenant id must not be active")
	}
	if !s.RevokeTenant(id) {
		t.Fatal("revoke should report success")
	}
	if s.TenantActive(id) {
		t.Fatal("revoked tenant must not be active")
	}
	if s.RevokeTenant(id) {
		t.Fatal("double revoke should report false")
	}
}

func TestListTenants(t *testing.T) {
	s := newStore(t)
	a := newTenant(t, s)
	b := newTenant(t, s)
	s.RevokeTenant(b)
	list, err := s.ListTenants()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("want 2 tenants, got %d", len(list))
	}
	byID := map[string]Tenant{}
	for _, tn := range list {
		byID[tn.ID] = tn
	}
	if byID[a].Revoked {
		t.Fatal("tenant a should not be revoked")
	}
	if !byID[b].Revoked {
		t.Fatal("tenant b should be revoked")
	}
}

func TestIssueVerifyRevoke(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatal(err)
	}
	dev, ok := s.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify")
	}
	if dev.TenantID != tenant {
		t.Fatalf("Verify TenantID = %q want %q", dev.TenantID, tenant)
	}
	if dev.DevicePubkey != testPubkey {
		t.Fatalf("Verify DevicePubkey = %q want %q", dev.DevicePubkey, testPubkey)
	}
	if dev.TokenHash == "" || len(dev.TokenHash) != 64 {
		t.Fatalf("Verify TokenHash should be a full 64-char hex digest, got %q", dev.TokenHash)
	}
	if _, ok := s.Verify("bogus"); ok {
		t.Fatal("bogus token must not verify")
	}
	if _, ok := s.Verify(""); ok {
		t.Fatal("empty token must not verify")
	}
	if !s.Revoke(tok) {
		t.Fatal("revoke should report removal")
	}
	if _, ok := s.Verify(tok); ok {
		t.Fatal("revoked token must not verify")
	}
	if s.Revoke(tok) {
		t.Fatal("double revoke should report false")
	}
}

func TestIssueRequiresDevicePubkey(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	if _, err := s.Issue(tenant, "phone", ""); err == nil {
		t.Fatal("Issue with an empty device pubkey must return an error")
	}
}

func TestVerifyFailsClosedWhenTenantRevoked(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	s.RevokeTenant(tenant)
	if _, ok := s.Verify(tok); ok {
		t.Fatal("a device token must stop verifying once its tenant is revoked")
	}
}

func TestTokensAreHashedAtRest(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	var count int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM devices WHERE token_hash = ?`, tok).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("the raw token must never appear as a stored token_hash value")
	}
}

func TestPairingCodeSingleUse(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	tok, gotTenant, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok || tok == "" {
		t.Fatal("first redeem should succeed")
	}
	if gotTenant != tenant {
		t.Fatalf("redeemed tenant = %q want %q", gotTenant, tenant)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("reuse of a code must fail")
	}
	if _, ok := s.Verify(tok); !ok {
		t.Fatal("redeemed token should verify")
	}
}

func TestRedeemPairingCodeRequiresDevicePubkey(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := s.RedeemPairingCode(code, "phone", ""); ok {
		t.Fatal("redeeming with an empty device pubkey must fail")
	}
}

func TestPairingCodeExpiry(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, _ := s.NewPairingCode(tenant, -time.Second) // already expired
	if _, _, ok := s.RedeemPairingCode(code, "phone", testPubkey); ok {
		t.Fatal("expired code must fail")
	}
}

func TestPairingCodeStatusReflectsRedemption(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	code, err := s.NewPairingCode(tenant, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	if _, _, redeemed, ok := s.PairingCodeStatus(tenant, code); !ok || redeemed {
		t.Fatalf("fresh code should exist and be unredeemed, got redeemed=%v ok=%v", redeemed, ok)
	}

	tok, _, ok := s.RedeemPairingCode(code, "phone", testPubkey)
	if !ok {
		t.Fatal("redeem should succeed")
	}

	pub, hash, redeemed, ok := s.PairingCodeStatus(tenant, code)
	if !ok || !redeemed {
		t.Fatalf("code should report redeemed, got ok=%v redeemed=%v", ok, redeemed)
	}
	if pub != testPubkey {
		t.Fatalf("PairingCodeStatus pubkey = %q want %q", pub, testPubkey)
	}
	wantHash := hashToken(tok)
	if hash != wantHash {
		t.Fatalf("PairingCodeStatus tokenHash = %q want %q", hash, wantHash)
	}
}

func TestPairingCodeStatusUnknownCode(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	if _, _, _, ok := s.PairingCodeStatus(tenant, "nonexistent"); ok {
		t.Fatal("unknown code should report ok=false")
	}
}

func TestPairingCodeStatusScopedToTenant(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	code, err := s.NewPairingCode(tenantA, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, ok := s.PairingCodeStatus(tenantB, code); ok {
		t.Fatal("a pairing code must not be visible under a different tenant id")
	}
}

func TestPersistenceAcrossReopen(t *testing.T) {
	p := filepath.Join(t.TempDir(), "d.db")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	s2, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Verify(tok); !ok {
		t.Fatal("token must survive reopening the database file")
	}
}

func TestListShowsHashSuffixNotRawToken(t *testing.T) {
	s := newStore(t)
	tenant := newTenant(t, s)
	tok, _ := s.Issue(tenant, "phone", testPubkey)
	list := s.List()
	if len(list) != 1 {
		t.Fatalf("want 1 device, got %d", len(list))
	}
	if list[0].HashSuffix == "" || len(list[0].HashSuffix) != 6 {
		t.Fatalf("want a 6-char hash suffix, got %q", list[0].HashSuffix)
	}
	if list[0].DevicePubkey != testPubkey {
		t.Fatalf("List DevicePubkey = %q want %q", list[0].DevicePubkey, testPubkey)
	}
	for _, want := range []string{tok, tok[len(tok)-6:]} {
		if list[0].HashSuffix == want {
			t.Fatal("List must never expose anything derived from the raw token")
		}
	}
}

func TestTenantFCMTokensScopedPerTenant(t *testing.T) {
	s := newStore(t)
	tenantA := newTenant(t, s)
	tenantB := newTenant(t, s)
	tokA, _ := s.Issue(tenantA, "phone-a", testPubkey)
	tokB, _ := s.Issue(tenantB, "phone-b", testPubkey)

	if got := s.TenantFCMTokens(tenantA); len(got) != 0 {
		t.Fatalf("expected no FCM tokens yet, got %v", got)
	}
	if !s.SetFCMToken(tokA, "fcm-a") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if !s.SetFCMToken(tokB, "fcm-b") {
		t.Fatal("SetFCMToken should succeed for a known device")
	}
	if s.SetFCMToken("bogus", "x") {
		t.Fatal("SetFCMToken must fail for unknown device")
	}

	gotA := s.TenantFCMTokens(tenantA)
	if len(gotA) != 1 || gotA[0] != "fcm-a" {
		t.Fatalf("tenantA tokens = %v, want [fcm-a]", gotA)
	}
	gotB := s.TenantFCMTokens(tenantB)
	if len(gotB) != 1 || gotB[0] != "fcm-b" {
		t.Fatalf("tenantB tokens = %v, want [fcm-b]", gotB)
	}
}

func TestOpenCreatesParentDirectory(t *testing.T) {
	// Verify that Open creates parent directories that don't exist yet.
	// This is a regression test: the old JSON-based store called os.MkdirAll
	// before persisting, and failing to do so causes fresh invocations to crash
	// when the config directory doesn't exist.
	path := filepath.Join(t.TempDir(), "nested", "does-not-exist-yet", "store.db")
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open with non-existent parent dirs: %v", err)
	}

	// Verify the store is usable by calling a cheap read method.
	list, err := s.ListTenants()
	if err != nil {
		t.Fatalf("ListTenants after Open: %v", err)
	}
	if len(list) != 0 {
		t.Fatalf("fresh store should have zero tenants, got %d", len(list))
	}
}

func TestMigrationAddsDevicePubkeyColumn(t *testing.T) {
	path := filepath.Join(t.TempDir(), "d.db")
	// Simulate a pre-migration database file: apply only the original schema
	// (no device_pubkey / pairing-code columns), bypassing Open (which always
	// applies both schema and migrations).
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	db.Close()

	// Opening through the real Open must apply the migration without error,
	// even though the file already exists and predates the new columns.
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open on pre-migration db: %v", err)
	}
	tenant, err := s.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tok, err := s.Issue(tenant, "phone", testPubkey)
	if err != nil {
		t.Fatalf("Issue after migration: %v", err)
	}
	dev, ok := s.Verify(tok)
	if !ok {
		t.Fatal("issued token should verify after migration")
	}
	if dev.DevicePubkey != testPubkey {
		t.Fatalf("DevicePubkey = %q, want %q", dev.DevicePubkey, testPubkey)
	}
}
```

Replace `bridge/internal/auth/middleware_test.go:17` (inside `protected`):

```go
	tok, _ := s.Issue(tenant, "phone", testPubkey)
```

- [ ] **Step 2: Run tests to verify they fail to compile (RED)**

Run: `cd bridge && go test ./internal/auth/... -v`
Expected: FAIL — build error, `Issue`/`RedeemPairingCode` called with the wrong number of arguments, `PairingCodeStatus` undefined, `Device.DevicePubkey`/`Device.TokenHash` undefined.

- [ ] **Step 3: Implement the store.go changes**

Replace `bridge/internal/auth/store.go` in full with:

```go
// Package auth manages tenants, their Mac-agent identity, and paired device
// (phone) bearer tokens, persisted in a local SQLite database. Bearer tokens
// are stored only as a SHA-256 hash — the raw token is returned once, at
// issuance, and never persisted or logged.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS tenants (
	id         TEXT PRIMARY KEY,
	created_at TEXT NOT NULL,
	revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS agent_certs (
	serial     TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	issued_at  TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
	token_hash TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	name       TEXT NOT NULL,
	fcm_token  TEXT,
	created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pairing_codes (
	code       TEXT PRIMARY KEY,
	tenant_id  TEXT NOT NULL REFERENCES tenants(id),
	expires_at TEXT NOT NULL
);
`

// migrations is applied unconditionally after schema on every Open, using
// ADD COLUMN IF NOT EXISTS so it is a no-op on a database that already has
// these columns (including a database created fresh by schema above, once a
// future edit folds them into the base CREATE TABLE) and additive on one that
// predates device-pubkey pairing.
const migrations = `
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_pubkey TEXT NOT NULL DEFAULT '';
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS device_pubkey TEXT;
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS token_hash TEXT;
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS redeemed_at TEXT;
`

// Tenant is a registered Mac-agent identity. Devices belong to exactly one.
type Tenant struct {
	ID        string
	CreatedAt time.Time
	Revoked   bool
}

// Device is a paired client (a phone) belonging to exactly one tenant.
type Device struct {
	TenantID string
	Name     string
	FCM      string
	Created  time.Time
	// DevicePubkey is the device's base64-encoded X25519 public key, submitted
	// at pairing time (internal/e2e). Every device has one — Issue and
	// RedeemPairingCode both reject an empty value.
	DevicePubkey string
	// TokenHash is the full SHA-256 hex digest of the raw token — used as the
	// internal/e2e.Store session key, and injected as X-Device-ID by the
	// relay's proxy Director (internal/relay/proxy.go) so the agent can tell
	// which device is speaking without ever seeing the raw bearer token.
	TokenHash string
	// HashSuffix is the last 6 hex characters of TokenHash — enough for an
	// operator to eyeball which device is which in `cmux-relay devices list`
	// output without printing the full hash.
	HashSuffix string
}

// Store holds tenants, agent-cert audit records, devices, and pairing codes
// in a local SQLite database at path.
type Store struct {
	mu sync.Mutex
	db *sql.DB
}

// Open opens (creating if absent) the SQLite database at path and applies the
// schema and migrations. Safe to call from multiple short-lived processes
// (the relay server and the `cmux-relay` CLI) against the same file — SQLite
// handles the locking, so there is no in-memory cache to fall out of sync.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create dir for store: %w", err)
	}
	// Add busy_timeout pragma to allow multiple processes to wait instead of
	// immediately failing with SQLITE_BUSY. 5 seconds should be ample for
	// infrequent operations like tenant/device list and pairing code redemption.
	dsn := path + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open store %s: %w", path, err)
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	if _, err := db.Exec(migrations); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply migrations: %w", err)
	}
	return &Store{db: db}, nil
}

func now() string { return time.Now().UTC().Format(time.RFC3339) }

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// CreateTenant mints a fresh, unguessable tenant ID.
func (s *Store) CreateTenant() (string, error) {
	id, err := randomHex(16)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.db.Exec(`INSERT INTO tenants (id, created_at) VALUES (?, ?)`, id, now()); err != nil {
		return "", fmt.Errorf("create tenant: %w", err)
	}
	return id, nil
}

// TenantActive reports whether id names a tenant that exists and has not
// been revoked.
func (s *Store) TenantActive(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	var revoked sql.NullString
	err := s.db.QueryRow(`SELECT revoked_at FROM tenants WHERE id = ?`, id).Scan(&revoked)
	if err != nil {
		return false
	}
	return !revoked.Valid
}

// RevokeTenant marks a tenant revoked. Its agent tunnel is refused on next
// connect and its devices' bearer tokens stop verifying immediately (Verify
// joins against tenants.revoked_at).
func (s *Store) RevokeTenant(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE tenants SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, now(), id)
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// ListTenants returns every tenant, oldest first.
func (s *Store) ListTenants() ([]Tenant, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT id, created_at, revoked_at FROM tenants ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Tenant
	for rows.Next() {
		var t Tenant
		var created string
		var revoked sql.NullString
		if err := rows.Scan(&t.ID, &created, &revoked); err != nil {
			return nil, err
		}
		t.CreatedAt, _ = time.Parse(time.RFC3339, created)
		t.Revoked = revoked.Valid
		out = append(out, t)
	}
	return out, rows.Err()
}

// RecordAgentCert logs an issued agent-cert serial against its tenant, for
// audit purposes only. It is not consulted for access control in this
// version: revoking a specific cert without losing tenant identity (key
// rotation) is out of scope — revoking a tenant revokes its whole identity.
func (s *Store) RecordAgentCert(tenantID, serial string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO agent_certs (serial, tenant_id, issued_at) VALUES (?, ?, ?)`,
		serial, tenantID, now())
	return err
}

// Issue creates a new device bearer token for tenantID, bound to
// devicePubkey (the device's base64 X25519 public key). The raw token is
// returned once, here — only its hash is ever persisted. devicePubkey is
// required: there is no manual-pairing fallback path in this version, so a
// device is never issued a token without an e2e identity to encrypt to.
func (s *Store) Issue(tenantID, name, devicePubkey string) (string, error) {
	if devicePubkey == "" {
		return "", fmt.Errorf("issue device token: device_pubkey is required")
	}
	tok, err := randomHex(32)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err = s.db.Exec(`INSERT INTO devices (token_hash, tenant_id, name, device_pubkey, created_at) VALUES (?, ?, ?, ?, ?)`,
		hashToken(tok), tenantID, name, devicePubkey, now())
	if err != nil {
		return "", fmt.Errorf("issue device token: %w", err)
	}
	return tok, nil
}

// Verify returns the device for a token, provided its tenant has not been
// revoked. A SHA-256 digest lookup needs no constant-time comparison here:
// unlike a raw-token equality check with early-exit branching, an indexed
// lookup on a fully-avalanching hash leaks no exploitable timing signal.
func (s *Store) Verify(token string) (Device, bool) {
	if token == "" {
		return Device{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	hash := hashToken(token)
	row := s.db.QueryRow(`
		SELECT d.tenant_id, d.name, d.fcm_token, d.device_pubkey, d.created_at
		FROM devices d JOIN tenants t ON t.id = d.tenant_id
		WHERE d.token_hash = ? AND t.revoked_at IS NULL`, hash)
	var dev Device
	var fcm sql.NullString
	var created string
	if err := row.Scan(&dev.TenantID, &dev.Name, &fcm, &dev.DevicePubkey, &created); err != nil {
		return Device{}, false
	}
	dev.FCM = fcm.String
	dev.Created, _ = time.Parse(time.RFC3339, created)
	dev.TokenHash = hash
	dev.HashSuffix = hash[len(hash)-6:]
	return dev, true
}

// List returns all devices across all tenants.
func (s *Store) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT token_hash, tenant_id, name, fcm_token, device_pubkey, created_at FROM devices ORDER BY created_at`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var dev Device
		var hash string
		var fcm sql.NullString
		var created string
		if err := rows.Scan(&hash, &dev.TenantID, &dev.Name, &fcm, &dev.DevicePubkey, &created); err != nil {
			continue
		}
		dev.FCM = fcm.String
		dev.Created, _ = time.Parse(time.RFC3339, created)
		dev.TokenHash = hash
		dev.HashSuffix = hash[len(hash)-6:]
		out = append(out, dev)
	}
	return out
}

// Revoke removes a device by its raw token. Reports whether a device was
// removed.
func (s *Store) Revoke(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`DELETE FROM devices WHERE token_hash = ?`, hashToken(token))
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// SetFCMToken records the FCM registration token for a device token.
func (s *Store) SetFCMToken(token, fcm string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE devices SET fcm_token = ? WHERE token_hash = ?`, fcm, hashToken(token))
	if err != nil {
		return false
	}
	n, _ := res.RowsAffected()
	return n > 0
}

// TenantFCMTokens returns all non-empty FCM registration tokens belonging to
// tenantID's own devices. Scoped per tenant so an attention push triggered by
// one tenant's agent can never fan out to another tenant's phones.
func (s *Store) TenantFCMTokens(tenantID string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT fcm_token FROM devices WHERE tenant_id = ? AND fcm_token IS NOT NULL AND fcm_token != ''`, tenantID)
	if err != nil {
		return []string{}
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var tok string
		if err := rows.Scan(&tok); err == nil {
			out = append(out, tok)
		}
	}
	return out
}

const pairingCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // unambiguous: no 0/O/1/I

func randomCode(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	out := make([]byte, n)
	for i := range b {
		out[i] = pairingCodeAlphabet[int(b[i])%len(pairingCodeAlphabet)]
	}
	return string(out)
}

// NewPairingCode generates a single-use pairing code, scoped to tenantID,
// valid for ttl.
func (s *Store) NewPairingCode(tenantID string, ttl time.Duration) (string, error) {
	code := randomCode(8)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO pairing_codes (code, tenant_id, expires_at) VALUES (?, ?, ?)`,
		code, tenantID, time.Now().Add(ttl).UTC().Format(time.RFC3339))
	if err != nil {
		return "", err
	}
	return code, nil
}

// RedeemPairingCode consumes a single-use pairing code, issuing a fresh
// device token bound to devicePubkey. Unlike the old CLI-driven flow this
// replaces, this is non-destructive: the pairing_codes row is UPDATEd (not
// deleted) so the issuing agent's poll (PairingCodeStatus) can retrieve the
// device's token hash and public key after redemption — the raw token itself
// is returned only here, to the caller (the phone), and never persisted.
// Atomic at the database level via a transaction, so two concurrent
// redemption attempts on the same code can't both succeed.
func (s *Store) RedeemPairingCode(code, name, devicePubkey string) (token, tenantID string, ok bool) {
	if devicePubkey == "" {
		return "", "", false
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.Begin()
	if err != nil {
		return "", "", false
	}
	defer tx.Rollback() //nolint:errcheck // no-op after a successful Commit

	var expiresAt string
	var redeemedAt sql.NullString
	err = tx.QueryRow(`SELECT tenant_id, expires_at, redeemed_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&tenantID, &expiresAt, &redeemedAt)
	if err != nil || redeemedAt.Valid {
		return "", "", false
	}
	exp, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil || time.Now().After(exp) {
		return "", "", false
	}

	tok, err := randomHex(32)
	if err != nil {
		return "", "", false
	}
	hash := hashToken(tok)
	if _, err := tx.Exec(`INSERT INTO devices (token_hash, tenant_id, name, device_pubkey, created_at) VALUES (?, ?, ?, ?, ?)`,
		hash, tenantID, name, devicePubkey, now()); err != nil {
		return "", "", false
	}
	if _, err := tx.Exec(`UPDATE pairing_codes SET device_pubkey = ?, token_hash = ?, redeemed_at = ? WHERE code = ?`,
		devicePubkey, hash, now(), code); err != nil {
		return "", "", false
	}
	if err := tx.Commit(); err != nil {
		return "", "", false
	}
	return tok, tenantID, true
}

// PairingCodeStatus reports whether code exists under tenantID, and if so
// whether it has been redeemed. Once redeemed, it returns the redeeming
// device's public key and full token hash — never the raw token, which was
// already handed to the phone directly by RedeemPairingCode and is never
// persisted. Used by `cmux-bridge pair-device`'s poll loop to learn a
// device's identity once the phone completes /devices/pair. Scoped to
// tenantID so one tenant's agent can never observe another tenant's pairing
// codes, even by guessing.
func (s *Store) PairingCodeStatus(tenantID, code string) (devicePubkey, tokenHash string, redeemed, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var gotTenant string
	var pubkey, hash, redeemedAt sql.NullString
	err := s.db.QueryRow(`SELECT tenant_id, device_pubkey, token_hash, redeemed_at FROM pairing_codes WHERE code = ?`, code).
		Scan(&gotTenant, &pubkey, &hash, &redeemedAt)
	if err != nil || gotTenant != tenantID {
		return "", "", false, false
	}
	if !redeemedAt.Valid {
		return "", "", false, true
	}
	return pubkey.String, hash.String, true, true
}
```

- [ ] **Step 4: Run auth package tests to verify they pass**

Run: `cd bridge && go test ./internal/auth/... -v`
Expected: PASS (all tests, including the new `TestMigrationAddsDevicePubkeyColumn`, `TestPairingCodeStatus*`, `TestIssueRequiresDevicePubkey`, `TestRedeemPairingCodeRequiresDevicePubkey`)

- [ ] **Step 5: Fix ripple call sites in other packages**

Replace `bridge/internal/server/sessions_test.go:46` (inside `newTestServer`):

```go
	tok, _ := store.Issue(tenant, "phone", "test-device-pubkey-b64")
```

Replace `bridge/internal/cli/cli_test.go:36`:

```go
	if _, err := store.Issue(tenant, "phone", "test-device-pubkey-b64"); err != nil {
```

Replace `bridge/internal/relay/relay_test.go:63`:

```go
	devTok, _ := relayStore.Issue(tenantID, "phone", "test-device-pubkey-b64")
```

Replace `bridge/internal/relay/multitenant_test.go:41-42`:

```go
	devA, _ := store.Issue(tenantA, "phone-a", "test-device-pubkey-a")
	devB, _ := store.Issue(tenantB, "phone-b", "test-device-pubkey-b")
```

Replace `bridge/internal/relay/multitenant_test.go:100`:

```go
	devTok, _ := store.Issue(tenantID, "phone", "test-device-pubkey-b64")
```

Replace `bridge/internal/relay/proxy_test.go:54`:

```go
	devTok, err := store.Issue(tenantID, "phone", "test-device-pubkey-b64")
```

Replace `bridge/internal/relay/pushmon_test.go:67`:

```go
	tok, _ := store.Issue(tenant, "phone", "test-device-pubkey-b64")
```

Replace `bridge/internal/relay/pushmon_test.go:124-125`:

```go
	tokA, _ := store.Issue(tenantA, "phone-a", "test-device-pubkey-a")
	tokB, _ := store.Issue(tenantB, "phone-b", "test-device-pubkey-b")
```

- [ ] **Step 6: Delete the `cmux-relay pair` CLI command**

No test changes needed for this step: `runPair` was never covered by an automated test (confirmed via repo grep for `runPair`/`TestPair`/`"pair"` across `cmd/` — the only hits are its own definition and dispatch, no `commands_test.go` exists in `cmd/cmux-relay`). Its removal is verified by the build and the full test suite passing, not by a deleted test.

Remove `bridge/cmd/cmux-relay/commands.go`'s entire `runPair` function (from its `func runPair(args []string) int {` line through its closing `}`, currently the first function in the file). The file's imports (`flag`, `fmt`, `log`, `os`, `time`, `internal/cli`) are all still used by the remaining `runDevices`/`runTenants` functions, so the import block is otherwise unchanged. The file's remaining content, in full, is:

```go
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cli"
)

func runDevices(args []string) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("devices: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		devs := store.List()
		if len(devs) == 0 {
			fmt.Println("no paired devices")
			return 0
		}
		for _, d := range devs {
			fmt.Printf("%-16s  tenant=%s  token=...%s  fcm=%v  created=%s\n",
				d.Name, d.TenantID, d.HashSuffix, d.FCM != "", d.Created.Format(time.RFC3339))
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay devices revoke <token>")
			return 2
		}
		if store.Revoke(rest[1]) {
			fmt.Println("revoked")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such token")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay devices [list|revoke <token>]")
		return 2
	}
}

func runTenants(args []string) int {
	fs := flag.NewFlagSet("tenants", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultConfigPath(), "path to config.toml")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	_, store, err := cli.LoadStore(*cfgPath)
	if err != nil {
		log.Printf("tenants: %v", err)
		return 1
	}
	rest := fs.Args()
	switch {
	case len(rest) == 0 || rest[0] == "list":
		tenants, err := store.ListTenants()
		if err != nil {
			log.Printf("tenants: %v", err)
			return 1
		}
		if len(tenants) == 0 {
			fmt.Println("no tenants")
			return 0
		}
		for _, t := range tenants {
			fmt.Printf("%s  created=%s  revoked=%v\n", t.ID, t.CreatedAt.Format(time.RFC3339), t.Revoked)
		}
		return 0
	case rest[0] == "revoke":
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants revoke <id>")
			return 2
		}
		if store.RevokeTenant(rest[1]) {
			fmt.Println("revoked (this also stops all of that tenant's devices from verifying)")
			return 0
		}
		fmt.Fprintln(os.Stderr, "no such tenant, or already revoked")
		return 1
	default:
		fmt.Fprintln(os.Stderr, "usage: cmux-relay tenants [list|revoke <id>]")
		return 2
	}
}
```

Replace `bridge/cmd/cmux-relay/main.go` in full — remove the `pair` dispatch case and update the usage string:

```go
package main

import (
	"fmt"
	"os"

	"github.com/sodre90/cmux-bridge/internal/version"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "serve":
		os.Exit(runServe(os.Args[2:]))
	case "devices":
		os.Exit(runDevices(os.Args[2:]))
	case "tenants":
		os.Exit(runTenants(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-relay", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-relay <serve|devices|tenants|version> [flags]")
}
```

- [ ] **Step 7: Run the full test suite to verify everything passes**

Run: `cd bridge && go build ./... && go test ./... -v`
Expected: `go build` succeeds with no errors. All tests PASS across every package (`internal/auth`, `internal/cli`, `internal/relay`, `internal/server`, `internal/config`, `internal/e2e`, `cmd/cmux-relay`).

- [ ] **Step 8: Commit**

```bash
git add bridge/internal/auth/store.go bridge/internal/auth/store_test.go bridge/internal/auth/middleware_test.go bridge/internal/server/sessions_test.go bridge/internal/cli/cli_test.go bridge/internal/relay/relay_test.go bridge/internal/relay/multitenant_test.go bridge/internal/relay/proxy_test.go bridge/internal/relay/pushmon_test.go bridge/cmd/cmux-relay/commands.go bridge/cmd/cmux-relay/main.go
git commit -m "auth: require device_pubkey on issue; remove the operator-driven pair CLI"
```

---
### Task 7: `internal/relay` agent-facing pairing-code endpoints

**Files:**
- Modify: `bridge/internal/relay/relay.go`
- Create: `bridge/internal/relay/pairing_test.go`

**Interfaces:**
- Consumes: `Store.NewPairingCode`, `Store.PairingCodeStatus` (Task 6); `tenantFromAgentCN`, `r.clientCN`, `r.store.TenantActive`, `writeJSONErr` (all pre-existing in `relay.go`/`proxy.go`).
- Produces: `POST /agent/pairing-code` (agent-CN-gated, returns `{"code","expires_at"}`), `GET /agent/pairing-code/{code}` (agent-CN-gated, scoped to the caller's own tenant, returns `{"redeemed","device_pubkey","token_hash"}`), `func (r *Relay) agentOnly(req *http.Request) (tenantID string, ok bool)`.

`agentOnly`'s check here is CN-only (a bare `tenantFromAgentCN` + `TenantActive` check) — safe at this point in the plan because `ssl_verify_client` is still mandatory (`on`) in nginx, so nginx itself guarantees any request reaching the relay already presented a cert chaining to the trusted CA. **Task 8 hardens this**: once `ssl_verify_client` becomes `optional` (to let certless paired devices connect), a bare CN can no longer be trusted, and `agentOnly` (along with the pre-existing `handleTunnel`/`notAgent`) is rewired through a new `verifiedAgentTenant` helper. Do not skip Task 8's hardening — it closes a real cross-tenant impersonation gap that only exists once `ssl_verify_client` is relaxed.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/relay/pairing_test.go`:

```go
package relay

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
)

func TestNewPairingCodeRequiresAgentCN(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=phone")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a non-agent CN, got %d", resp.StatusCode)
	}
}

func TestNewPairingCodeIssuesCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body pairingCodeResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code == "" || body.ExpiresAt == "" {
		t.Fatalf("unexpected body: %+v", body)
	}
	if body.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", body.TenantID, tenantID)
	}
}

func TestNewPairingCodeRejectsRevokedTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	store.RevokeTenant(tenantID)
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a revoked tenant, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusPendingThenRedeemed(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	poll := func() pairingCodeStatusResp {
		req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("want 200, got %d", resp.StatusCode)
		}
		var body pairingCodeStatusResp
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		return body
	}

	if got := poll(); got.Redeemed {
		t.Fatalf("code should not be redeemed yet: %+v", got)
	}

	tok, _, ok := store.RedeemPairingCode(code, "phone", "device-pubkey-b64")
	if !ok || tok == "" {
		t.Fatal("redeem should succeed")
	}

	got := poll()
	if !got.Redeemed || got.DevicePubkey != "device-pubkey-b64" {
		t.Fatalf("unexpected status after redeem: %+v", got)
	}
}

func TestPairingCodeStatusUnknownCodeIs404(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/bogus", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusScopedToOwnTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantA, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tenantB, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantA, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantB)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("tenant B must not see tenant A's pairing code, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusRequiresAgentCN(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=phone")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a non-agent CN, got %d", resp.StatusCode)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/relay/... -run 'TestNewPairingCode|TestPairingCodeStatus' -v`
Expected: FAIL — `/agent/pairing-code` routes 404 (unregistered), `pairingCodeResp`/`pairingCodeStatusResp` undefined.

- [ ] **Step 3: Implement the endpoints**

Modify `bridge/internal/relay/relay.go`: add `"time"`-based TTL const (the file already imports `"time"` for `agentCertValidity`), an `agentOnly` helper, two handlers, and two new route registrations in `Handler()`.

Insert after the `agentCertValidity` const (relay.go, after line 27):

```go
// pairingCodeTTL is how long a self-service pairing code stays redeemable.
const pairingCodeTTL = 10 * time.Minute
```

Insert after `tenantFromAgentCN` (relay.go, after its closing brace):

```go
// agentOnly extracts the calling agent's tenant ID from its mTLS CN,
// rejecting any request that isn't a valid, currently-active agent. Used by
// the agent-facing pairing-code endpoints below, which authenticate via mTLS
// CN rather than auth.Require's device bearer token.
//
// NOTE: this check is CN-only for now. Task 8 hardens it to also require
// nginx's X-Client-Cert-Verify == "SUCCESS" once ssl_verify_client stops
// being mandatory — see that task's Global-Constraint-driven rewrite. Do not
// copy this CN-only pattern into new code once Task 8 lands.
func (r *Relay) agentOnly(req *http.Request) (string, bool) {
	tenantID, ok := tenantFromAgentCN(r.clientCN(req))
	if !ok || !r.store.TenantActive(tenantID) {
		return "", false
	}
	return tenantID, true
}
```

Insert after `handleTunnel` (relay.go, after its closing brace, before `type registerReq struct`):

```go
type pairingCodeResp struct {
	Code      string `json:"code"`
	ExpiresAt string `json:"expires_at"`
	TenantID  string `json:"tenant_id"`
}

// handleNewPairingCode lets an already-registered agent request a fresh
// single-use pairing code to embed in a QR code (see
// cmd/cmux-bridge/pair.go). Agent-CN-gated: only a request presenting a
// valid, active agent's mTLS certificate may call this. TenantID is echoed
// back so the QR payload can carry it for display, even though /devices/pair
// itself never needs it in the request (see the Global Constraint on that
// endpoint's simplified request/response shapes) — the pairing code alone is
// resolved to a tenant server-side.
func (r *Relay) handleNewPairingCode(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.agentOnly(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	code, err := r.store.NewPairingCode(tenantID, pairingCodeTTL)
	if err != nil {
		log.Printf("relay: new pairing code: %v", err)
		writeJSONErr(w, http.StatusInternalServerError, "internal_error")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pairingCodeResp{
		Code:      code,
		ExpiresAt: time.Now().Add(pairingCodeTTL).UTC().Format(time.RFC3339),
		TenantID:  tenantID,
	})
}

type pairingCodeStatusResp struct {
	Redeemed     bool   `json:"redeemed"`
	DevicePubkey string `json:"device_pubkey,omitempty"`
	TokenHash    string `json:"token_hash,omitempty"`
}

// handlePairingCodeStatus lets the agent that requested a pairing code poll
// for its redemption. Scoped to the caller's own tenant (via agentOnly), so
// one tenant's agent can never observe another tenant's pairing codes.
func (r *Relay) handlePairingCodeStatus(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.agentOnly(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
	code := req.PathValue("code")
	pubkey, hash, redeemed, ok := r.store.PairingCodeStatus(tenantID, code)
	if !ok {
		writeJSONErr(w, http.StatusNotFound, "not_found")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pairingCodeStatusResp{
		Redeemed:     redeemed,
		DevicePubkey: pubkey,
		TokenHash:    hash,
	})
}
```

Modify `bridge/internal/relay/relay.go`'s `Handler()` — insert two new route registrations right after the `/agent/tunnel` line:

```go
	mux.HandleFunc("/agent/tunnel", r.handleTunnel)
	mux.Handle("POST /agent/pairing-code", http.HandlerFunc(r.handleNewPairingCode))
	mux.Handle("GET /agent/pairing-code/{code}", http.HandlerFunc(r.handlePairingCodeStatus))
	mux.Handle("POST /tenants/register", http.HandlerFunc(r.handleRegisterTenant))
```

(This replaces the existing two-line block of `mux.HandleFunc("/agent/tunnel", ...)` + `mux.Handle("POST /tenants/register", ...)` with the four-line block above — the `POST /devices/register` and `/` lines that follow are unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/relay/... -v`
Expected: PASS (all tests in the package, including the pre-existing ones — `/agent/pairing-code*` are new exact-method+path patterns, which Go 1.22+ `http.ServeMux` prioritizes over the `/` catch-all, so no existing route's behavior changes)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/relay.go bridge/internal/relay/pairing_test.go
git commit -m "relay: add agent-facing pairing-code request/poll endpoints"
```

---

### Task 8: `internal/relay` device-facing pairing endpoint + verified-agent-CN hardening

**Files:**
- Modify: `bridge/internal/relay/relay.go`
- Modify: `bridge/internal/relay/pairing_test.go`
- Modify: `bridge/internal/relay/relay_test.go`
- Modify: `bridge/internal/relay/multitenant_test.go`
- Modify: `bridge/deploy/nginx-cmux-relay.conf`

**Interfaces:**
- Consumes: `Store.RedeemPairingCode` (Task 6); `tenantFromAgentCN`, `r.clientCN` (pre-existing); `r.agentOnly` (Task 7, hardened here).
- Produces: `POST /devices/pair` (fully public, no cert, no bearer token — returns `{"token","tenant_id"}`); `func (r *Relay) verifiedAgentTenant(req *http.Request) (tenantID string, ok bool)` (new — every agent-CN check in the package routes through this from here on).

This task does two things that must land together, not separately: it lets certless devices reach the relay (`ssl_verify_client optional` + the new `/devices/pair` endpoint), and it closes the cross-tenant impersonation gap that relaxation opens (nginx forwards `X-Client-Cert-CN` for *any* presented certificate once verification is optional — including a trivial self-signed one — so a bare CN match stops being proof of agent identity the moment this task's nginx change lands). Landing the relaxation without the hardening, even for one commit, would let any caller present a throwaway cert with `CN=agent:<victim-tenant-id>` and hijack that tenant's agent tunnel outright.

**No bootstrap-vhost change is needed.** Once the main vhost's `ssl_verify_client` is `optional`, a certless phone can already complete a TLS handshake against the *main* vhost and reach its `location /` catch-all, which already proxies to the relay's `Handler()` mux — and `POST /devices/pair` is mounted there, fully public, with no cert or bearer-token requirement of its own. `bridge/deploy/nginx-cmux-relay-bootstrap.conf` (the separate no-mTLS vhost used only for `POST /tenants/register`) needs no changes at all for pairing.

- [ ] **Step 1: Write the failing tests**

Append to `bridge/internal/relay/pairing_test.go`:

```go
func TestDevicePairRedeemsCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64","name":"my-phone"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var got devicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Token == "" {
		t.Fatal("expected a non-empty device token")
	}
	if got.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", got.TenantID, tenantID)
	}
	dev, ok := store.Verify(got.Token)
	if !ok {
		t.Fatal("returned token should verify")
	}
	if dev.Name != "my-phone" || dev.DevicePubkey != "device-pubkey-b64" || dev.TenantID != tenantID {
		t.Fatalf("unexpected device: %+v", dev)
	}
}

func TestDevicePairDefaultsName(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var got devicePairResp
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	dev, ok := store.Verify(got.Token)
	if !ok || dev.Name != "phone" {
		t.Fatalf("expected default name %q, got device: %+v ok=%v", "phone", dev, ok)
	}
}

func TestDevicePairRejectsUnknownCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"bogus","device_pubkey":"x"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("want 410, got %d", resp.StatusCode)
	}
}

func TestDevicePairRejectsNoDevicePubkey(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `"}`
	resp, err := http.Post(srv.URL+"/devices/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("want 400 for missing device_pubkey, got %d", resp.StatusCode)
	}
}

func TestDevicePairWorksWithNoClientCert(t *testing.T) {
	// /devices/pair must be reachable by a phone presenting no client cert at
	// all (X-Client-Cert-Cn absent) -- this is the whole point of the
	// self-service flow. notAgent/auth.Require must never gate this route.
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	body := `{"code":"` + code + `","device_pubkey":"device-pubkey-b64"}`
	req, _ := http.NewRequest("POST", srv.URL+"/devices/pair", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	// Deliberately no X-Client-Cert-Cn header.
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200 with no client cert, got %d", resp.StatusCode)
	}
}

func TestNewPairingCodeRejectsUnverifiedCert(t *testing.T) {
	// The regression test for the cross-tenant impersonation gap: once
	// ssl_verify_client is optional, nginx forwards X-Client-Cert-CN for any
	// presented certificate, verified or not. A correct CN with no (or a
	// failed) X-Client-Cert-Verify must still be rejected.
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	for _, verify := range []string{"", "FAILED:self-signed certificate", "NONE"} {
		req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		if verify != "" {
			req.Header.Set("X-Client-Cert-Verify", verify)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("X-Client-Cert-Verify=%q: want 403 for an unverified cert with a correct CN, got %d", verify, resp.StatusCode)
		}
	}
}

func TestHandleTunnelRejectsUnverifiedAgentCert(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	// Deliberately no X-Client-Cert-Verify: SUCCESS -- a spoofed self-signed
	// cert with the right CN must not be enough to open a tunnel.
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for an unverified agent cert, got %d", resp.StatusCode)
	}
}
```

Add `"strings"` to `pairing_test.go`'s import block.

Five of `bridge/internal/relay/pairing_test.go`'s existing Task 7 tests set an **agent** CN and expect the request to proceed past the gate — each now also needs `X-Client-Cert-Verify: SUCCESS`, since `agentOnly` routes through `verifiedAgentTenant` as of this task. (`TestNewPairingCodeRequiresAgentCN` and `TestPairingCodeStatusRequiresAgentCN` use a non-agent CN, `CN=phone`, and already expect 403 regardless of verification, so they are unaffected and not shown here.) Replace each of the five in full:

```go
func TestNewPairingCodeIssuesCode(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var body pairingCodeResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code == "" || body.ExpiresAt == "" {
		t.Fatalf("unexpected body: %+v", body)
	}
	if body.TenantID != tenantID {
		t.Fatalf("TenantID = %q, want %q", body.TenantID, tenantID)
	}
}

func TestNewPairingCodeRejectsRevokedTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	store.RevokeTenant(tenantID)
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("POST", srv.URL+"/agent/pairing-code", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403 for a revoked tenant, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusPendingThenRedeemed(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	code, err := store.NewPairingCode(tenantID, time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	poll := func() pairingCodeStatusResp {
		req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
		req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
		req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("want 200, got %d", resp.StatusCode)
		}
		var body pairingCodeStatusResp
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		return body
	}

	if got := poll(); got.Redeemed {
		t.Fatalf("code should not be redeemed yet: %+v", got)
	}

	tok, _, ok := store.RedeemPairingCode(code, "phone", "device-pubkey-b64")
	if !ok || tok == "" {
		t.Fatal("redeem should succeed")
	}

	got := poll()
	if !got.Redeemed || got.DevicePubkey != "device-pubkey-b64" {
		t.Fatalf("unexpected status after redeem: %+v", got)
	}
}

func TestPairingCodeStatusUnknownCodeIs404(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/bogus", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("want 404, got %d", resp.StatusCode)
	}
}

func TestPairingCodeStatusScopedToOwnTenant(t *testing.T) {
	store, err := auth.Open(t.TempDir() + "/r.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantA, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	tenantB, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	code, err := store.NewPairingCode(tenantA, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rl := New(store, nil, "relay-secret")
	srv := httptest.NewServer(rl.Handler())
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/agent/pairing-code/"+code, nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantB)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("tenant B must not see tenant A's pairing code, got %d", resp.StatusCode)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/relay/... -run 'TestDevicePair|TestNewPairingCodeRejectsUnverifiedCert|TestHandleTunnelRejectsUnverifiedAgentCert' -v`
Expected: FAIL — `/devices/pair` 404s (unregistered), `devicePairResp` undefined; the two new "rejects unverified" tests fail because nothing checks `X-Client-Cert-Verify` yet, so the pre-hardening `agentOnly`/raw `tenantFromAgentCN` checks let them through (200/101 instead of the expected 403).

- [ ] **Step 3: Add the `verifiedAgentTenant` helper and harden every agent-CN call site**

Insert into `bridge/internal/relay/relay.go`, directly after `agentOnly`'s closing brace:

```go
// verifiedAgentTenant extracts the calling agent's tenant ID from its mTLS
// CN, requiring nginx to report the presented certificate as independently
// verified (X-Client-Cert-Verify: SUCCESS) before trusting the CN at all.
//
// Before this method existed, every agent-CN check (handleTunnel, notAgent,
// agentOnly) trusted X-Client-Cert-CN on its own -- safe only because
// ssl_verify_client was mandatory ("on"), so nginx guaranteed any request
// that reached the relay had already presented a cert chaining to the
// trusted CA, or the TLS handshake would have failed before the request
// ever arrived. Now that ssl_verify_client is optional (see
// deploy/nginx-cmux-relay.conf, changed below in this same task, to let
// certless paired devices connect), nginx forwards X-Client-Cert-CN for ANY
// presented certificate -- including a trivial self-signed one with
// CN=agent:<any-tenant-id> -- so a bare CN match is no longer proof of agent
// identity; only a verified cert is.
func (r *Relay) verifiedAgentTenant(req *http.Request) (string, bool) {
	if req.Header.Get("X-Client-Cert-Verify") != "SUCCESS" {
		return "", false
	}
	tenantID, ok := tenantFromAgentCN(r.clientCN(req))
	if !ok || !r.store.TenantActive(tenantID) {
		return "", false
	}
	return tenantID, true
}
```

Replace `agentOnly`'s body (Task 7) in full, so it delegates to the new helper instead of duplicating the CN+active check:

```go
// agentOnly extracts the calling agent's tenant ID from its mTLS CN,
// rejecting any request that isn't a valid, currently-active, VERIFIED
// agent. Used by the agent-facing pairing-code endpoints, which
// authenticate via mTLS CN rather than auth.Require's device bearer token.
func (r *Relay) agentOnly(req *http.Request) (string, bool) {
	return r.verifiedAgentTenant(req)
}
```

Replace `notAgent` in full:

```go
// notAgent rejects requests bearing a verified agent CN on non-tunnel
// routes.
func (r *Relay) notAgent(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if _, ok := r.verifiedAgentTenant(req); ok {
			writeJSONErr(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, req)
	})
}
```

Replace `handleTunnel`'s opening tenant check (its first 4 lines, everything up to and including the `writeJSONErr`/`return` on a failed check):

```go
func (r *Relay) handleTunnel(w http.ResponseWriter, req *http.Request) {
	tenantID, ok := r.verifiedAgentTenant(req)
	if !ok {
		writeJSONErr(w, http.StatusForbidden, "forbidden")
		return
	}
```

(The rest of `handleTunnel` — `tunnel.Accept`, session registration, the `onSession` hook, the block-until-closed wait — is unchanged.)

- [ ] **Step 4: Implement the device-pairing endpoint**

Insert into `bridge/internal/relay/relay.go`, after `handleRegisterTenant`'s closing brace (end of file):

```go
type devicePairReq struct {
	Code         string `json:"code"`
	DevicePubkey string `json:"device_pubkey"`
	Name         string `json:"name"`
}

type devicePairResp struct {
	Token    string `json:"token"`
	TenantID string `json:"tenant_id"`
}

// handleDevicePair is the public, no-auth endpoint a phone hits directly
// after scanning the agent's pairing QR code. Reachable without a client
// cert (see deploy/nginx-cmux-relay.conf's ssl_verify_client optional
// change below) — a brand-new phone has no cert to present yet, mirroring
// handleRegisterTenant's bootstrap story for agents. The response omits the
// agent's e2e public key (the phone already has it from the QR code payload
// itself, cmd/cmux-bridge/pair.go — the relay never needs to hold or
// forward e2e key material) but keeps tenant_id, informationally, so the
// app knows which workspace it just joined.
func (r *Relay) handleDevicePair(w http.ResponseWriter, req *http.Request) {
	req.Body = http.MaxBytesReader(w, req.Body, 4<<10)
	var rq devicePairReq
	if err := json.NewDecoder(req.Body).Decode(&rq); err != nil || rq.Code == "" || rq.DevicePubkey == "" {
		writeJSONErr(w, http.StatusBadRequest, "missing code or device_pubkey")
		return
	}
	name := rq.Name
	if name == "" {
		name = "phone"
	}
	tok, tenantID, ok := r.store.RedeemPairingCode(rq.Code, name, rq.DevicePubkey)
	if !ok {
		// RedeemPairingCode's bool return doesn't distinguish not-found,
		// expired, and already-redeemed -- per the spec's error-handling
		// section, all three map to the same response.
		writeJSONErr(w, http.StatusGone, "pairing_code_invalid")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(devicePairResp{Token: tok, TenantID: tenantID})
	log.Printf("relay: device paired via QR code")
}
```

Modify `bridge/internal/relay/relay.go`'s `Handler()` — insert one new route registration right after `POST /devices/register`:

```go
	mux.Handle("POST /devices/register", r.notAgent(auth.Require(r.store, http.HandlerFunc(r.handleRegister))))
	mux.Handle("POST /devices/pair", http.HandlerFunc(r.handleDevicePair))
	mux.Handle("/", r.notAgent(auth.Require(r.store, r.logProxy(r.proxy))))
```

- [ ] **Step 5: Relax nginx's client-cert verification and forward the verification result**

Replace `bridge/deploy/nginx-cmux-relay.conf`'s mutual-TLS block and `location /` block in full:

```nginx
    # Mutual TLS: agent + device certs are signed by this one CA. Devices
    # paired via /devices/pair never get a client cert at all -- verification
    # must therefore be optional, not mandatory. Because it's optional, nginx
    # forwards X-Client-Cert-CN for ANY presented certificate, verified or
    # not (e.g. a trivial self-signed one) -- so it also forwards
    # X-Client-Cert-Verify, and every Go-side agent-CN check
    # (verifiedAgentTenant in internal/relay/relay.go) requires it to read
    # exactly "SUCCESS" before trusting the CN at all.
    ssl_client_certificate /etc/nginx/certs/cmux/client-ca.crt;
    ssl_verify_client      optional;

    client_max_body_size 256k;

    # The relay distinguishes the Mac agent from app devices by client-cert CN.
    # Always SET (never trust an inbound) X-Client-Cert-CN and
    # X-Client-Cert-Verify from the verified DN/verification result, so a
    # client cannot spoof either; the relay parses the CN= component and
    # requires Verify == "SUCCESS" before trusting any agent CN.
    location / {
        # If nginx is on a DIFFERENT host than the relay, proxy to the relay's
        # LAN address (e.g. http://192.168.1.160:8765) and set edge_token in the
        # relay config + the X-Edge-Token header below, so only nginx can drive
        # the relay. If nginx is local, http://127.0.0.1:8765 with no edge token.
        proxy_pass http://127.0.0.1:8765;

        proxy_http_version 1.1;
        proxy_set_header Upgrade              $http_upgrade;
        proxy_set_header Connection            $connection_upgrade;
        proxy_set_header Host                  $host;
        proxy_set_header X-Forwarded-For       $remote_addr;
        proxy_set_header X-Client-Cert-CN      $ssl_client_s_dn;      # relay parses CN=…
        proxy_set_header X-Client-Cert-Verify  $ssl_client_verify;    # SUCCESS | FAILED:<reason> | NONE
        # proxy_set_header X-Edge-Token        "CHANGE_ME_edge_secret";  # if edge_token is set

        # The agent tunnel (/agent/tunnel) and terminal/event WebSockets are
        # long-lived.
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
```

Also update the file's top-of-file comment, which currently ends with "this vhost's ssl_verify_client on stays mandatory for both the agent tunnel and all device traffic", to instead say: "this vhost's ssl_verify_client is optional — agents present a cert, self-service-paired devices don't — but the relay never trusts a CN without nginx also confirming X-Client-Cert-Verify: SUCCESS (see internal/relay/relay.go's verifiedAgentTenant)."

- [ ] **Step 6: Update the pre-existing tunnel-dialing tests to present a verified CN**

These three tests predate this plan and already set an agent CN to open a tunnel; once `handleTunnel` requires verification, they need the matching header too.

Replace `bridge/internal/relay/relay_test.go`'s tunnel-dial line inside `TestRelayEndToEndSessions`:

```go
	u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
	sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{
		"X-Client-Cert-Cn":     {"CN=agent:" + tenantID},
		"X-Client-Cert-Verify": {"SUCCESS"},
	})
```

Replace `bridge/internal/relay/multitenant_test.go`'s `dial` helper inside `TestRelayIsolatesTenants`:

```go
	dial := func(tenantID string, h http.Handler) {
		u := "ws" + strings.TrimPrefix(relayHTTP.URL, "http") + "/agent/tunnel"
		sess, err := tunnel.Dial(context.Background(), u, nil, http.Header{
			"X-Client-Cert-Cn":     {"CN=agent:" + tenantID},
			"X-Client-Cert-Verify": {"SUCCESS"},
		})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { sess.Close() })
		go func() { _ = http.Serve(sess, h) }()
	}
```

Replace `bridge/internal/relay/multitenant_test.go`'s tunnel-request header lines inside `TestRelayRevokedTenantCannotReconnectOrServeDevices`:

```go
	req, _ := http.NewRequest("GET", relayHTTP.URL+"/agent/tunnel", nil)
	req.Header.Set("X-Client-Cert-Cn", "CN=agent:"+tenantID)
	req.Header.Set("X-Client-Cert-Verify", "SUCCESS")
```

(`TestRelayTunnelRejectsWrongCN` and `TestRelayEdgeTokenGate` in `relay_test.go` both use a non-agent CN, `CN=phone` — `tenantFromAgentCN` already fails on that regardless of verification, so they need no change.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/relay/... -v`
Expected: PASS (all tests in the package — the pre-existing tunnel tests now present a verified CN and keep passing; the two new "rejects unverified" tests now correctly get 403; `TestDevicePair*` all pass)

- [ ] **Step 8: Commit**

```bash
git add bridge/internal/relay/relay.go bridge/internal/relay/pairing_test.go bridge/internal/relay/relay_test.go bridge/internal/relay/multitenant_test.go bridge/deploy/nginx-cmux-relay.conf
git commit -m "relay: add public /devices/pair endpoint; require verified (not just matching) agent CN"
```

---

### Task 9: `internal/relay` proxy forwards device identity header

**Files:**
- Modify: `bridge/internal/relay/proxy.go`
- Modify: `bridge/internal/relay/proxy_test.go`

**Interfaces:**
- Consumes: `auth.DeviceFromContext` (pre-existing), `Device.TokenHash` (Task 6).
- Produces: the proxy's `Director` now also sets `X-Device-ID: <TokenHash>` on every proxied request, so `internal/server`'s encryption layer (Tasks 11-13) can key its per-device shared secret without ever seeing the raw bearer token.

- [ ] **Step 1: Write the failing test**

Replace `bridge/internal/relay/proxy_test.go`'s `TestProxyForwardsAndInjectsRelayToken` in full with:

```go
func TestProxyForwardsAndInjectsRelayToken(t *testing.T) {
	c1, c2 := net.Pipe()
	agentSess, err := yamux.Server(c1, nil)
	if err != nil {
		t.Fatal(err)
	}
	relaySess, err := yamux.Client(c2, nil)
	if err != nil {
		t.Fatal(err)
	}
	gotTok := make(chan string, 1)
	gotDeviceID := make(chan string, 1)
	go func() {
		_ = http.Serve(agentSess, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotTok <- r.Header.Get("X-Relay-Token")
			gotDeviceID <- r.Header.Get("X-Device-ID")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
		}))
	}()

	store, err := auth.Open(t.TempDir() + "/proxy.db")
	if err != nil {
		t.Fatal(err)
	}
	tenantID, err := store.CreateTenant()
	if err != nil {
		t.Fatal(err)
	}
	devTok, err := store.Issue(tenantID, "phone", "test-device-pubkey-b64")
	if err != nil {
		t.Fatal(err)
	}
	dev, ok := store.Verify(devTok)
	if !ok {
		t.Fatal("issued token should verify")
	}

	reg := NewRegistry()
	reg.Set(tenantID, relaySess, nil)
	p := newProxy(reg, "relay-secret")
	handler := auth.Require(store, p)

	req := httptest.NewRequest("GET", "http://relay/sessions", nil)
	req.Header.Set("Authorization", "Bearer "+devTok)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("want 200, got %d (%s)", rr.Code, rr.Body.String())
	}
	if tok := <-gotTok; tok != "relay-secret" {
		t.Fatalf("X-Relay-Token not injected: %q", tok)
	}
	if id := <-gotDeviceID; id != dev.TokenHash {
		t.Fatalf("X-Device-ID = %q, want %q", id, dev.TokenHash)
	}
}
```

(`TestProxyOfflineReturns503` is unaffected — it never goes through `auth.Require`, so there is no `Device` in its request context, and `Director` simply skips setting `X-Device-ID` in that case, same as any pre-`auth.Require` request.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bridge && go test ./internal/relay/... -run TestProxyForwardsAndInjectsRelayToken -v`
Expected: FAIL — `gotDeviceID` never receives a value (or `id != dev.TokenHash`), since `X-Device-ID` isn't set yet.

- [ ] **Step 3: Implement the Director change**

Replace `bridge/internal/relay/proxy.go`'s `Director` field in full:

```go
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = "agent" // ignored by the stream dialer below
			req.Header.Set("X-Relay-Token", relayToken)
			if dev, ok := auth.DeviceFromContext(req.Context()); ok {
				req.Header.Set("X-Device-ID", dev.TokenHash)
			}
		},
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bridge && go test ./internal/relay/... -v`
Expected: PASS (all tests in the package)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/relay/proxy.go bridge/internal/relay/proxy_test.go
git commit -m "relay: inject X-Device-ID header for the agent-side encryption layer"
```

---
### Task 10: `internal/config` agent identity/session-store fields

**Files:**
- Modify: `bridge/internal/config/agent.go`
- Modify: `bridge/internal/config/agent_test.go`

**Interfaces:**
- Consumes: nothing new.
- Produces: `AgentConfig` gains `IdentityKey string` (`toml:"identity_key"`) and `SessionStore string` (`toml:"session_store"`), both defaulted and `expandHome`-d exactly like `ClientCert`/`ClientKey`/`CACert`.

- [ ] **Step 1: Write the failing tests**

Append to `bridge/internal/config/agent_test.go` (add `"strings"` to the import block):

```go
func TestLoadAgentDefaultsIdentityAndSessionStorePaths(t *testing.T) {
	cfg, err := LoadAgent(filepath.Join(t.TempDir(), "nope.toml"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.IdentityKey == "" || strings.Contains(cfg.IdentityKey, "~") {
		t.Fatalf("IdentityKey default not expanded: %q", cfg.IdentityKey)
	}
	if cfg.SessionStore == "" || strings.Contains(cfg.SessionStore, "~") {
		t.Fatalf("SessionStore default not expanded: %q", cfg.SessionStore)
	}
	if !strings.HasSuffix(cfg.IdentityKey, "identity.key") {
		t.Fatalf("IdentityKey = %q", cfg.IdentityKey)
	}
	if !strings.HasSuffix(cfg.SessionStore, "sessions.json") {
		t.Fatalf("SessionStore = %q", cfg.SessionStore)
	}
}

func TestLoadAgentParsesIdentityAndSessionStorePaths(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.toml")
	body := `
identity_key   = "/c/identity.key"
session_store  = "/c/sessions.json"
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadAgent(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.IdentityKey != "/c/identity.key" {
		t.Fatalf("IdentityKey = %q", cfg.IdentityKey)
	}
	if cfg.SessionStore != "/c/sessions.json" {
		t.Fatalf("SessionStore = %q", cfg.SessionStore)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/config/... -run TestLoadAgent -v`
Expected: FAIL — `cfg.IdentityKey`/`cfg.SessionStore` undefined.

- [ ] **Step 3: Implement the config changes**

Replace `bridge/internal/config/agent.go` in full:

```go
package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"github.com/BurntSushi/toml"
)

// AgentConfig is the Mac agent's configuration. The agent dials the relay and
// serves the cmux handler over the tunnel; it holds no device secrets other
// than its own e2e identity key.
type AgentConfig struct {
	CmuxBin    string `toml:"cmux_bin"`
	RelayURL   string `toml:"relay_url"`
	ClientCert string `toml:"client_cert"`
	ClientKey  string `toml:"client_key"`
	CACert     string `toml:"ca_cert"`
	RelayToken string `toml:"relay_token"`
	// BootstrapURL is the relay's no-mTLS registration endpoint
	// (e.g. https://cmux.example.com:8444/tenants/register), used exactly
	// once, on first run, when ClientCert/ClientKey/CACert don't exist yet.
	// The same bootstrap vhost also serves /devices/pair (see
	// cmd/cmux-bridge/pair.go), derived from this URL.
	BootstrapURL string `toml:"bootstrap_url"`
	// IdentityKey is the path to this agent's X25519 e2e identity private key
	// (internal/e2e.Identity), created on first use by `cmux-bridge
	// pair-device`.
	IdentityKey string `toml:"identity_key"`
	// SessionStore is the path to the JSON file holding this agent's paired
	// devices' e2e shared secrets and replay counters (internal/e2e.Store).
	SessionStore string `toml:"session_store"`
}

func agentDefaults() AgentConfig {
	return AgentConfig{
		CmuxBin:      "cmux",
		IdentityKey:  "~/.config/cmux-bridge/identity.key",
		SessionStore: "~/.config/cmux-bridge/sessions.json",
	}
}

// LoadAgent reads the agent TOML at path. A missing file yields defaults.
func LoadAgent(path string) (AgentConfig, error) {
	cfg := agentDefaults()
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := toml.Unmarshal(data, &cfg); err != nil {
			return cfg, fmt.Errorf("parse agent config %s: %w", path, err)
		}
	case errors.Is(err, fs.ErrNotExist):
		// Fall through with defaults.
	default:
		return cfg, fmt.Errorf("read agent config %s: %w", path, err)
	}
	if cfg.CmuxBin == "" {
		cfg.CmuxBin = "cmux"
	}
	cfg.ClientCert = expandHome(cfg.ClientCert)
	cfg.ClientKey = expandHome(cfg.ClientKey)
	cfg.CACert = expandHome(cfg.CACert)
	cfg.IdentityKey = expandHome(cfg.IdentityKey)
	cfg.SessionStore = expandHome(cfg.SessionStore)
	return cfg, nil
}
```

This restructures `LoadAgent`'s missing-file branch (`errors.Is(err, fs.ErrNotExist)`) to fall through into the same `expandHome` calls as the file-found branch, rather than returning early. Before this task, that early return was harmless because every `expandHome`d field defaulted to `""` (a no-op for `expandHome`); it would have silently returned a literal un-expanded `~/...` path now that `IdentityKey`/`SessionStore` have non-empty tilde defaults, so the restructure is required, not optional.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/config/... -v`
Expected: PASS (all tests in the package, including the pre-existing `TestLoadAgentParses`, `TestLoadAgentMissingFileDefaults`, `TestConfigRelayFields`)

- [ ] **Step 5: Commit**

```bash
git add bridge/internal/config/agent.go bridge/internal/config/agent_test.go
git commit -m "config: add agent identity_key/session_store fields for e2e pairing"
```

---
### Task 11: `internal/server` opt-in encryption layer (HTTP body plumbing)

**Files:**
- Create: `bridge/internal/server/encryption.go`
- Create: `bridge/internal/server/encryption_test.go`
- Modify: `bridge/internal/server/server.go`
- Modify: `bridge/internal/server/trusted.go`

**Interfaces:**
- Consumes: `e2e.Store.SharedSecret`, `e2e.Store.DecryptBody`, `e2e.Store.EncryptBody` (Task 4).
- Produces: `func (s *Server) SetSessions(sessions *e2e.Store)`, `func (s *Server) encryptionMiddleware(next http.Handler) http.Handler`. `TrustedHandler` wraps its whole route set in `encryptionMiddleware`; `Handler()` (the device-bearer path used only by tests) is untouched, per the Global Constraint against modifying it.

This task covers the 3 non-WebSocket trusted routes (`GET /sessions`, `GET /feed/pending`, `POST /feed/{id}/reply`) via a single generic body-encrypting middleware. `/terminal/{id}` and `/events` hijack the connection on WebSocket upgrade, so a generic body wrapper can't reach them — the middleware detects and skips any `Upgrade: websocket` request, and Tasks 12/13 add frame-level encryption to those two handlers directly.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/encryption_test.go`:

```go
package server

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/testutil"
)

// pairedSessions returns an e2e.Store with one device ("dev1-token-hash")
// already paired, plus its deviceID key and shared secret — mirroring how
// `cmux-bridge pair-device` (Task 15) populates the real store, without
// depending on that CLI.
func pairedSessions(t *testing.T) (sessions *e2e.Store, deviceID string, secret []byte) {
	t.Helper()
	agentPriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate agent key: %v", err)
	}
	devicePriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate device key: %v", err)
	}
	secret, err = e2e.DeriveSharedSecret(agentPriv, devicePriv.PublicKey())
	if err != nil {
		t.Fatalf("DeriveSharedSecret: %v", err)
	}
	sessions = e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))
	deviceID = "dev1-token-hash"
	if err := sessions.AddDevice(deviceID, devicePriv.PublicKey(), secret); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}
	return sessions, deviceID, secret
}

func TestEncryptionMiddlewarePassesThroughWhenSessionsNil(t *testing.T) {
	s := &Server{}
	called := false
	h := s.encryptionMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("plain"))
	}))
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/x", nil))
	if !called || rr.Body.String() != "plain" {
		t.Fatalf("expected untouched pass-through, got body=%q called=%v", rr.Body.String(), called)
	}
}

func TestEncryptionMiddlewareRejectsMissingDeviceID(t *testing.T) {
	sessions, _, _ := pairedSessions(t)
	s := &Server{sessions: sessions}
	h := s.encryptionMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler must not run without a valid X-Device-ID")
	}))
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/x", nil))
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rr.Code)
	}
}

func TestEncryptionMiddlewareRejectsUnknownDeviceID(t *testing.T) {
	// A present-but-unrecognized device id (e.g. this agent's local e2e
	// state was wiped) gets 409, distinct from the 401 for a wholly missing
	// header -- per the spec's error-handling section, the two should point
	// the app at different recovery UX (re-check auth vs. re-pair).
	sessions, _, _ := pairedSessions(t)
	s := &Server{sessions: sessions}
	h := s.encryptionMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler must not run for an unrecognized device id")
	}))
	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("X-Device-ID", "nope")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusConflict {
		t.Fatalf("want 409, got %d", rr.Code)
	}
}

func TestEncryptionMiddlewareDecryptsRequestEncryptsResponse(t *testing.T) {
	sessions, deviceID, secret := pairedSessions(t)
	s := &Server{sessions: sessions}
	var sawPlaintext []byte
	h := s.encryptionMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var err error
		sawPlaintext, err = io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("handler read body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))

	plaintextReq := []byte(`{"hello":"world"}`)
	ct, err := e2e.Seal(secret, e2e.Nonce(e2e.DirDeviceToAgent, 0), plaintextReq)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envelope, err := json.Marshal(struct {
		V  int    `json:"v"`
		N  uint64 `json:"n"`
		CT string `json:"ct"`
	}{V: 1, N: 0, CT: base64.StdEncoding.EncodeToString(ct)})
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}

	req := httptest.NewRequest("POST", "/x", bytes.NewReader(envelope))
	req.Header.Set("X-Device-ID", deviceID)
	req.ContentLength = int64(len(envelope))
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)

	if string(sawPlaintext) != string(plaintextReq) {
		t.Fatalf("handler saw %q, want %q", sawPlaintext, plaintextReq)
	}
	if rr.Code != http.StatusOK {
		t.Fatalf("want 200, got %d", rr.Code)
	}
	if strings.Contains(rr.Body.String(), `"ok"`) {
		t.Fatalf("response body must be encrypted, not plaintext: %s", rr.Body.String())
	}
	respPlain, err := sessions.DecryptBody(deviceID, rr.Body.Bytes())
	if err != nil {
		t.Fatalf("DecryptBody on response: %v", err)
	}
	if string(respPlain) != `{"ok":true}` {
		t.Fatalf("decrypted response = %q", respPlain)
	}
}

func TestEncryptionMiddlewareSkipsWebSocketUpgrade(t *testing.T) {
	sessions, deviceID, _ := pairedSessions(t)
	s := &Server{sessions: sessions}
	called := false
	h := s.encryptionMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	}))
	req := httptest.NewRequest("GET", "/terminal/x", nil)
	req.Header.Set("X-Device-ID", deviceID)
	req.Header.Set("Upgrade", "websocket")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	if !called {
		t.Fatal("a WebSocket upgrade request must reach the handler untouched, not be body-encrypted")
	}
}

func TestTrustedHandlerEncryptsSessionsWhenSessionsSet(t *testing.T) {
	script := "#!/bin/sh\ncat <<'JSON'\n" + fakeWorkspaceList + "\nJSON\n"
	bin := testutil.WriteFakeCmux(t, script)
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("X-Relay-Token", relayTok)
	req.Header.Set("X-Device-ID", deviceID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "workspaces") {
		t.Fatalf("response must be encrypted, saw plaintext: %s", raw)
	}
	var env struct {
		CT string `json:"ct"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	ct, err := base64.StdEncoding.DecodeString(env.CT)
	if err != nil {
		t.Fatalf("decode ct: %v", err)
	}
	plain, err := e2e.Open(secret, e2e.Nonce(e2e.DirAgentToDevice, 0), ct)
	if err != nil {
		t.Fatalf("decrypt response: %v", err)
	}
	if !strings.Contains(string(plain), "882CA6F0") {
		t.Fatalf("decrypted response missing expected workspace: %s", plain)
	}
}

func TestTrustedHandlerStillPlaintextWhenSessionsUnset(t *testing.T) {
	// Regression guard for the Global Constraint that encryption is strictly
	// opt-in: a TrustedHandler that never calls SetSessions must behave
	// exactly as before this feature existed.
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\necho '{}'\n")
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/sessions", nil)
	req.Header.Set("X-Relay-Token", relayTok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), "workspaces") {
		t.Fatalf("expected plaintext workspaces JSON, got: %s", raw)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run 'TestEncryptionMiddleware|TestTrustedHandlerEncrypts|TestTrustedHandlerStillPlaintext' -v`
Expected: FAIL — `Server{}.sessions` field / `encryptionMiddleware` / `SetSessions` undefined.

- [ ] **Step 3: Add the `sessions` field**

Replace `bridge/internal/server/server.go` in full:

```go
// Package server exposes the bridge's HTTP/WebSocket API. Every route except
// /pair requires a device bearer token (auth.Require); the public edge adds
// mTLS in front of all of it.
package server

import (
	"net/http"
	"time"

	"github.com/sodre90/cmux-bridge/internal/auth"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
)

// Server holds the dependencies shared by all handlers.
type Server struct {
	cfg          config.Config
	cmux         *cmux.Client
	store        *auth.Store
	hub          *hub
	terminalPoll time.Duration // how often WS /terminal re-replays for output
	// sessions is nil unless SetSessions is called (only by runAgent's
	// production wiring). Nil means the plaintext code path every existing
	// test exercises; non-nil enables the opt-in e2e encryption layer.
	sessions *e2e.Store
}

// New constructs a Server.
func New(cfg config.Config, c *cmux.Client, s *auth.Store) *Server {
	return &Server{
		cfg:          cfg,
		cmux:         c,
		store:        s,
		hub:          newHub(),
		terminalPoll: 250 * time.Millisecond,
	}
}

// Handler returns the fully-wired HTTP handler (device-bearer auth on every
// route; the public edge adds mTLS in front).
func (s *Server) Handler() http.Handler {
	return s.routes(s.authWrap)
}
```

- [ ] **Step 4: Implement the middleware**

Create `bridge/internal/server/encryption.go`:

```go
package server

import (
	"bytes"
	"io"
	"net/http"
	"strings"

	"github.com/sodre90/cmux-bridge/internal/e2e"
)

// SetSessions enables the opt-in e2e content-encryption layer. Called only by
// runAgent's production wiring (Task 14); no test calls this, so every
// pre-existing test continues to exercise the plaintext code path unchanged.
func (s *Server) SetSessions(sessions *e2e.Store) { s.sessions = sessions }

func writeEncryptionErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"error":"` + msg + `"}`))
}

// encryptionMiddleware transparently decrypts an e2e-enveloped request body
// and encrypts the response body, keyed by the X-Device-ID header the
// relay's proxy Director injects (internal/relay/proxy.go). A request with
// no X-Device-ID, or one the session store doesn't recognize, is rejected
// before reaching the wrapped handler — once encryption is enabled there is
// no plaintext fallback (see the Global Constraint on full enforcement).
// WebSocket upgrade requests (/terminal/{id}, /events) pass through
// untouched: they hijack the connection, so a generic body wrapper can't
// reach them, and they carry their own frame-level encryption (Tasks 12-13).
func (s *Server) encryptionMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.sessions == nil || strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			next.ServeHTTP(w, r)
			return
		}
		deviceID := r.Header.Get("X-Device-ID")
		if deviceID == "" {
			// Shouldn't happen given the proxy always sets it, but defensively:
			// per the spec's error-handling section, a missing header gets the
			// generic "unknown_device" 401, distinct from the 409 below for a
			// present-but-unrecognized device (e.g. this agent's local e2e
			// state was wiped) -- the two point the app at different recovery
			// UX (re-check auth vs. re-pair).
			writeEncryptionErr(w, http.StatusUnauthorized, "unknown_device")
			return
		}
		if _, ok := s.sessions.SharedSecret(deviceID); !ok {
			writeEncryptionErr(w, http.StatusConflict, "not_paired")
			return
		}

		if r.ContentLength != 0 && r.Body != nil {
			envelope, err := io.ReadAll(r.Body)
			if err != nil {
				writeEncryptionErr(w, http.StatusBadRequest, "read_failed")
				return
			}
			if len(envelope) > 0 {
				plaintext, err := s.sessions.DecryptBody(deviceID, envelope)
				if err != nil {
					writeEncryptionErr(w, http.StatusBadRequest, "decrypt_failed")
					return
				}
				r.Body = io.NopCloser(bytes.NewReader(plaintext))
				r.ContentLength = int64(len(plaintext))
			}
		}

		rec := &encryptingResponseWriter{ResponseWriter: w, deviceID: deviceID, sessions: s.sessions}
		next.ServeHTTP(rec, r)
		rec.flush()
	})
}

// encryptingResponseWriter buffers a handler's plaintext response and
// encrypts it as a single e2e envelope on flush, rather than encrypting each
// Write call separately — every handler behind this middleware writes one
// JSON body in one call, so this keeps envelope-counter usage at exactly one
// increment per response.
type encryptingResponseWriter struct {
	http.ResponseWriter
	deviceID string
	sessions *e2e.Store
	buf      bytes.Buffer
	status   int
}

func (w *encryptingResponseWriter) WriteHeader(status int) { w.status = status }

func (w *encryptingResponseWriter) Write(p []byte) (int, error) { return w.buf.Write(p) }

func (w *encryptingResponseWriter) flush() {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	envelope, err := w.sessions.EncryptBody(w.deviceID, w.buf.Bytes())
	if err != nil {
		writeEncryptionErr(w.ResponseWriter, http.StatusInternalServerError, "encrypt_failed")
		return
	}
	w.ResponseWriter.Header().Set("Content-Type", "application/json")
	w.ResponseWriter.WriteHeader(w.status)
	_, _ = w.ResponseWriter.Write(envelope)
}
```

- [ ] **Step 5: Wire the middleware into `TrustedHandler`**

Replace `bridge/internal/server/trusted.go`'s `TrustedHandler` function in full:

```go
// TrustedHandler is the handler the Mac agent serves over the relay tunnel:
// device-bearer auth is replaced by the relay-token check, and the opt-in
// e2e encryption layer (SetSessions) wraps the whole route set.
func (s *Server) TrustedHandler(relayToken string) http.Handler {
	base := s.routes(func(h http.Handler) http.Handler {
		return RequireRelayToken(relayToken, h)
	})
	return s.encryptionMiddleware(base)
}
```

(`routes`, `RequireRelayToken`, and `authWrap` are unchanged — only `TrustedHandler`'s body changes, wrapping its previous return value in `s.encryptionMiddleware`.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS (all tests in the package — every pre-existing test still goes through `Handler()` or a `TrustedHandler()` that never calls `SetSessions`, so `s.sessions` stays `nil` and `encryptionMiddleware` is a no-op pass-through for all of them)

- [ ] **Step 7: Commit**

```bash
git add bridge/internal/server/encryption.go bridge/internal/server/encryption_test.go bridge/internal/server/server.go bridge/internal/server/trusted.go
git commit -m "server: add opt-in e2e HTTP body encryption layer for TrustedHandler"
```

---
### Task 12: `internal/server` terminal WebSocket encryption

**Files:**
- Modify: `bridge/internal/server/terminal.go`
- Create: `bridge/internal/server/terminal_encryption_test.go`

**Interfaces:**
- Consumes: `e2e.Store.SharedSecret`, `e2e.Store.EncryptFrame`, `e2e.Store.DecryptFrame` (Task 5); `pairedSessions` test helper (Task 11).
- Produces: `handleTerminal` rejects (before upgrading) a request missing/carrying an unknown `X-Device-ID` once `s.sessions` is set; frames become binary e2e envelopes instead of JSON text frames on that connection.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/terminal_encryption_test.go`:

```go
package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/testutil"
)

func wsConnectEncrypted(t *testing.T, srvURL, path, relayTok, deviceID string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + path
	h := http.Header{"X-Relay-Token": {relayTok}, "X-Device-ID": {deviceID}}
	c, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial %s failed (status %d): %v", path, code, err)
	}
	return c
}

func TestTerminalReplayEncryptedWhenSessionsSet(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsConnectEncrypted(t, srv.URL, "/terminal/SURF1", relayTok, deviceID)
	defer c.Close()

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	msgType, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if msgType != websocket.BinaryMessage {
		t.Fatalf("want a binary (encrypted) frame, got message type %d", msgType)
	}
	counter, plain, err := e2e.DecodeFrame(secret, e2e.DirAgentToDevice, raw)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if counter != 0 {
		t.Fatalf("want first frame counter 0, got %d", counter)
	}
	var down TerminalDown
	if err := json.Unmarshal(plain, &down); err != nil {
		t.Fatalf("unmarshal decrypted frame: %v", err)
	}
	if down.Type != "replay" || down.Columns != 80 || down.Rows != 24 {
		t.Fatalf("unexpected decrypted frame: %+v", down)
	}
}

func TestTerminalInputDispatchedWhenEncrypted(t *testing.T) {
	logPath := t.TempDir() + "/cmux.log"
	t.Setenv("CMUX_FAKE_LOG", logPath)
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsConnectEncrypted(t, srv.URL, "/terminal/SURF1", relayTok, deviceID)
	defer c.Close()

	// Drain the initial encrypted replay frame.
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := c.ReadMessage(); err != nil {
		t.Fatal(err)
	}

	upBytes, err := json.Marshal(TerminalUp{Type: "input", Text: "ls\r"})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := e2e.EncodeFrame(secret, e2e.DirDeviceToAgent, 0, upBytes)
	if err != nil {
		t.Fatalf("EncodeFrame: %v", err)
	}
	if err := c.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(logPath)
		if strings.Contains(string(data), "mobile.terminal.input") &&
			strings.Contains(string(data), "SURF1") &&
			strings.Contains(string(data), "ls") {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	data, _ := os.ReadFile(logPath)
	t.Fatalf("input rpc not dispatched; log:\n%s", data)
}

func TestTerminalRejectsMissingDeviceIDWhenEncrypted(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, fakeTerminalScript)
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, _, _ := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/terminal/SURF1"
	h := http.Header{"X-Relay-Token": {relayTok}}
	_, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err == nil {
		t.Fatal("expected dial to fail without X-Device-ID once encryption is enabled")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401, got %v", resp)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestTerminal -v`
Expected: FAIL — `TestTerminalReplayEncryptedWhenSessionsSet` and `TestTerminalInputDispatchedWhenEncrypted` get plaintext JSON text frames instead of binary encrypted ones (the dial itself succeeds since `handleTerminal` doesn't yet check `X-Device-ID`); `TestTerminalRejectsMissingDeviceIDWhenEncrypted`'s dial unexpectedly succeeds.

- [ ] **Step 3: Implement the terminal encryption**

Replace `bridge/internal/server/terminal.go` in full:

```go
package server

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

// TerminalDown is a server->client terminal message. Grid carries the cmux
// render-grid object (format "cmux.render-grid.v1") verbatim; the app renders it
// as a styled cell grid.
type TerminalDown struct {
	Type    string          `json:"type"` // "replay" | "output"
	Grid    json.RawMessage `json:"grid,omitempty"`
	Columns int             `json:"columns,omitempty"`
	Rows    int             `json:"rows,omitempty"`
	Seq     int             `json:"seq,omitempty"`
}

// TerminalUp is a client->server terminal message.
type TerminalUp struct {
	Type    string `json:"type"` // "input" | "paste" | "resize"
	Text    string `json:"text,omitempty"`
	Columns int    `json:"columns,omitempty"`
	Rows    int    `json:"rows,omitempty"`
}

func (s *Server) handleTerminal(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		http.Error(w, "missing surface id", http.StatusBadRequest)
		return
	}
	var deviceID string
	if s.sessions != nil {
		// Status codes match internal/server/encryption.go's
		// encryptionMiddleware: a missing header is 401 unknown_device, a
		// present-but-unrecognized device id is 409 not_paired (see the
		// spec's error-handling section) — the two point the app at
		// different recovery UX.
		deviceID = r.Header.Get("X-Device-ID")
		if deviceID == "" {
			http.Error(w, "unknown_device", http.StatusUnauthorized)
			return
		}
		if _, ok := s.sessions.SharedSecret(deviceID); !ok {
			http.Error(w, "not_paired", http.StatusConflict)
			return
		}
	}
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	start := time.Now()
	log.Printf("terminal %s: connected", id)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// Initial full replay.
	fr, err := s.fetchReplay(ctx, id)
	if err != nil {
		log.Printf("terminal %s: initial replay failed after %s: %v", id, time.Since(start), err)
		return
	}
	fr.Type = "replay"
	_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := s.writeTerminalFrame(c, deviceID, fr); err != nil {
		log.Printf("terminal %s: initial write failed after %s: %v", id, time.Since(start), err)
		return
	}
	// cmux's top-level seq (and render_grid.state_seq) is always 0, so we can't
	// gate on it — instead we forward whenever the render-grid bytes change.
	lastGrid := fr.Grid

	// Read loop (client input) runs in its own goroutine; it only reads.
	go s.terminalReadLoop(ctx, cancel, c, id, deviceID)

	// Output poll loop is the sole writer after the initial replay.
	t := time.NewTicker(s.terminalPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Printf("terminal %s: closed after %s", id, time.Since(start))
			return
		case <-t.C:
			next, err := s.fetchReplay(ctx, id)
			if err != nil {
				log.Printf("terminal %s: poll replay failed after %s: %v", id, time.Since(start), err)
				return
			}
			if bytes.Equal(next.Grid, lastGrid) {
				continue
			}
			lastGrid = next.Grid
			next.Type = "output"
			_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := s.writeTerminalFrame(c, deviceID, next); err != nil {
				log.Printf("terminal %s: output write failed after %s: %v", id, time.Since(start), err)
				return
			}
		}
	}
}

// writeTerminalFrame sends fr as a plain JSON text frame when encryption is
// disabled (s.sessions == nil), or as a binary e2e-encrypted frame otherwise.
func (s *Server) writeTerminalFrame(c *websocket.Conn, deviceID string, fr TerminalDown) error {
	if s.sessions == nil {
		return c.WriteJSON(fr)
	}
	raw, err := json.Marshal(fr)
	if err != nil {
		return err
	}
	frame, err := s.sessions.EncryptFrame(deviceID, raw)
	if err != nil {
		return err
	}
	return c.WriteMessage(websocket.BinaryMessage, frame)
}

func (s *Server) terminalReadLoop(ctx context.Context, cancel context.CancelFunc, c *websocket.Conn, id, deviceID string) {
	defer cancel()
	for {
		var up TerminalUp
		if s.sessions == nil {
			if err := c.ReadJSON(&up); err != nil {
				log.Printf("terminal %s: read loop ended: %v", id, err)
				return
			}
		} else {
			_, raw, err := c.ReadMessage()
			if err != nil {
				log.Printf("terminal %s: read loop ended: %v", id, err)
				return
			}
			plain, err := s.sessions.DecryptFrame(deviceID, raw)
			if err != nil {
				log.Printf("terminal %s: decrypt failed: %v", id, err)
				return
			}
			if err := json.Unmarshal(plain, &up); err != nil {
				log.Printf("terminal %s: bad frame json: %v", id, err)
				return
			}
		}
		switch up.Type {
		case "input":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.input",
				map[string]any{"surface_id": id, "text": up.Text})
		case "paste":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.paste",
				map[string]any{"surface_id": id, "text": up.Text})
		case "resize":
			_, _ = s.cmux.Rpc(ctx, "mobile.terminal.viewport",
				map[string]any{"surface_id": id, "columns": up.Columns, "rows": up.Rows})
		}
	}
}

// fetchReplay calls mobile.terminal.replay and returns a TerminalDown (Type
// unset) holding the render grid and dimensions.
func (s *Server) fetchReplay(ctx context.Context, id string) (TerminalDown, error) {
	raw, err := s.cmux.Rpc(ctx, "mobile.terminal.replay",
		map[string]any{"surface_id": id})
	if err != nil {
		return TerminalDown{}, err
	}
	var top struct {
		Columns    int             `json:"columns"`
		Rows       int             `json:"rows"`
		Seq        int             `json:"seq"`
		RenderGrid json.RawMessage `json:"render_grid"`
	}
	if err := json.Unmarshal(raw, &top); err != nil {
		return TerminalDown{}, err
	}
	return TerminalDown{
		Grid:    top.RenderGrid,
		Columns: top.Columns,
		Rows:    top.Rows,
		Seq:     top.Seq,
	}, nil
}
```

This changes `terminalReadLoop`'s third parameter from the narrow `interface{ ReadJSON(any) error }` to the concrete `*websocket.Conn` (needed for the new `ReadMessage` call in the encrypted branch) — no test constructs a fake implementer of that interface directly (confirmed: `terminal_test.go` only ever drives `handleTerminal` through a real `websocket.Conn` via `wsConnect`), so this is a safe, uncalled-out signature narrowing.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -run 'TestTerminal' -v`
Expected: PASS (all `TestTerminal*` tests, both the pre-existing plaintext ones from `terminal_test.go` — unaffected, since `s.sessions` stays `nil` for `newTestServer`-constructed servers — and the new encrypted-path ones)

- [ ] **Step 5: Run the full server package test suite**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS (all tests in the package)

- [ ] **Step 6: Commit**

```bash
git add bridge/internal/server/terminal.go bridge/internal/server/terminal_encryption_test.go
git commit -m "server: encrypt terminal WebSocket frames when e2e sessions are set"
```

---
### Task 13: `internal/server` events WebSocket encryption

**Files:**
- Modify: `bridge/internal/server/events.go`
- Create: `bridge/internal/server/events_encryption_test.go`

**Interfaces:**
- Consumes: `e2e.Store.SharedSecret`, `e2e.Store.EncryptFrame` (Task 5); `pairedSessions` test helper (Task 11).
- Produces: `handleEvents` rejects (before upgrading) a request missing/carrying an unknown `X-Device-ID` once `s.sessions` is set; broadcast frames become binary e2e envelopes instead of JSON text frames on that connection. `/events` is server→client only, so unlike `/terminal/{id}` there is no client-authored frame to decrypt — the existing read goroutine, which only drains the socket to detect client close, is unchanged.

- [ ] **Step 1: Write the failing tests**

Create `bridge/internal/server/events_encryption_test.go`:

```go
package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/testutil"
)

func wsDialEncrypted(t *testing.T, srvURL, relayTok, deviceID string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(srvURL, "http") + "/events"
	h := http.Header{"X-Relay-Token": {relayTok}, "X-Device-ID": {deviceID}}
	c, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		t.Fatalf("ws dial failed (status %d): %v", code, err)
	}
	return c
}

func TestEventsBroadcastEncryptedWhenSessionsSet(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\necho '{}'\n")
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, deviceID, secret := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	c := wsDialEncrypted(t, srv.URL, relayTok, deviceID)
	defer c.Close()
	time.Sleep(100 * time.Millisecond) // let the handler register with the hub

	s.hub.broadcast(EventFrame{Type: "feed", FeedID: "X", NeedsAttention: true})

	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	msgType, raw, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if msgType != websocket.BinaryMessage {
		t.Fatalf("want binary (encrypted) frame, got type %d", msgType)
	}
	counter, plain, err := e2e.DecodeFrame(secret, e2e.DirAgentToDevice, raw)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if counter != 0 {
		t.Fatalf("want counter 0, got %d", counter)
	}
	var got EventFrame
	if err := json.Unmarshal(plain, &got); err != nil {
		t.Fatalf("unmarshal decrypted frame: %v", err)
	}
	if got.FeedID != "X" || !got.NeedsAttention {
		t.Fatalf("unexpected decrypted frame: %+v", got)
	}
}

func TestEventsRejectsMissingDeviceIDWhenEncrypted(t *testing.T) {
	bin := testutil.WriteFakeCmux(t, "#!/bin/sh\necho '{}'\n")
	s := New(config.Config{}, &cmux.Client{Bin: bin}, nil)
	sessions, _, _ := pairedSessions(t)
	s.SetSessions(sessions)

	const relayTok = "relay-secret"
	srv := httptest.NewServer(s.TrustedHandler(relayTok))
	defer srv.Close()

	u := "ws" + strings.TrimPrefix(srv.URL, "http") + "/events"
	h := http.Header{"X-Relay-Token": {relayTok}}
	_, resp, err := websocket.DefaultDialer.Dial(u, h)
	if err == nil {
		t.Fatal("expected dial to fail without X-Device-ID once encryption is enabled")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401, got %v", resp)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bridge && go test ./internal/server/... -run TestEvents -v`
Expected: FAIL — `TestEventsBroadcastEncryptedWhenSessionsSet` gets a plaintext JSON text frame instead of a binary encrypted one; `TestEventsRejectsMissingDeviceIDWhenEncrypted`'s dial unexpectedly succeeds.

- [ ] **Step 3: Implement the events encryption**

Replace `bridge/internal/server/events.go`'s `handleEvents` function, and add one helper right after it. Everything else in `events.go` (`classify`, `needsAttention`, `attentionLabel`, `enrichTitle`, `ingestEvents`, `RunEvents`, the `hub` type and its methods, `upgrader`, `str`, `firstNonEmpty`) is unchanged.

```go
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	var deviceID string
	if s.sessions != nil {
		// Status codes match internal/server/encryption.go's
		// encryptionMiddleware and handleTerminal: a missing header is 401
		// unknown_device, a present-but-unrecognized device id is 409
		// not_paired (see the spec's error-handling section).
		deviceID = r.Header.Get("X-Device-ID")
		if deviceID == "" {
			http.Error(w, "unknown_device", http.StatusUnauthorized)
			return
		}
		if _, ok := s.sessions.SharedSecret(deviceID); !ok {
			http.Error(w, "not_paired", http.StatusConflict)
			return
		}
	}
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer c.Close()
	ch := s.hub.register()
	defer s.hub.unregister(ch)

	// Drain/await client close so a dead socket unblocks the writer.
	go func() {
		for {
			if _, _, err := c.ReadMessage(); err != nil {
				c.Close()
				return
			}
		}
	}()

	for f := range ch {
		_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := s.writeEventFrame(c, deviceID, f); err != nil {
			return
		}
	}
}

// writeEventFrame sends f as a plain JSON text frame when encryption is
// disabled (s.sessions == nil), or as a binary e2e-encrypted frame otherwise.
func (s *Server) writeEventFrame(c *websocket.Conn, deviceID string, f EventFrame) error {
	if s.sessions == nil {
		return c.WriteJSON(f)
	}
	raw, err := json.Marshal(f)
	if err != nil {
		return err
	}
	frame, err := s.sessions.EncryptFrame(deviceID, raw)
	if err != nil {
		return err
	}
	return c.WriteMessage(websocket.BinaryMessage, frame)
}
```

`events.go` already imports `"encoding/json"` and `"github.com/gorilla/websocket"` (used by `classify`/`ingestEvents` and `upgrader`/`handleEvents` respectively) — no import changes needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bridge && go test ./internal/server/... -run TestEvents -v`
Expected: PASS (all `TestEvents*`/`TestWSEvents*`/`TestIngestEvents*` tests, both pre-existing plaintext ones — unaffected, `s.sessions` stays `nil` for `newTestServer`-constructed servers — and the new encrypted-path ones)

- [ ] **Step 5: Run the full server package test suite**

Run: `cd bridge && go test ./internal/server/... -v`
Expected: PASS (all tests in the package — this is the last Task 11-13 checkpoint the Global Constraint about leaving every pre-existing test unmodified and passing applies to; confirm it explicitly here)

- [ ] **Step 6: Commit**

```bash
git add bridge/internal/server/events.go bridge/internal/server/events_encryption_test.go
git commit -m "server: encrypt events WebSocket frames when e2e sessions are set"
```

---
### Task 14: Wire `e2e.Store` into `cmux-bridge agent`

**Files:**
- Modify: `bridge/cmd/cmux-bridge/agent.go`

**Interfaces:**
- Consumes: `e2e.OpenStore` (Task 3), `Server.SetSessions` (Task 11), `cfg.SessionStore` (Task 10).
- Produces: nothing new — this is pure wiring.

`runAgent` itself never needs the agent's e2e *identity* (only `cmux-bridge pair-device`, Task 15, derives shared secrets with it) — it only needs the already-populated `e2e.Store` of paired devices' shared secrets, to decrypt/encrypt traffic on already-paired connections. `e2e.OpenStore` never errors (it lazily creates the backing file on first write — see Task 3), so there is nothing to handle here beyond the two-line wire-up.

No test is added for this step: `runAgent` has no existing test harness (confirmed via repo grep — no `agent_test.go` exists under `cmd/cmux-bridge`; it dials real network sockets and installs OS signal handlers), and this exact `SetSessions` + `TrustedHandler` combination is already covered end-to-end by Task 11's `TestTrustedHandlerEncryptsSessionsWhenSessionsSet`. Adding a parallel test harness just for these two lines would duplicate that coverage without exercising anything `runAgent`-specific.

- [ ] **Step 1: Wire `SetSessions` into `runAgent`**

Modify `bridge/cmd/cmux-bridge/agent.go`'s import block — add the `e2e` import:

```go
import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/sodre90/cmux-bridge/internal/cli"
	"github.com/sodre90/cmux-bridge/internal/cmux"
	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/server"
	"github.com/sodre90/cmux-bridge/internal/tunnel"
)
```

Replace `bridge/cmd/cmux-bridge/agent.go`'s `runAgent` body from `srv := server.New(...)` through `handler := srv.TrustedHandler(cfg.RelayToken)` (inclusive) with:

```go
	srv := server.New(config.Config{}, &cmux.Client{Bin: cfg.CmuxBin}, nil)
	srv.SetSessions(e2e.OpenStore(cfg.SessionStore))
	go srv.RunEvents(ctx)
	handler := srv.TrustedHandler(cfg.RelayToken)
```

(Everything before and after this block in `runAgent` — the config load, `ensureRegistered`, TLS load, the reconnect loop — is unchanged.)

- [ ] **Step 2: Verify the build**

Run: `cd bridge && go build ./...`
Expected: succeeds with no errors.

- [ ] **Step 3: Run the full test suite as a regression check**

Run: `cd bridge && go test ./... -v`
Expected: PASS (all tests, unaffected by this wiring-only change — no test exercises `cmd/cmux-bridge` directly)

- [ ] **Step 4: Commit**

```bash
git add bridge/cmd/cmux-bridge/agent.go
git commit -m "cmd/cmux-bridge: wire e2e session store into the running agent"
```

---
### Task 15: `cmux-bridge pair-device` CLI command

**Files:**
- Create: `bridge/cmd/cmux-bridge/pair.go`
- Create: `bridge/cmd/cmux-bridge/pair_test.go`
- Modify: `bridge/cmd/cmux-bridge/main.go`
- Modify: `bridge/go.mod`, `bridge/go.sum` (via `go get`/`go mod tidy`)

**Interfaces:**
- Consumes: `e2e.LoadOrCreateIdentity`, `e2e.Identity` (Task 1); `e2e.DeriveSharedSecret` (Task 2); `e2e.OpenStore`, `Store.AddDevice` (Task 3); `loadTLS` (pre-existing in `agent.go`, confirmed 3-arg `(certPath, keyPath, caPath string) (*tls.Config, error)`); `cfg.RelayURL`, `cfg.BootstrapURL`, `cfg.ClientCert/ClientKey/CACert`, `cfg.IdentityKey`, `cfg.SessionStore` (Task 10); the relay's `POST /agent/pairing-code` / `GET /agent/pairing-code/{code}` (Task 7).
- Produces: `func pairDevice(client *http.Client, agentBase, devicePairURL string, identity *e2e.Identity, sessions *e2e.Store, out io.Writer, pollPeriod time.Duration, deadline time.Time) error` (the testable core, isolated from CLI/network/TLS concerns), `func runPairDevice(args []string) int` (the thin CLI wrapper `main.go` dispatches to).

The relay's two agent-facing pairing endpoints (Task 7) live on the **main mTLS vhost** (the same one `agent_tunnel` uses — reachable only with the agent's own client cert), derived here from `cfg.RelayURL`. The public `/devices/pair` endpoint (Task 8) the *phone* calls lives on the **bootstrap vhost** (no cert required), derived here from `cfg.BootstrapURL` — this CLI never calls that endpoint itself; it only needs its URL to embed in the QR code payload for the phone to use.

- [ ] **Step 1: Add the qrterminal dependency**

Run: `cd bridge && go get github.com/mdp/qrterminal/v3@v3.2.1 && go mod tidy`
Expected: `go.mod`/`go.sum` updated with `github.com/mdp/qrterminal/v3 v3.2.1` as a direct dependency.

- [ ] **Step 2: Write the failing tests**

Create `bridge/cmd/cmux-bridge/pair_test.go`:

```go
package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sodre90/cmux-bridge/internal/e2e"
)

func testDevicePubkeyB64(t *testing.T) string {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate device key: %v", err)
	}
	return base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
}

// fakePairingRelay serves the two agent-facing pairing endpoints pairDevice
// calls. The GET poll handler returns 500 for the first failFirstN polls
// (simulating a transient relay hiccup), reports "redeemed":false until the
// redeemAfter'th poll, and "redeemed":true from then on. polls counts total
// GET calls for the test to assert retry/poll-count behavior.
func fakePairingRelay(t *testing.T, code, devicePubkeyB64 string, redeemAfter, failFirstN int) (*httptest.Server, *int32) {
	t.Helper()
	var polls int32
	mux := http.NewServeMux()
	mux.HandleFunc("POST /agent/pairing-code", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "expires_at": "2099-01-01T00:00:00Z", "tenant_id": "fake-tenant-id"})
	})
	mux.HandleFunc("GET /agent/pairing-code/"+code, func(w http.ResponseWriter, r *http.Request) {
		n := int(atomic.AddInt32(&polls, 1))
		if n <= failFirstN {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if n < redeemAfter {
			_ = json.NewEncoder(w).Encode(map[string]any{"redeemed": false})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"redeemed":      true,
			"device_pubkey": devicePubkeyB64,
			"token_hash":    "fake-token-hash",
		})
	})
	return httptest.NewServer(mux), &polls
}

func TestPairDeviceStopsOnRedemption(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	srv, polls := fakePairingRelay(t, "CODE1234", devicePub, 2, 0)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(2*time.Second))
	if err != nil {
		t.Fatalf("pairDevice: %v", err)
	}
	if got := atomic.LoadInt32(polls); got < 2 {
		t.Fatalf("expected pairDevice to poll at least twice before redemption, got %d", got)
	}
	if _, ok := sessions.SharedSecret("fake-token-hash"); !ok {
		t.Fatal("expected pairDevice to persist a shared secret for the redeemed device")
	}
}

func TestPairDeviceRetriesOnTransientError(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	// Poll #1 and #2 return 500; poll #3 reports redeemed.
	srv, polls := fakePairingRelay(t, "CODE1234", devicePub, 1, 2)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(2*time.Second))
	if err != nil {
		t.Fatalf("pairDevice: %v", err)
	}
	if got := atomic.LoadInt32(polls); got < 3 {
		t.Fatalf("expected pairDevice to survive 2 transient errors and keep polling, got %d polls", got)
	}
}

func TestPairDeviceTimesOut(t *testing.T) {
	devicePub := testDevicePubkeyB64(t)
	// redeemAfter is unreachably far given the short deadline below.
	srv, _ := fakePairingRelay(t, "CODE1234", devicePub, 1000000, 0)
	defer srv.Close()

	identity, err := e2e.LoadOrCreateIdentity(filepath.Join(t.TempDir(), "identity.key"))
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	sessions := e2e.OpenStore(filepath.Join(t.TempDir(), "sessions.json"))

	err = pairDevice(srv.Client(), srv.URL, "https://phone.example/devices/pair", identity, sessions, io.Discard, 10*time.Millisecond, time.Now().Add(50*time.Millisecond))
	if err == nil {
		t.Fatal("expected pairDevice to time out")
	}
}

func TestHttpsBaseFromRelayURL(t *testing.T) {
	cases := map[string]string{
		"wss://cmux.example.com/agent/tunnel": "https://cmux.example.com",
		"ws://localhost:8765/agent/tunnel":    "http://localhost:8765",
	}
	for in, want := range cases {
		got, err := httpsBaseFromRelayURL(in)
		if err != nil {
			t.Fatalf("httpsBaseFromRelayURL(%q): %v", in, err)
		}
		if got != want {
			t.Fatalf("httpsBaseFromRelayURL(%q) = %q, want %q", in, got, want)
		}
	}
	if _, err := httpsBaseFromRelayURL("not-a-url-with-bad-scheme://x"); err == nil {
		t.Fatal("expected an error for a non-ws(s) scheme")
	}
}

func TestDevicePairURLFromBootstrap(t *testing.T) {
	got, err := devicePairURLFromBootstrap("https://cmux.example.com:8444/tenants/register")
	if err != nil {
		t.Fatal(err)
	}
	if want := "https://cmux.example.com:8444/devices/pair"; got != want {
		t.Fatalf("devicePairURLFromBootstrap = %q, want %q", got, want)
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd bridge && go test ./cmd/cmux-bridge/... -run 'TestPairDevice|TestHttpsBaseFromRelayURL|TestDevicePairURLFromBootstrap' -v`
Expected: FAIL — `pairDevice`, `httpsBaseFromRelayURL`, `devicePairURLFromBootstrap` undefined.

- [ ] **Step 4: Implement `pair.go`**

Create `bridge/cmd/cmux-bridge/pair.go`:

```go
package main

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/mdp/qrterminal/v3"

	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
)

// pairingQR is the JSON payload rendered into the QR code. The phone scans
// it, generates its own e2e keypair, and POSTs PairURL directly — it needs
// nothing else to complete self-service pairing.
type pairingQR struct {
	PairURL     string `json:"pair_url"`
	Code        string `json:"code"`
	AgentPubkey string `json:"agent_pubkey"` // base64 X25519 public key
	ExpiresAt   string `json:"expires_at"`
	TenantID    string `json:"tenant_id"` // informational only -- /devices/pair never needs it in the request
}

// httpsBaseFromRelayURL converts the agent's wss:// tunnel URL
// (e.g. "wss://cmux.example.com/agent/tunnel") into the https base the same
// mTLS vhost serves the agent-facing pairing-code endpoints on
// (e.g. "https://cmux.example.com").
func httpsBaseFromRelayURL(relayURL string) (string, error) {
	u, err := url.Parse(relayURL)
	if err != nil {
		return "", fmt.Errorf("parse relay_url: %w", err)
	}
	switch u.Scheme {
	case "wss":
		u.Scheme = "https"
	case "ws":
		u.Scheme = "http"
	default:
		return "", fmt.Errorf("relay_url must be ws:// or wss://, got %q", u.Scheme)
	}
	u.Path = ""
	return u.String(), nil
}

// devicePairURLFromBootstrap converts the agent's bootstrap registration URL
// (e.g. "https://cmux.example.com:8444/tenants/register") into the no-mTLS
// device-pairing endpoint the same bootstrap vhost serves
// (e.g. "https://cmux.example.com:8444/devices/pair").
func devicePairURLFromBootstrap(bootstrapURL string) (string, error) {
	u, err := url.Parse(bootstrapURL)
	if err != nil {
		return "", fmt.Errorf("parse bootstrap_url: %w", err)
	}
	u.Path = "/devices/pair"
	return u.String(), nil
}

func requestPairingCode(client *http.Client, agentBase string) (code, expiresAt, tenantID string, err error) {
	resp, err := client.Post(agentBase+"/agent/pairing-code", "application/json", nil)
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", "", fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var body struct {
		Code      string `json:"code"`
		ExpiresAt string `json:"expires_at"`
		TenantID  string `json:"tenant_id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", "", "", err
	}
	return body.Code, body.ExpiresAt, body.TenantID, nil
}

func pollPairingCode(client *http.Client, agentBase, code string) (devicePubkey, tokenHash string, redeemed bool, err error) {
	resp, err := client.Get(agentBase + "/agent/pairing-code/" + code)
	if err != nil {
		return "", "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", false, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var body struct {
		Redeemed     bool   `json:"redeemed"`
		DevicePubkey string `json:"device_pubkey"`
		TokenHash    string `json:"token_hash"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", "", false, err
	}
	return body.DevicePubkey, body.TokenHash, body.Redeemed, nil
}

// pairDevice runs one pairing session: request a fresh pairing code from the
// relay, render it (with the agent's e2e public key and the phone's
// no-cert pairing URL) as a QR code to out, then poll agentBase until the
// code is redeemed or deadline passes. On redemption it derives the shared
// secret with the redeeming device and persists it to sessions, keyed by the
// device's token hash — the same key the relay's proxy Director injects as
// X-Device-ID on every subsequent request from that device.
func pairDevice(client *http.Client, agentBase, devicePairURL string, identity *e2e.Identity, sessions *e2e.Store, out io.Writer, pollPeriod time.Duration, deadline time.Time) error {
	code, expiresAt, tenantID, err := requestPairingCode(client, agentBase)
	if err != nil {
		return fmt.Errorf("request pairing code: %w", err)
	}

	qr := pairingQR{
		PairURL:     devicePairURL,
		Code:        code,
		AgentPubkey: base64.StdEncoding.EncodeToString(identity.PublicKey().Bytes()),
		ExpiresAt:   expiresAt,
		TenantID:    tenantID,
	}
	qrJSON, err := json.Marshal(qr)
	if err != nil {
		return fmt.Errorf("marshal QR payload: %w", err)
	}
	fmt.Fprintf(out, "Scan this QR code with the cmux app (code expires %s):\n\n", expiresAt)
	qrterminal.GenerateHalfBlock(string(qrJSON), qrterminal.L, out)
	fmt.Fprintf(out, "\nOr enter this code manually: %s\n\n", code)

	for {
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for pairing code %s to be redeemed", code)
		}
		devicePubkeyB64, tokenHash, redeemed, err := pollPairingCode(client, agentBase, code)
		if err != nil {
			// Transient relay errors (network blip, brief 5xx) shouldn't abort
			// the whole pairing attempt -- keep polling until the deadline.
			fmt.Fprintf(out, "poll error (will retry): %v\n", err)
			time.Sleep(pollPeriod)
			continue
		}
		if !redeemed {
			time.Sleep(pollPeriod)
			continue
		}
		devicePubkeyRaw, err := base64.StdEncoding.DecodeString(devicePubkeyB64)
		if err != nil {
			return fmt.Errorf("decode device pubkey: %w", err)
		}
		devicePub, err := ecdh.X25519().NewPublicKey(devicePubkeyRaw)
		if err != nil {
			return fmt.Errorf("parse device pubkey: %w", err)
		}
		secret, err := e2e.DeriveSharedSecret(identity.Priv, devicePub)
		if err != nil {
			return fmt.Errorf("derive shared secret: %w", err)
		}
		if err := sessions.AddDevice(tokenHash, devicePub, secret); err != nil {
			return fmt.Errorf("persist paired device: %w", err)
		}
		fmt.Fprintf(out, "Device paired successfully.\n")
		return nil
	}
}

func runPairDevice(args []string) int {
	fs := flag.NewFlagSet("pair-device", flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	timeout := fs.Duration("timeout", 5*time.Minute, "how long to wait for the phone to scan and redeem the code")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	if cfg.RelayURL == "" || cfg.BootstrapURL == "" {
		log.Printf("pair-device: relay_url and bootstrap_url are both required")
		return 1
	}
	agentBase, err := httpsBaseFromRelayURL(cfg.RelayURL)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	devicePairURL, err := devicePairURLFromBootstrap(cfg.BootstrapURL)
	if err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	tlsCfg, err := loadTLS(cfg.ClientCert, cfg.ClientKey, cfg.CACert)
	if err != nil {
		log.Printf("pair-device: tls: %v", err)
		return 1
	}
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}}

	identity, err := e2e.LoadOrCreateIdentity(cfg.IdentityKey)
	if err != nil {
		log.Printf("pair-device: e2e identity: %v", err)
		return 1
	}
	sessions := e2e.OpenStore(cfg.SessionStore)

	if err := pairDevice(client, agentBase, devicePairURL, identity, sessions, os.Stdout, 2*time.Second, time.Now().Add(*timeout)); err != nil {
		log.Printf("pair-device: %v", err)
		return 1
	}
	return 0
}
```

The full import block for this file is exactly: `crypto/ecdh`, `encoding/base64`, `encoding/json`, `flag`, `fmt`, `io`, `log`, `net/http`, `net/url`, `os`, `time`, `github.com/mdp/qrterminal/v3`, `github.com/sodre90/cmux-bridge/internal/config`, `github.com/sodre90/cmux-bridge/internal/e2e`.

- [ ] **Step 5: Wire `pair-device` into `main.go`**

Replace `bridge/cmd/cmux-bridge/main.go` in full:

```go
package main

import (
	"fmt"
	"os"

	"github.com/sodre90/cmux-bridge/internal/version"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "agent":
		os.Exit(runAgent(os.Args[2:]))
	case "pair-device":
		os.Exit(runPairDevice(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-bridge", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-bridge <agent|pair-device|version> [flags]")
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd bridge && go test ./cmd/cmux-bridge/... -v`
Expected: PASS (all tests, including the new `pair_test.go` ones)

- [ ] **Step 7: Run the full test suite as a regression check**

Run: `cd bridge && go build ./... && go test ./... -v`
Expected: `go build` succeeds. All tests PASS across every package.

- [ ] **Step 8: Commit**

```bash
git add bridge/cmd/cmux-bridge/pair.go bridge/cmd/cmux-bridge/pair_test.go bridge/cmd/cmux-bridge/main.go bridge/go.mod bridge/go.sum
git commit -m "cmd/cmux-bridge: add pair-device QR self-service pairing CLI"
```

---
### Task 16: Update `bridge/README.md`

**Files:**
- Modify: `bridge/README.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Update the podman pairing example**

Replace `bridge/README.md:106-110` (currently ending with the `podman exec cmux-relay cmux-relay pair ...` instructions):

```markdown
The image builds on the host (don't ship it across architectures). The port is
published on loopback only; the device store persists in the `relay-data`
volume. Pair devices by running `cmux-bridge pair-device` on the Mac agent
(see [Pair a device](#pair-a-device) below) — the relay side needs no manual
step.
```

- [ ] **Step 2: Rewrite the "Pair a device" section**

Replace `bridge/README.md:169-212` (the entire `## Pair a device` section, from its header through the paragraph ending "...not yet implemented — see [...multi-tenant-relay-design.md]") with:

```markdown
## Pair a device

Pairing is self-service now — no operator step, and no hand-rolled `.p12`
client certificate. On the **home server**, once the Mac agent has
registered (see [Agent client certificate](#agent-client-certificate)
above):

```bash
cmux-bridge pair-device --config ~/.config/cmux-bridge/agent.toml
```

This asks the relay for a fresh, single-use pairing code, then prints a QR
code (and the code itself, for manual entry) to the terminal:

```
Scan this QR code with the cmux app (code expires 2026-07-02T15:32:00Z):

█▀▀▀▀▀█ ▀▄█▀▀▄██ █▀▀▀▀▀█
█ ███ █ █▀▄ ▀▀▄█ █ ███ █
█ ▀▀▀ █ █▄▄▀▀▄▀█ █ ▀▀▀ █
▀▀▀▀▀▀▀ █▄▀ ▀ █▄▀ ▀▀▀▀▀▀▀

Or enter this code manually: 7F3K9QRT
```

The QR payload carries a one-time pairing URL, the code, and the agent's
public key. The app (see the separate Android QR-scanning work) scans it,
generates its own keypair, and calls the relay directly to redeem the code —
no client certificate needed for that call. `pair-device` polls in the
background and, once the phone redeems the code, derives a shared secret
with the device (X25519 + HKDF) and saves it locally: content sent between
this agent and that device is now end-to-end encrypted, so the relay
operator (or anyone who compromises the relay host) can route messages but
not read them.

`pair-device` never displays a raw device token to the operator — only the
phone that scanned the QR code ever sees it. List/revoke devices and tenants
exactly as before:

```bash
cmux-relay devices                # list devices (tokens redacted)
cmux-relay devices revoke <token>
cmux-relay tenants list            # created/revoked per tenant
cmux-relay tenants revoke <id>     # devices stop authenticating immediately;
                                   # the agent is refused on its next reconnect
```

Note: revocation is checked live on every connect/request, so new agent-tunnel
connects and all device authentication are blocked immediately. It does not,
however, forcibly close an agent that is already connected — that agent's
existing tunnel and its push-monitor goroutine keep running until the
connection ends on its own (a network blip, the agent process restarting, or
the relay itself restarting).

There is no manual-pairing fallback: `auth.Issue` always requires a device
public key, so a phone paired under the old `cmux-relay pair` flow loses
relay access the moment this ships and must be re-paired via `pair-device`.
```

- [ ] **Step 3: Fix the now-inaccurate nginx mTLS description**

Replace `bridge/README.md`'s "Edge: nginx mutual TLS" section (originally at lines 214-219) — specifically the sentence describing `ssl_verify_client on` as required:

```markdown
## Edge: nginx mutual TLS

See `deploy/nginx-cmux-relay.conf`. Point your home-server DNS name at nginx,
accept an optional client certificate (`ssl_verify_client optional` — agents
present one, self-service-paired devices don't; the relay tells them apart by
CN), and `proxy_pass` to `http://127.0.0.1:8765`. The `map $http_upgrade
$connection_upgrade` block (http context) is required for the agent tunnel
and the terminal/event WebSockets.
```

- [ ] **Step 4: Verify the anchors and command names are consistent**

Run: `grep -n 'cmux-relay pair\b' bridge/README.md`
Expected: no output (the old command name no longer appears anywhere in the file).

Run: `grep -n 'pair-device' bridge/README.md`
Expected: at least two matches (the podman section and the "Pair a device" section).

- [ ] **Step 5: Commit**

```bash
git add bridge/README.md
git commit -m "docs: document self-service QR pairing, replace the old pair CLI instructions"
```

---
