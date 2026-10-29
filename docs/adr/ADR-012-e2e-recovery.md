# ADR-012: User-Controlled E2E Key Recovery and Encrypted Recovery Packages

## Status
Accepted

## Context
In zero-knowledge End-to-End Encryption (E2E), private keys reside exclusively on user endpoints. If a user loses their registered device, upgrades their hardware, or experiences OS corruption, their encrypted transfers would normally be unrecoverable.

Many platforms solve this by escrowing recovery keys on the server or linking encryption keys to the user's account password. However, this introduces severe vulnerabilities:
1. Anyone who initiates a password reset (e.g. via compromised email or SMS) can hijack the user's E2E private keys.
2. The server or its operators become an attractive target for subpoenas or breach, as they hold the means to decrypt user files.
3. It violates the core HoneyShare principle: **The server must not implement a hidden recovery bypass.**

## Decision
We implement a **100% client-side, user-controlled recovery protocol** complying with Specification Section 3.3 and FR-SEC-09:

1. **User Recovery Secret:** During E2E onboarding, the client application generates a 24-word BIP-39 mnemonic or 128-bit high-entropy secret. The user is instructed to store this offline (e.g. written on paper or in a hardware password manager).
2. **Client-Side Key Derivation (Argon2id):**
   - The client derives a 256-bit symmetric recovery key $K_{rec} = \text{Argon2id}(\text{passphrase}, \text{salt}, t=3, m=65536\,\text{KiB}, p=1)$.
   - Argon2id provides strong resistance against GPU and ASIC brute-force attacks.
3. **Encrypted Recovery Package:**
   - The client encrypts the user's device private keys or master identity keys using $AES-256-GCM$ with $K_{rec}$ and a fresh 96-bit nonce.
   - The client uploads only the resulting ciphertext and public KDF parameters (salt, iteration count, memory limit) to `POST /api/v1/users/me/e2e-recovery`.
4. **Server Storage & Invariants:**
   - The server stores the encrypted recovery package in `user_e2e_recovery_packages`.
   - The server inspects payloads and strictly rejects unencrypted private keys (`PRIVATE KEY`, `BEGIN EC PRIVATE`).
   - The server NEVER receives, derives, or stores the recovery secret, passphrase, or $K_{rec}$.
5. **Separation from Password Reset:**
   - Password reset via email verification updates only the user's authentication credentials (`users.password_hash`).
   - A password reset does NOT touch, reset, or decrypt the E2E recovery package.
6. **Recovery Restoration Workflow:**
   - On a new or restored device, the user signs in and enters their 24-word recovery secret.
   - The client fetches the encrypted package and salt via `GET /api/v1/users/me/e2e-recovery`.
   - The client executes Argon2id locally to derive $K_{rec}$, decrypts the package, and enrolls the new device.
7. **Unrecoverable State Warning:**
   - The application explicitly warns users that if they lose all active devices AND lose their recovery secret, their E2E content cannot be recovered by anyone, including HoneyShare administrators.

## Consequences
### Positive
- True zero-knowledge: The backend cannot decrypt user recovery packages under any circumstances.
- Password resets cannot be weaponized to steal E2E keys.
- Robust against offline brute-force attacks via memory-hard Argon2id parameters.

### Negative / Trade-offs
- No administrator recovery: If a user loses their recovery secret and all devices, files cannot be restored.
- Requires user responsibility to securely store their recovery secret offline.

