# HoneyShare Threat Model (Initial Draft)

## 1. Scope & System Boundaries

HoneyShare transports opaque binary files between registered, verified users via an S3-compatible object store and a MySQL-backed Spring Boot control plane.

### In Scope
- Control-plane APIs: registration via invitation, authentication, password reset, email verification, MFA.
- Session lifecycle and token security.
- Transfer state machine, authorization, and quota accounting.
- S3 pre-signed URL generation, scope, and lifetime.
- Recipient authorization.
- Deletion and expiration verification.

### Out of Scope (MVP)
- Physical server or infrastructure host tampering.
- Client device malware / compromised operating system.
- Third-party certificate authority compromise.
- Denial of service attacks saturating public network transit.

---

## 2. Threat Actors & Capabilities

| Threat Actor | Motivations | Capabilities |
|---|---|---|
| **Unauthenticated External Attacker** | Account creation, brute forcing credentials, data scraping | Internet-facing HTTP requests, timing analysis, credential stuffing |
| **Malicious Authenticated User** | Unauthorized data access, quota exhaustion, denial of service | Access with valid credentials, manipulating transfer IDs, forging requests |
| **Unauthorized Recipient** | Intercepting files not intended for them | Knows or guesses transfer ID |
| **Compromised Database / Insider** | Access to persisted secrets, read stored tokens | Read access to MySQL tables |
| **Malicious File Sender** | Exploiting recipient or server via malicious files | Uploads trojans, malware, malicious filenames |

---

## 3. Threat Analysis & Mitigations

### 3.1 Account Enumeration & Brute Force
- **Threat:** Attacker probes registration, login, or password reset endpoints to discover registered email addresses.
- **Mitigation:**
  - Registration requires a high-entropy, single-use, time-limited invitation token.
  - Login returns a generic `"Invalid email or password"` error regardless of whether the email exists.
  - Password reset returns `"If this email is registered, a password reset link has been sent"` identically for valid and invalid emails.
  - Rate limiting via token buckets (Bucket4j) on login, reset, registration, and MFA endpoints.

### 3.2 Token Compromise via Database Leak
- **Threat:** A database backup or SQL injection leak exposes active sessions, invitations, or verification tokens.
- **Mitigation:**
  - Raw tokens are generated with 256 bits of CSPRNG entropy (`SecureTokens`).
  - Only SHA-256 hex hashes of session tokens, invitation tokens, verification tokens, and reset tokens are persisted.
  - Raw tokens are transmitted over TLS once and never logged or stored.

### 3.3 Unauthorized File Access (IDOR)
- **Threat:** Attacker with a valid user account guesses or observes a Transfer ID belonging to other users.
- **Mitigation:**
  - Transfer IDs alone provide zero authorization.
  - The control plane enforces strict role and ownership checks: only the sender or explicitly designated, email-verified recipients may request download authorizations.
  - Pre-signed S3 download URLs are strictly scoped to the exact object key and have a short TTL (default 300 seconds).

### 3.4 Quota Exhaustion & Race Conditions
- **Threat:** Attacker starts multiple concurrent uploads to exceed the 200 GB quota limit.
- **Mitigation:**
  - Quota is reserved atomically in MySQL before upload sessions are initialized.
  - Concurrent upload requests check cumulative active reservations + committed storage.
  - Incomplete or abandoned uploads are reclaimed by reconciliation jobs.

### 3.5 Malicious Filenames & Untrusted Content
- **Threat:** Attacker uploads files with path traversal names (e.g. `../../etc/passwd`) or executable scripts served inline to execute XSS.
- **Mitigation:**
  - S3 object keys are generated as random opaque keys (`transfers/{transferId}/...`), not derived from user filenames.
  - User-supplied original filename is treated strictly as opaque display text with validation against path separators and control characters.
  - Content-Type hints are untrusted; files are never served inline with executable MIME types on the authenticated origin.

### 3.6 End-to-End Encryption (E2E) Truth in Advertising
- **Threat:** Users expect E2E protection before the cryptographic protocol has been formally reviewed and tested.
- **Mitigation:**
  - E2E mode is disabled by default via feature flag (`honeyshare.features.e2e-enabled=false`).
  - The system does not claim E2E security until key management, device revocation, and recovery protocols undergo formal review and test verification.

