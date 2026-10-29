# HoneyShare End-to-End Encryption (E2E) Protocol & Key Management Specification

**Status:** Draft / Feature-Flagged (`honeyshare.features.e2e-enabled=false`)  
**Specification Reference:** Sections 3.3, 5 (FR-SEC-03 to FR-SEC-09), 9.4, 9.5, 12.2, 18 Gate 5  

---

## 1. Overview and Core Invariants

HoneyShare supports two distinct privacy modes:
1. **Standard Mode:** Server-side managed encryption at rest and in transit.
2. **End-to-End (E2E) Mode:** Zero-knowledge client-side encryption.

### Non-Negotiable Server Security Invariants
1. **No Plaintext Symmetric Keys:** The server NEVER generates, receives, computes, stores, or logs plaintext file encryption keys ($K_{file}$).
2. **No Device Private Keys:** The server stores ONLY device public keys (`devices.public_key_b64`). Private keys are generated and retained exclusively within client hardware/OS secure enclaves (Apple Secure Enclave, Android StrongBox/Keystore, Windows DPAPI / TPM).
3. **No Plaintext Recovery Secrets:** The server stores only client-encrypted recovery packages (`user_e2e_recovery_packages`) and public KDF parameters (salt, iteration count, memory limit). The server NEVER possesses or evaluates the user's recovery secret.
4. **No Hidden Recovery Bypass:** Password resets through email verification reset authentication credentials only; they CANNOT and MUST NOT silently decrypt or reconstruct E2E keys. If a user loses all active devices and their recovery secret, their E2E files are cryptographically unrecoverable.
5. **Feature Flag Gate:** In accordance with Specification Gate 5, all E2E operations (`Transfer` creation in `E2E` mode, key envelope storage/retrieval, recovery package enrollment) are guarded by `honeyshare.features.e2e-enabled`. When `false`, the server rejects E2E operations with `403 Forbidden` (`E2E_FEATURE_DISABLED`).

---

## 2. Cryptographic Primitives & Standards

HoneyShare relies exclusively on standard, vetted cryptographic primitives. Custom cryptography is strictly forbidden.

| Purpose | Algorithm / Primitive | Standard / Reference |
|---|---|---|
| **Asymmetric Key Agreement** | X25519 (Curve25519) | RFC 7748 |
| **Key Derivation Function (KDF)** | HKDF-SHA256 | RFC 5869 |
| **Envelope Wrapping** | AES-256-GCM (128-bit tag, 96-bit random nonce) | NIST SP 800-38D |
| **Payload Symmetric Cipher** | AES-256-GCM / XChaCha20-Poly1305 | NIST SP 800-38D / RFC 8439 |
| **Recovery Key Derivation** | Argon2id ($t=3, m=65536\,\text{KiB}, p=1$) | RFC 9106 |
| **Integrity & Checksums** | SHA-256 (streaming) | FIPS 180-4 |

---

## 3. Protocol Workflows

### 3.1 Device Registration & Key Enrollment
1. Client generates an X25519 keypair ($priv_{device}, pub_{device}$) within the platform's secure storage.
2. Client registers the device by calling `POST /api/v1/users/me/devices` with `deviceName`, `platform`, and `publicKeyB64` (Base64-encoded Curve25519 raw public key).
3. The server inspects the public key material and rejects any private key indicators (`PRIVATE KEY`, `BEGIN EC PRIVATE`).
4. The server assigns a ULID `deviceId` and records the active device in the database.

### 3.2 E2E Transfer Creation & Key Wrapping
When sender Alice sends an E2E transfer to recipient Bob:
1. **Device Key Discovery:** Alice's client calls `GET /api/v1/users/{bobUserId}/devices/active` to fetch Bob's active device public keys ($pub_{bob\_dev}$).
2. **Data Key Generation:** Alice's client generates a fresh, 256-bit symmetric file encryption key ($K_{file}$) using a cryptographically secure pseudorandom number generator (CSPRNG).
3. **Payload Encryption:** Alice's client chunks the file into parts (default 32 MiB) and encrypts each chunk using $AES-256-GCM$ with $K_{file}$ and a distinct 96-bit nonce ($IV_{chunk} = \text{base\_iv} \oplus \text{chunk\_index}$).
4. **Key Envelope Generation:** For each active recipient device:
   - Alice generates an ephemeral keypair ($priv_{eph}, pub_{eph}$).
   - Alice computes the shared secret $SS = \text{X25519}(priv_{eph}, pub_{bob\_dev})$.
   - Alice derives the wrapping key $K_{wrap} = \text{HKDF-SHA256}(SS, \text{salt}=\text{transferId}, \text{info}="HoneyShare-E2E-KeyWrap")$.
   - Alice encrypts $K_{file}$ using $AES-256-GCM$ with $K_{wrap}$ to yield $C_{key}$.
5. **Publish Transfer & Envelopes:**
   - Alice calls `POST /api/v1/transfers` with `privacyMode: "E2E"`.
   - Alice uploads the wrapped key envelopes to `POST /api/v1/transfers/{transferId}/e2e-envelopes`:
     - `recipientDeviceId`: Bob's device ID
     - `senderDeviceId`: Alice's device ID
     - `ephemeralPublicKeyB64`: $pub_{eph}$
     - `encryptedKeyB64`: $C_{key}$
     - `keyAlgorithm`: `"X25519-HKDF-AES256GCM"`
6. **Encrypted Upload:** Alice uploads encrypted chunks via standard resumable upload sessions (`POST /api/v1/upload-sessions/{id}/chunks/{index}`). The server stores only ciphertext.

### 3.3 Recipient Decryption
When recipient Bob downloads the transfer:
1. Bob's client creates a download session: `POST /api/v1/transfers/{transferId}/download-sessions`.
2. Bob's client retrieves the wrapped key envelope for his active device: `GET /api/v1/transfers/{transferId}/e2e-envelopes/{bobDeviceId}`.
3. Bob's client computes the shared secret $SS = \text{X25519}(priv_{bob\_dev}, pub_{eph})$.
4. Bob's client derives $K_{wrap} = \text{HKDF-SHA256}(SS, \text{salt}=\text{transferId}, \text{info}="HoneyShare-E2E-KeyWrap")$.
5. Bob's client unwraps $C_{key}$ to obtain $K_{file}$.
6. Bob's client streams and decrypts each chunk locally, verifying the authenticated GCM tag and calculating the payload SHA-256 checksum.

### 3.4 Device Revocation
1. A user can revoke a lost or retired device via `POST /api/v1/users/me/devices/{deviceId}/revoke`.
2. The server sets `revoked_at = CURRENT_TIMESTAMP(6)`.
3. Revocation is immediately enforced:
   - The revoked device can no longer retrieve key envelopes (`getTransferKeyEnvelope` returns `403 RECIPIENT_NOT_AUTHORIZED`).
   - Senders cannot wrap new transfers for revoked devices (`getActiveDeviceKeys` excludes revoked devices).
4. *Limitation Disclosure:* Revoking a device cannot wipe files or keys that were already downloaded to that device prior to revocation.

### 3.5 User-Controlled Recovery Package
1. During E2E setup, the client displays a 24-word recovery mnemonic or high-entropy passphrase to the user, instructing them to store it offline.
2. The client derives a recovery key $K_{rec} = \text{Argon2id}(\text{passphrase}, \text{salt}, t=3, m=65536, p=1)$.
3. The client encrypts the device private key or master root key using $AES-256-GCM$ with $K_{rec}$.
4. The client transmits the encrypted package and public KDF parameters to the server: `POST /api/v1/users/me/e2e-recovery`.
5. On a new device, the user enters their recovery secret. The client fetches the package (`GET /api/v1/users/me/e2e-recovery`), performs Argon2id locally, decrypts the keys, and enrolls the new device.
6. The server never observes the recovery secret, passphrase, or derived key.

---

## 4. Metadata Protection & Leakage Boundary

E2E encryption protects file contents and filenames when wrapped. However, the HoneyShare backend requires certain operational metadata for routing, retention, and quota accounting:
- **Exposed to Server:**
  - Transfer size in bytes (`file_size_bytes`) — required for atomic 200 GB quota accounting.
  - Sender user ID and recipient email addresses — required for access control and inbox routing.
  - Number and size of chunks uploaded — required for resumable upload orchestration.
  - Upload completion timestamp and expiry clock (24h retention) — required for automated cleanup.
  - Ciphertext SHA-256 checksum — required to verify chunk transmission integrity between client and storage.
- **Protected by E2E:**
  - File plaintext contents.
  - Embedded file metadata, internal headers, and structure.

---

## 5. Security & Threat Model

| Threat Actor | Capabilities | HoneyShare Mitigation |
|---|---|---|
| **Compromised Storage / S3 / MinIO** | Full read access to storage buckets | Storage contains only AES-256-GCM ciphertext chunks. Decryption requires $K_{file}$, which is stored only in recipient-wrapped envelopes. |
| **Compromised MySQL Database** | Full read access to database tables | Database contains only wrapped envelopes ($C_{key}$) and device public keys. Decryption requires device private keys held exclusively in client secure hardware. |
| **Malicious Server Administrator** | Code inspection, database queries, storage dumps | Server holds no plaintext keys, private keys, or recovery secrets. Cannot decrypt file transfers without compromising a client device. |
| **Compromised Recipient Device** | Full device access | Recipient can decrypt transfers intended for them. Revoking the device prevents future transfers from being wrapped for it. |
| **Man-in-the-Middle (MITM)** | Network traffic interception | TLS 1.3 encryption in transit; end-to-end payload encryption prevents reading payload even if TLS termination is intercepted. |

---

## 6. Review & Release Readiness Checklist (Gate 5)

- [x] Documented formal threat model and protocol workflows.
- [x] Standard primitives selected (X25519, HKDF-SHA256, AES-256-GCM, Argon2id).
- [x] MySQL schema migrated (`devices`, `transfer_key_envelopes`, `user_e2e_recovery_packages`).
- [x] Server-side invariant enforcement: rejection of private key PEMs in device & recovery package registration.
- [x] Device revocation blocks immediate envelope access.
- [x] Zero server-side plaintext key exposure verified by automated adversarial tests (`E2eSecurityAdversarialTest`).
- [x] Feature flag enforced: default `honeyshare.features.e2e-enabled=false`.
- [ ] Complete desktop/web client implementation with secure local storage (Phase 6).
- [ ] Independent third-party cryptographic audit prior to production `e2e-enabled=true`.

