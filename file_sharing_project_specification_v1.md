# Reliable File-Sharing Platform
## Agent-Ready Product Requirements, Architecture Decisions, and Implementation Plan

**Specification version:** 1.0  
**Status:** Initial build specification  
**Primary purpose:** Give an AI coding agent a single source of truth for planning and implementing the project.  
**Product type:** Cross-platform, server-backed file-sharing and delivery system  
**Initial deployment:** Invitation-only small private beta  
**Primary backend language:** Java  
**Metadata database:** MySQL  

---

## 0. Instructions to the AI coding agent

Treat this document as the product's source of truth.

1. **Do not implement the entire system in one giant change.** Work in small, reviewable phases and keep the application runnable after each phase.
2. Before coding, inspect the repository and report its current structure, existing code, and constraints. Do not overwrite useful existing work without explaining why.
3. Produce a short implementation plan and identify any assumption that would materially affect security, data integrity, or migration compatibility.
4. Prefer the simplest architecture that satisfies the requirements. Do not introduce microservices, Kubernetes, Kafka, or other operationally expensive infrastructure without a demonstrated need.
5. Use Java and MySQL for the backend and metadata persistence. Use database migrations, not manual undocumented schema changes.
6. Never invent a security guarantee. Document the threat model and distinguish tested guarantees from intended behavior.
7. Do not write custom cryptographic algorithms. Use vetted libraries and standard protocols. E2E key management must receive a focused design review before production use.
8. Never log passwords, session tokens, MFA secrets, recovery keys, file contents, encryption keys, or presigned URLs.
9. Large-file handling must be streaming and resumable. Do not load an entire file into backend memory.
10. Use explicit states, idempotent operations, checksums, reconciliation, and visible error reporting. Do not silently mark a transfer complete.
11. Write unit, integration, security, and failure-recovery tests as features are implemented.
12. If a cloud provider or credential is not available, implement against a documented storage interface and a local development implementation. Do not fabricate a successful production deployment.
13. Maintain `README.md`, setup instructions, environment-variable documentation, architecture decisions, and a list of known limitations.
14. When a requirement is ambiguous, follow the **Resolved Defaults** section in this document rather than repeatedly asking the product owner. Record the assumption in `docs/assumptions.md`.
15. Do not claim 100 GB support, E2E security, safe deletion, or disaster recovery until the relevant acceptance tests pass.

---

# 1. Product summary

Build a secure, dependable platform that allows a registered user to send files to another registered user, even when the recipient is offline or the sender temporarily loses connectivity.

The platform must support arbitrary file types as opaque binary content, resumable large-file transfers, persistent queues, integrity verification, configurable retention, background transfer processing, and two privacy modes:

1. **Standard encryption mode:** TLS in transit and encryption at rest; authorized server-side services may access plaintext where the product requires it.
2. **End-to-end encryption (E2E) mode:** content is encrypted on the sender's device and decrypted only by authorized recipient clients. The server stores ciphertext and must not possess enough key material to independently decrypt the content.

The service is server-backed; peer-to-peer transfer is not required. Content flows from the sender to remote object storage, then from object storage to the recipient.

## 1.1 Core product promise

The system must make transfer state truthful and observable:

- An interrupted transfer can resume from verified progress.
- A recipient can receive a file after being offline.
- A file is never marked verified unless the relevant integrity checks pass.
- Retried requests do not create duplicate logical transfers or corrupt data.
- The system surfaces permanent failures instead of silently losing pending work.
- Sender-side automatic deletion is optional and may occur only after the configured remote-storage durability condition has been met and acknowledged.

**Important limitation:** The MVP uses redundant storage in one region. It cannot promise survival of every regional disaster. A separately protected metadata backup does not back up file content.

## 1.2 Out of scope for the initial MVP

- Anonymous sharing and public share links.
- Peer-to-peer transfers.
- Public, unrestricted self-registration.
- Cross-region file replication.
- Guaranteed infinite file sizes, storage, or bandwidth.
- File preview, indexing, content search, or server-side antivirus scanning for E2E ciphertext.
- Cross-user deduplication.
- Microservices and a dedicated message broker unless later justified by measured scale.
- Enterprise compliance certifications unless separately planned and audited.

---

# 2. Adopted product decisions

These decisions are accepted for the MVP.

| Area | Decision |
|---|---|
| Platforms | Web, desktop, and mobile clients |
| Backend | Java with Spring Boot |
| Database | MySQL |
| Storage | S3-compatible object storage, redundant within one region |
| Delivery model | Server-only; no P2P |
| Sharing | Registered users only |
| Onboarding | Invitation-only |
| Authentication | Email + password; MFA optional for regular users and mandatory for administrators |
| Email verification | Required before accessing files |
| Privacy | Standard server-readable mode and E2E mode |
| E2E recovery | User-controlled recovery key; server stores encrypted recovery material only |
| Maximum individual file | 100 GB target for beta, subject to acceptance tests |
| Per-user stored-content quota | 200 GB |
| Default retention | 24 hours, configurable within server policy |
| Expiry behavior | Block new downloads at expiry; allow eligible active downloads to finish for a maximum 24-hour grace period, then revoke access |
| Notifications | In-app inbox, email, and push notifications |
| Push notification privacy | Generic text by default; no filename or sensitive content by default |
| Sender local copy | Keep by default; optional deletion after durable remote storage is confirmed |
| Backup | Separately protected MySQL metadata backups; no cross-region file backup in MVP |
| Deployment | Small private beta, invitation-only |
| Background work | Persistent queues and resumable transfers; honor OS/browser limitations |
| Architecture | Modular monolith, durable database-backed jobs, object storage for bytes |

---

# 3. Resolved defaults and conflict decisions

The product owner asked the agent to resolve conflicts with sensible defaults instead of asking many additional questions. Apply these decisions unless a genuine blocker is discovered.

## 3.1 Quota accounting

**Decision:** Charge stored content to the user who owns the stored transfer object (normally the sender). Do not charge a recipient merely because they have received a transfer, unless the product later introduces recipient-owned copies or recipient-side storage.

- The sender's retained transfers count toward the sender's 200 GB quota.
- A transfer is charged once, not once per recipient.
- Pending upload reservations count against available quota.
- Temporary multipart data and abandoned uploads have separate operational limits and cleanup.
- When an upload is cancelled or definitively abandoned, release its reservation after reconciliation.
- When content expires and deletion is confirmed, release the associated stored-content quota.
- Prevent concurrent uploads from reserving more than the available quota by using an atomic database transaction or equivalent concurrency-safe reservation.
- Keep an operational safety margin for temporary chunks, multipart parts, retries, and provider versioning. The 200 GB user quota is not a promise that total infrastructure usage is capped at 200 GB per user.

If product semantics later require each recipient to retain a separate stored copy, add a distinct ownership and quota policy rather than charging implicitly.

## 3.2 Authentication and MFA

- MFA is optional for ordinary beta users and mandatory for administrators.
- Use a modern password hashing algorithm, preferably Argon2id with parameters selected for the deployment.
- Verify email ownership before permitting file access.
- Rate-limit login, password reset, invitation redemption, MFA verification, and recovery operations.
- Use secure, revocable sessions and protect cookies against CSRF where applicable.
- Provide MFA recovery codes or a documented recovery flow.
- Require reauthentication for sensitive account changes where appropriate.
- Never use a password reset to silently recover or decrypt E2E keys.

## 3.3 E2E recovery

**Decision:** Use a user-controlled recovery secret/key. The server may store an encrypted recovery package but must not receive the recovery secret or plaintext private key.

- The user must be able to create and verify a recovery key during E2E setup.
- Encourage an offline backup or a password-protected recovery export.
- The application must warn that loss of both device keys and recovery secret can make E2E content unrecoverable.
- The exact cryptographic envelope, key derivation, key rotation, device enrollment, recipient key distribution, and recovery protocol must be designed with established cryptographic libraries and reviewed before E2E is declared production-ready.
- Do not claim the system has strong E2E security until the server-side key-exposure tests and protocol review pass.
- The server must not implement a hidden recovery bypass.
- A user who loses the recovery secret and all trusted keys must not be told that recovery is guaranteed.

## 3.4 Expiry and active downloads

- Default retention is 24 hours from the product-defined start event. **Resolved default:** start the expiry clock when the upload is marked `STORED_AND_VERIFIED`, not when the sender first begins uploading.
- Show the exact expiry timestamp to sender and recipient.
- At expiry, reject new download sessions and new access authorizations.
- Allow a download session authorized before expiry to finish for up to 24 additional hours.
- A disconnected session may resume only within that grace period and only if the server still considers that session eligible.
- After the grace period, revoke access and queue deletion.
- Do not allow repeated reconnects to reset the grace-period clock.
- Expiry, access revocation, and physical deletion are separate events.
- Cleanup must be idempotent and retried until the storage provider confirms the operation or the system reports an actionable failure.
- Sender cancellation may revoke access earlier than expiry, subject to a clearly documented active-download policy. Default: cancellation blocks new downloads immediately and gives active downloads no additional grace beyond the currently authorized session, unless the user interface explicitly warns otherwise.

## 3.5 Durability and automatic sender deletion

Define `STORED_AND_VERIFIED` carefully:

1. Every expected chunk/part has been uploaded.
2. The object storage service confirms the completed object or all committed parts.
3. The system validates expected file size and available checksums.
4. The system validates the final object using a trusted checksum path, or streams and hashes the complete object where necessary.
5. The metadata transaction commits the verified state.
6. The backend returns a durable-storage receipt bound to the transfer ID, object version/identifier, size, checksum, and state version.

Only after receiving and validating this receipt may the sender client offer or perform automatic source deletion, and only if the user explicitly enabled it.

- Default: keep local source.
- If the acknowledgement is lost, query server state before deletion.
- A remote durable-storage receipt does **not** mean the recipient has downloaded the file or that the file is protected against regional disaster.
- The product must explain this distinction to users.
- Where possible, retain a local copy until the sender explicitly chooses otherwise.

## 3.6 Storage and backups

- Use object storage redundant within one region.
- Back up MySQL metadata separately, protect backups from accidental deletion and account compromise, and test restores.
- Do not describe metadata backups as file-content backups.
- Document that catastrophic loss of the storage region can make file content unavailable or unrecoverable in the MVP.
- Cross-region file replication is a future enhancement.
- Storage provider, region, lifecycle policies, and actual durability terms must be documented once selected.

## 3.7 100 GB file target

- Treat 100 GB as the target maximum individual file size, not as a proven capability at project start.
- All server code must stream content and avoid whole-file buffering.
- Use multipart upload or chunk-based resumable transfers supported by the chosen object store.
- Persist upload session and chunk progress.
- Verify integrity at chunk and complete-file levels.
- Test 100 GB transfers end-to-end on each supported client type before advertising the limit.
- Use configurable chunk size and concurrency, selected by benchmark.
- Check filesystem capacity before starting large local downloads where practical.
- Browser and mobile operating systems impose background-execution limits; do not promise indefinite transfer execution on these platforms.

## 3.8 Notifications

- Notifications are asynchronous and never the authoritative transfer record.
- The in-app inbox is backed by persisted server state.
- Use a durable outbox/job pattern so a committed transfer does not lose its notification because the email/push provider is unavailable.
- Retry transient notification failures with backoff and deduplicate by event ID.
- Generic push message by default, such as “You have a new file. Open the app to view it.”
- Do not include file contents, encryption keys, recovery secrets, presigned URLs, or sensitive filenames in push payloads.
- Respect user preferences and platform permissions.
- Track provider delivery outcomes without treating a notification as proof that the recipient read or downloaded a file.

---

# 4. User roles and permissions

## 4.1 Roles

### Ordinary user
- Register only with a valid invitation.
- Verify email before accessing transfers.
- Send files to registered, verified recipients.
- View own sent transfers and received inbox.
- Pause, resume, cancel, and retry eligible transfers.
- Choose standard or E2E privacy mode per transfer.
- Configure notification preferences.
- Configure retention within policy limits.
- Optionally enable sender-side local deletion after durable storage.
- Manage own devices and E2E recovery material.

### Administrator
- Mandatory MFA.
- Invite, suspend, or disable users.
- Configure global quotas, file-size limits, retention bounds, and beta settings.
- View operational status and security audit events.
- Cannot decrypt E2E file contents or access user recovery secrets.
- Administrative access to standard-mode content must be narrowly scoped, audited, and limited to an explicit product function; do not create a general-purpose “browse all user files” feature.

## 4.2 Authorization rules

- Every API endpoint that reads or changes a transfer must verify the authenticated actor's permissions.
- Knowing a transfer ID is never sufficient authorization.
- Recipients must be explicitly associated with a transfer.
- Unverified users cannot download.
- Expired or revoked transfers cannot start new downloads.
- Short-lived storage URLs must be scoped to the exact object and operation, and must not bypass application authorization after expiry.
- Avoid leaking whether arbitrary email addresses belong to registered accounts.
- Audit sensitive events: login security changes, MFA changes, invitation creation/redemption, transfer creation/cancellation, recovery-key changes, and administrative actions.

---

# 5. Functional requirements

Use IDs in code issues, tests, and acceptance criteria where practical.

## Identity and onboarding

- **FR-AUTH-01:** Support email/password registration through a valid invitation.
- **FR-AUTH-02:** Verify email ownership before file access.
- **FR-AUTH-03:** Require MFA for administrators; offer MFA enrollment to ordinary users.
- **FR-AUTH-04:** Provide secure password reset and session revocation.
- **FR-AUTH-05:** Rate-limit authentication and recovery attempts.
- **FR-AUTH-06:** Invitations must expire, be revocable, and have documented single-use or limited-use behavior. Default: single-use, expiring invitations.
- **FR-AUTH-07:** Never use account recovery to bypass E2E encryption.

## Transfer creation and content

- **FR-XFER-01:** Transfer arbitrary binary file types without altering bytes.
- **FR-XFER-02:** Support a target maximum file size of 100 GB.
- **FR-XFER-03:** Support multiple files as separate transfer objects initially; folder/archive convenience may be added later.
- **FR-XFER-04:** Support sender-to-one-or-more-registered-recipient delivery.
- **FR-XFER-05:** Store per-recipient delivery status.
- **FR-XFER-06:** Display filename, size, sender, creation time, expiry, privacy mode, and state, subject to E2E metadata limitations.
- **FR-XFER-07:** Allow pause, resume, cancel, and retry where meaningful.
- **FR-XFER-08:** Make retryable operations idempotent.

## Resumability and integrity

- **FR-RES-01:** Split uploads into chunks or use provider-supported multipart uploads.
- **FR-RES-02:** Persist an upload manifest with expected size, chunk/part identifiers, sizes, checksums, and state.
- **FR-RES-03:** Resume after network interruption, app restart, and retryable server/storage errors.
- **FR-RES-04:** Reconcile client progress with the server's verified manifest after reconnect.
- **FR-RES-05:** Verify chunk or part integrity using a cryptographic checksum where supported.
- **FR-RES-06:** Verify final file size and full-file hash before declaring completion.
- **FR-RES-07:** Do not trust an object-storage ETag as a universal full-file MD5 checksum; multipart ETags are not generally equivalent to a simple file hash.
- **FR-RES-08:** Do not report success when the database and object storage disagree.
- **FR-RES-09:** Detect and clean abandoned multipart uploads and orphaned objects according to policy.

## Offline recipient delivery

- **FR-OFF-01:** Store completed files until expiry, cancellation, or the applicable retention policy.
- **FR-OFF-02:** Provide a recipient inbox independent of email/push delivery.
- **FR-OFF-03:** Notify recipients when a transfer becomes available.
- **FR-OFF-04:** Let a recipient download later while the transfer remains eligible.
- **FR-OFF-05:** Provide clear expired, cancelled, failed, and unavailable states.

## Privacy and encryption

- **FR-SEC-01:** Use TLS for all network traffic.
- **FR-SEC-02:** Support standard server-readable encryption mode.
- **FR-SEC-03:** Support client-side E2E encryption mode with server-stored ciphertext.
- **FR-SEC-04:** Identify the privacy mode in transfer metadata and UI.
- **FR-SEC-05:** Do not silently downgrade an E2E transfer to standard mode.
- **FR-SEC-06:** Use authenticated encryption and vetted cryptographic libraries.
- **FR-SEC-07:** Provide a user-controlled recovery-key mechanism for E2E keys, subject to security review.
- **FR-SEC-08:** Ensure the server does not log or receive plaintext recovery secrets or E2E private keys.
- **FR-SEC-09:** Document that E2E mode prevents server-side content previews/scanning and may make recovery impossible if all user-held recovery material is lost.

## Retention and deletion

- **FR-RET-01:** Default expiry is 24 hours after remote storage is verified.
- **FR-RET-02:** Allow configurable expiry within server-defined limits.
- **FR-RET-03:** Show exact expiry time and timezone-aware display.
- **FR-RET-04:** Block new download sessions after expiry.
- **FR-RET-05:** Allow eligible active downloads to finish for up to 24 additional hours.
- **FR-RET-06:** Delete expired/cancelled content asynchronously and idempotently.
- **FR-RET-07:** Track deletion pending, deletion confirmed, and deletion failed.
- **FR-RET-08:** Optional sender-side deletion is permitted only after durable-storage confirmation and explicit user opt-in.
- **FR-RET-09:** Document that remote deletion cannot erase copies already downloaded by recipients.
- **FR-RET-10:** Define audit metadata retention separately from file-content retention.

## Quotas and cost controls

- **FR-QUOTA-01:** Default per-user stored-content quota is 200 GB.
- **FR-QUOTA-02:** Target maximum file size is 100 GB.
- **FR-QUOTA-03:** Reserve quota atomically before accepting large uploads.
- **FR-QUOTA-04:** Prevent concurrent upload races from exceeding quota.
- **FR-QUOTA-05:** Account for active reservations, stored objects, and cleanup state.
- **FR-QUOTA-06:** Apply configurable rate limits and abuse controls.
- **FR-QUOTA-07:** Provide admin visibility into storage use and stuck cleanup tasks.

## Notifications

- **FR-NOTIFY-01:** Provide in-app inbox, email, and push notifications.
- **FR-NOTIFY-02:** Use durable asynchronous notification jobs.
- **FR-NOTIFY-03:** Deduplicate notification events and retry transient failures.
- **FR-NOTIFY-04:** Use generic push payloads by default.
- **FR-NOTIFY-05:** Notification failure must not change transfer success/failure state.
- **FR-NOTIFY-06:** Allow users to configure notification preferences.

## Operations and audit

- **FR-OPS-01:** Record transfer state transitions and critical security events.
- **FR-OPS-02:** Provide structured logs, health endpoints, and metrics.
- **FR-OPS-03:** Detect stuck jobs, stale uploads, quota inconsistencies, and orphaned objects.
- **FR-OPS-04:** Provide tested MySQL backup and restore procedures.
- **FR-OPS-05:** Report permanent errors to the user and preserve useful diagnostics without secrets.

---

# 6. Nonfunctional requirements

## Integrity and durability
- A completed transfer must match expected size and final checksum.
- No incomplete or corrupted file may be marked verified.
- At-least-once delivery/job execution is acceptable if operations are idempotent.
- Do not promise exactly-once network delivery.
- Acknowledged state changes must be recoverable under the documented database/storage failure model.
- A database row alone does not prove that file bytes exist.

## Performance and resource usage
- Stream all large content.
- Do not buffer entire files in the Java heap.
- Use bounded concurrency and backpressure.
- Tune chunk size and concurrency through benchmarks.
- Make storage upload/download throughput independently scalable from metadata API throughput.
- Set practical connection, request, and storage timeouts.

## Availability and recovery
- Initial deployment is a small private beta.
- Define an initial service-level objective after selecting the deployment platform and monitoring.
- Metadata backup and restore must be tested.
- No cross-region file recovery guarantee exists in MVP.
- Document recovery point objective (RPO) and recovery time objective (RTO) before production commitments.

## Security and privacy
- Enforce least privilege for application and cloud identities.
- Store secrets in environment-managed secret storage, not source control.
- Use short-lived scoped object-storage credentials/URLs.
- Protect session cookies and CSRF-sensitive endpoints.
- Use security headers and strict CORS configuration.
- Validate filename length and metadata; treat filenames as untrusted display data.
- Do not execute uploaded files or trust file extensions/MIME types.
- Avoid serving arbitrary content inline from the application's authenticated origin.
- Add abuse controls, quotas, and audit logging.

## Compatibility
- Define supported OS/browser versions in the README before beta.
- Test desktop and mobile background behavior realistically.
- Browser clients must tolerate tab suspension and ask the user to reopen/resume if needed.
- Mobile clients must respect OS background scheduling and battery policies.
- Desktop client should use a persistent local transfer queue and recover it after restart.

---

# 7. Architecture

## 7.1 High-level components

### Clients
- Web client: React + TypeScript.
- Desktop client: recommended Tauri + React/TypeScript, or another framework selected after evaluating native background transfer support. Keep a persistent native transfer engine; do not rely on a browser tab alone.
- Mobile client: platform-native or cross-platform framework selected after validating background upload/download support on target OS versions.
- Clients maintain local transfer metadata and safe temporary files to support pause/resume.
- For E2E mode, clients perform encryption/decryption and key operations locally.

### Backend
- Java + Spring Boot modular monolith.
- REST API for identity, transfer creation, manifests, authorization, state changes, and inbox.
- SSE or WebSocket may be added for live progress, but polling must remain a reliable fallback.
- Use a durable MySQL-backed job/outbox table for the initial beta.
- Use object storage for file content.
- Keep API servers stateless where practical.

### MySQL
Authoritative for:
- users and account status,
- invitations,
- MFA metadata,
- devices/public keys,
- transfers and recipients,
- upload sessions and manifests,
- quota reservations,
- notification outbox/jobs,
- idempotency records,
- audit events,
- cleanup/reconciliation jobs.

MySQL is not the file-content store.

### Object storage
- Holds binary objects or completed multipart objects.
- Redundant within one selected region.
- Use private buckets/containers and least-privilege access.
- Enable versioning or recovery controls where practical and cost-appropriate.
- Configure lifecycle cleanup for abandoned multipart uploads.
- Never make stored objects public.
- Choose a provider only after evaluating cost, multipart/resume support, signed URL behavior, checksums, lifecycle controls, and documented durability.

### Background workers
- Process notification jobs.
- Retry and reconcile incomplete uploads.
- Verify or schedule final integrity checks.
- Expire transfers and delete objects.
- Clean abandoned multipart sessions and orphaned objects.
- Reconcile quota reservations against storage state.
- Retry jobs with exponential backoff and jitter; after a bounded number of automatic retries, preserve the job and expose an actionable alert rather than silently discarding it.

## 7.2 Architectural boundaries

1. **Control plane:** users, permissions, transfer state, quotas, notifications, and policy.
2. **Data plane:** file bytes transferred between clients and object storage.
3. **Recovery plane:** retries, integrity verification, reconciliation, backups, and cleanup.

Where object storage supports it, use short-lived, narrowly scoped signed URLs so large bytes do not have to pass through the Java API server. The backend must authorize the operation before issuing the URL and validate completion afterward.

## 7.3 Recommended backend modules

- `identity`: registration, invitation, email verification, password reset, sessions, MFA.
- `users`: profile, devices, notification preferences.
- `transfers`: creation, recipients, state machine, expiry policy.
- `uploads`: upload sessions, chunks/parts, completion, resume manifests.
- `downloads`: authorization, signed download access, range/chunk resume.
- `storage`: provider interface and implementation.
- `integrity`: checksums, final verification, mismatch handling.
- `quotas`: reservation, usage, reconciliation.
- `encryption`: privacy-mode metadata and key-envelope metadata; no custom crypto.
- `notifications`: inbox, email/push outbox, retry/deduplication.
- `jobs`: claims, retries, scheduling, reconciliation.
- `retention`: expiry, revocation, deletion lifecycle.
- `audit`: security and state-transition events.
- `observability`: health, metrics, tracing/logging.

Keep these as packages/modules in one deployable application initially.

---

# 8. Transfer state machine

Use explicit state transitions and enforce them in the service layer. Avoid relying on a single `completed` boolean.

## 8.1 Transfer-level states

- `CREATED`
- `QUEUED`
- `UPLOADING`
- `VERIFYING`
- `STORED_AND_VERIFIED`
- `AVAILABLE_TO_RECIPIENT`
- `PARTIALLY_DELIVERED` (multi-recipient only, if needed)
- `DELIVERED` (all applicable recipients have verified receipt)
- `CANCELLED`
- `EXPIRED`
- `DELETION_PENDING`
- `DELETED`
- `FAILED` (terminal only after retry policy is exhausted or an unrecoverable condition is established)

Use separate job/session states for transient operations. A retryable chunk error must not automatically make the entire transfer terminally failed.

## 8.2 Recipient-level states

- `INVITATION_REQUIRED`
- `AWAITING_EMAIL_VERIFICATION`
- `AVAILABLE`
- `DOWNLOAD_STARTED`
- `DOWNLOADING`
- `VERIFIED_AT_RECEIVER`
- `CANCELLED`
- `EXPIRED`
- `FAILED`

Do not let one recipient's completion mark every recipient as delivered.

## 8.3 Upload session states

- `INITIATED`
- `ACTIVE`
- `PAUSED`
- `COMPLETING`
- `VERIFYING`
- `COMPLETED`
- `ABORT_PENDING`
- `ABORTED`
- `FAILED`

## 8.4 Required transition rules

- `STORED_AND_VERIFIED` is reached only after the content and manifest are verified.
- `AVAILABLE_TO_RECIPIENT` requires valid recipient association and retention policy eligibility.
- Expiry blocks new sessions; an already-authorized download may finish within the fixed grace period.
- `DELETION_PENDING` does not mean content is physically deleted.
- `DELETED` is reached only after the storage deletion operation is confirmed according to provider semantics.
- Every transition records actor/system, timestamp, previous state, new state, and correlation ID where appropriate.
- Use optimistic locking/version fields or equivalent concurrency controls to prevent conflicting transitions.
- All mutating API operations must be safe to retry or use idempotency keys.

---

# 9. MySQL schema blueprint

Use InnoDB, foreign keys, migrations, UTC timestamps, and suitable indexes. Use `BIGINT` for file sizes and byte counts. Do not store file bytes in MySQL.

This is a conceptual schema; the agent should produce reviewed migrations and exact constraints.

## 9.1 `users`
- `id` (opaque UUID/ULID primary key)
- `email_normalized` (unique)
- `password_hash`
- `email_verified_at` (nullable)
- `status` (`INVITED`, `ACTIVE`, `SUSPENDED`, `DISABLED`)
- `role` (`USER`, `ADMIN`)
- `created_at`, `updated_at`, `last_login_at`

## 9.2 `invitations`
- `id`
- `email_normalized` (nullable if invitation is not email-bound; default should be email-bound)
- `token_hash` (store a hash, not the raw redeemable token)
- `created_by_user_id`
- `expires_at`
- `redeemed_at`
- `revoked_at`
- `created_at`

Invitation tokens must be high-entropy, single-use, time-limited, and protected against brute force.

## 9.3 `user_mfa_methods`
- `id`
- `user_id`
- `method_type`
- `secret_ciphertext` or external secret reference
- `enabled_at`
- `last_used_at`
- `created_at`

Never store MFA secrets in plaintext. Recovery codes should be hashed or otherwise securely protected and invalidated after use.

## 9.4 `devices`
- `id`
- `user_id`
- `device_name`
- `platform`
- `public_key` or public-key reference, if used by the E2E protocol
- `registered_at`
- `last_seen_at`
- `revoked_at`

Do not store private device keys in server-readable form.

## 9.5 `transfers`
- `id`
- `sender_id`
- `original_filename`
- `file_size_bytes`
- `content_type_hint` (untrusted hint; nullable)
- `privacy_mode` (`STANDARD`, `E2E`)
- `state`
- `storage_object_key` or object manifest reference
- `storage_object_version` (nullable)
- `expected_sha256` (where the privacy mode permits the relevant representation)
- `verified_sha256` (hash of the stored representation; be clear whether plaintext or ciphertext)
- `created_at`
- `stored_at`
- `expires_at`
- `deletion_started_at`
- `deleted_at`
- `state_version`
- `quota_reservation_bytes`

For E2E mode, the server-side hash is normally a hash of ciphertext, unless the client intentionally supplies a plaintext hash. Avoid leaking plaintext hashes where they could enable guessing attacks against predictable files.

## 9.6 `transfer_recipients`
- `transfer_id`
- `recipient_user_id`
- `state`
- `invitation_id` (nullable)
- `available_at`
- `download_started_at`
- `verified_at`
- `expires_at` (if recipient-specific behavior is later adopted)
- `created_at`
- unique constraint on `(transfer_id, recipient_user_id)`

## 9.7 `upload_sessions`
- `id`
- `transfer_id`
- `storage_upload_id` (nullable)
- `state`
- `chunk_size_bytes`
- `expected_chunk_count`
- `created_at`
- `last_activity_at`
- `completed_at`
- `failure_code` (non-secret structured code)

## 9.8 `file_chunks`
- `id`
- `upload_session_id`
- `chunk_index`
- `byte_length`
- `checksum_algorithm`
- `checksum_value`
- `storage_part_id` or object key
- `state`
- `verified_at`
- unique constraint on `(upload_session_id, chunk_index)`

## 9.9 `quota_reservations`
- `id`
- `user_id`
- `transfer_id`
- `reserved_bytes`
- `state` (`ACTIVE`, `COMMITTED`, `RELEASED`)
- `created_at`
- `released_at`

Reservations must be changed transactionally to avoid concurrent quota races.

## 9.10 `idempotency_records`
- `id`
- `user_id`
- `operation_scope`
- `idempotency_key`
- `request_fingerprint`
- `response_reference` or safely stored response metadata
- `created_at`
- `expires_at`
- unique constraint across user, operation scope, and key

Do not store sensitive bearer credentials in these records.

## 9.11 `notification_outbox`
- `id`
- `user_id`
- `event_type`
- `event_id` (unique deduplication key)
- `channel` (`IN_APP`, `EMAIL`, `PUSH`)
- `payload_reference` or minimal safe payload
- `state`
- `attempt_count`
- `next_attempt_at`
- `provider_message_id` (nullable)
- `created_at`
- `sent_at`

## 9.12 `inbox_notifications`
- `id`
- `user_id`
- `event_id`
- `notification_type`
- `transfer_id` (nullable)
- `read_at`
- `created_at`

## 9.13 `background_jobs`
- `id`
- `job_type`
- `aggregate_id`
- `state`
- `attempt_count`
- `next_attempt_at`
- `locked_at`
- `locked_by`
- `last_error_code`
- `created_at`
- `updated_at`

Implement safe job claiming, leases/timeouts, and recovery for workers that crash while holding a job.

## 9.14 `audit_events`
- `id`
- `actor_user_id` (nullable for system actions)
- `event_type`
- `target_type`
- `target_id`
- `correlation_id`
- `created_at`
- minimal structured metadata with no secrets or file contents

---

# 10. API contract outline

Use REST, JSON for control-plane operations, HTTPS everywhere, consistent error responses, and version the API (for example `/api/v1`). This is an initial contract outline; document exact DTOs and status codes in OpenAPI.

## Identity and invitations
- `POST /api/v1/auth/register` — redeem invitation and create account.
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh` if refresh tokens are used.
- `POST /api/v1/auth/email-verification/request`
- `POST /api/v1/auth/email-verification/confirm`
- `POST /api/v1/auth/password-reset/request`
- `POST /api/v1/auth/password-reset/confirm`
- `POST /api/v1/auth/mfa/enroll`
- `POST /api/v1/auth/mfa/confirm`
- `POST /api/v1/auth/mfa/verify`
- `POST /api/v1/auth/mfa/recovery`
- `POST /api/v1/invitations` — administrator only.
- `POST /api/v1/invitations/{id}/revoke` — administrator only.

Do not reveal account existence through distinguishable unauthenticated responses.

## Transfers and uploads
- `POST /api/v1/transfers` — create transfer and recipient assignments.
- `GET /api/v1/transfers/{transferId}` — authorized transfer metadata/state.
- `GET /api/v1/transfers/{transferId}/recipients`
- `POST /api/v1/transfers/{transferId}/upload-sessions`
- `GET /api/v1/upload-sessions/{sessionId}/manifest`
- `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/authorize` — return a short-lived, narrowly scoped upload URL or equivalent.
- `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/commit` — idempotently record part completion and checksum metadata.
- `POST /api/v1/upload-sessions/{sessionId}/complete` — request completion and verification.
- `POST /api/v1/upload-sessions/{sessionId}/pause`
- `POST /api/v1/upload-sessions/{sessionId}/resume`
- `POST /api/v1/transfers/{transferId}/cancel`

Do not assume that a successful object-store part upload automatically means the application has verified it. The backend must validate completion and reconcile actual storage state.

## Recipient inbox and download
- `GET /api/v1/inbox`
- `GET /api/v1/inbox/{notificationId}`
- `POST /api/v1/transfers/{transferId}/download-sessions`
- `GET /api/v1/download-sessions/{sessionId}/manifest`
- `POST /api/v1/download-sessions/{sessionId}/authorize` — return a short-lived download URL or range/chunk authorization.
- `POST /api/v1/download-sessions/{sessionId}/acknowledge` — client reports final verification; backend validates the session and records receipt idempotently.
- `POST /api/v1/transfers/{transferId}/mark-read` if needed.

A client acknowledgement is useful but should not be treated as cryptographic proof that the user retained the file. The client must verify the downloaded bytes before acknowledging.

## Settings and recovery
- `GET /api/v1/users/me`
- `PATCH /api/v1/users/me/preferences`
- `GET /api/v1/users/me/quota`
- `POST /api/v1/users/me/devices`
- `POST /api/v1/users/me/devices/{deviceId}/revoke`
- E2E key enrollment/recovery endpoints must be designed only after the protocol is reviewed. They may store encrypted envelopes and public keys, never plaintext recovery secrets/private keys.

## Operations
- `GET /actuator/health` or equivalent health endpoint, with sensitive details restricted.
- Metrics endpoint protected from public access.
- Administrative endpoints require role checks and MFA.

## API error conventions

Return stable machine-readable error codes, such as:
- `AUTH_REQUIRED`
- `EMAIL_NOT_VERIFIED`
- `MFA_REQUIRED`
- `INVITATION_INVALID`
- `TRANSFER_NOT_FOUND`
- `TRANSFER_EXPIRED`
- `TRANSFER_CANCELLED`
- `QUOTA_EXCEEDED`
- `UPLOAD_SESSION_EXPIRED`
- `CHECKSUM_MISMATCH`
- `STORAGE_TEMPORARILY_UNAVAILABLE`
- `IDEMPOTENCY_KEY_REUSED`
- `RECIPIENT_NOT_AUTHORIZED`

Do not expose storage internals, stack traces, secrets, or account-existence details.

---

# 11. Idempotency, consistency, and reconciliation

MySQL and object storage do not share one atomic transaction. Design around that fact.

## Required principles
- Every mutating operation that may be retried must have an idempotency strategy.
- Use unique operation IDs and stable transfer/session/part IDs.
- A repeated request with the same idempotency key and same request fingerprint returns the prior logical result.
- Reusing a key with a different request payload returns a conflict.
- Do not delete a remote object merely because a database update failed; first reconcile ownership and state.
- Do not mark a transfer stored merely because a database row says the upload completed.
- Do not mark a transfer deleted until the storage deletion operation is confirmed.
- Use optimistic locking/version numbers or transaction locks for state transitions.
- Keep a periodic reconciliation worker.

## Reconciliation cases
1. Object/part exists but database progress is missing.
2. Database says part exists but storage cannot find it.
3. Upload completed in storage but metadata completion failed.
4. Database says expired/deleted but the object still exists.
5. Quota reservation exists for a cancelled or abandoned upload.
6. Notification job was committed but not sent.
7. A worker crashed after performing an external operation but before recording the result.
8. Local client and server manifests disagree after reconnect.

Each case needs a deterministic repair or a visible operator alert. Do not automatically destroy ambiguous data before confirming it is safe to remove.

---

# 12. Security and privacy requirements

## 12.1 Standard encryption mode
- TLS in transit.
- Encryption at rest using storage-provider controls or application encryption as justified.
- Server-side access limited by least privilege.
- Explicit authorization and audit for any server-side content access.
- Never expose file objects publicly.
- File content should not be served inline from a trusted app origin unless the content is safely isolated.
- Filenames and MIME types are untrusted input.

## 12.2 E2E mode
- Encrypt on the sender client before upload.
- Decrypt only on an authorized recipient client.
- Server stores ciphertext, public-key metadata, and encrypted key envelopes only.
- Use authenticated encryption with unique nonces as required by the selected construction.
- Use established key agreement and key-wrapping schemes; no home-grown cryptography.
- Design key distribution and identity verification against man-in-the-middle attacks.
- Define what happens when a recipient device is revoked.
- Make the user aware that a recipient can retain or copy a file after downloading it.
- A file's name, size, timing, sender, and recipient may remain visible to the service unless separately protected. Document metadata privacy honestly.
- Do not claim the server cannot infer plaintext from metadata in all cases.
- Recovery must not introduce a server-held decryption key.

## 12.3 Application security
- Input validation on every API endpoint.
- SQL queries through parameterized ORM/query APIs.
- Output encoding and safe rendering of filenames.
- CSRF protection for cookie-authenticated mutations.
- Strict CORS allowlist.
- Security headers.
- Rate limits for auth, invitations, transfer creation, URL issuance, and recovery.
- Secrets managed outside source control.
- Dependency scanning and timely security updates.
- Least-privilege cloud roles for upload, download, list, and delete operations.
- Presigned URLs are secrets: never log them and keep their lifetime short.
- Admin actions require MFA and are audited.
- Avoid logging sensitive request bodies or query parameters.

## 12.4 Abuse and untrusted files
- The platform transports arbitrary bytes; it does not execute uploaded files.
- Do not trust file extensions or user-provided MIME types.
- Standard mode may later add content scanning if policy permits.
- E2E mode cannot provide ordinary server-side content scanning without undermining confidentiality.
- Apply per-user quotas, rate limits, invitation limits, and operational budget alarms.
- Define a user-reporting and account-suspension process before public launch.

---

# 13. Local client storage and background transfers

## 13.1 Local queue
- Persist transfer ID, source path/URI, expected size, progress manifest, privacy mode, and retry state.
- Store only necessary metadata and protect sensitive local data.
- For E2E, local encryption keys must be stored in the platform's secure key store where available.
- Do not persist passwords, raw session tokens, or recovery secrets in ordinary config files.
- Recover pending work after restart.
- If a source file changes during upload, detect the mismatch and require a new upload or restart; do not silently combine versions.
- Verify available disk space before creating large temporary download files.
- Keep partial downloads clearly marked and do not expose them as complete files.

## 13.2 Background behavior
- Desktop: implement a persistent transfer worker or service appropriate to the chosen desktop framework.
- Mobile: use supported OS background transfer APIs and communicate suspension limits.
- Web: use resumable uploads and resume when the page becomes active; do not promise continuous operation after the tab/browser is closed.
- Every client must reconcile its local manifest with server state after reconnect.
- Retry with exponential backoff and jitter, with a maximum retry frequency.
- Provide a clear pause/cancel option.
- Do not delete the sender's source file while the local client is uncertain whether remote durability was confirmed.

---

# 14. Observability and operations

## Metrics
- Upload throughput and completion rate.
- Download throughput and completion rate.
- Active uploads/downloads.
- Chunk retry rate.
- Checksum mismatch count.
- Upload session age and stuck-session count.
- Queue depth and oldest job age.
- Notification failure/retry rate.
- Storage usage and egress estimate.
- Quota reservation mismatch count.
- Expiry backlog and deletion failure count.
- Authentication failures, invitation abuse, and rate-limit events.
- Backend latency and error rate.
- Database connection pool pressure and storage-provider error rates.

## Logs
- Structured logs with correlation IDs and transfer/session IDs where safe.
- Never log passwords, tokens, recovery keys, cryptographic keys, raw file content, or presigned URLs.
- Redact email addresses or other personal data where feasible.
- Provide actionable operator messages for stuck reconciliation or deletion jobs.

## Backups and restore
- Schedule MySQL backups and protect them from the same failure modes as the primary database.
- Define backup retention and encryption.
- Run restore drills in a separate environment.
- Document that the selected MVP policy does not guarantee file-content restoration after a regional storage disaster.
- Do not claim backup success without monitoring and verification.

---

# 15. Testing strategy

## Unit tests
- Transfer state transition validation.
- Quota reservation and release logic.
- Idempotency-key behavior.
- Expiry and grace-period calculations.
- Recipient authorization.
- Invitation expiration and redemption.
- Notification deduplication.
- Checksum verification.
- Recovery metadata validation without logging secrets.

## Integration tests
- MySQL migrations and transaction behavior.
- Object storage multipart/chunk workflow.
- Signed URL scoping and expiration.
- Complete upload and download.
- Database failure after object upload.
- Storage failure after database session creation.
- Notification outbox retry.
- Expiry and deletion worker.
- Quota race with concurrent uploads.
- Invitation and email verification flow.

Use a local S3-compatible storage implementation or a provider test environment. Clearly distinguish emulator behavior from the production provider's documented semantics.

## Failure injection tests
- Sender disconnects mid-upload.
- Sender application crashes and restarts.
- Recipient remains offline until later.
- Backend restarts after a chunk uploads but before metadata commits.
- Duplicate part-commit or completion requests.
- Missing or corrupted chunk.
- Storage service temporarily unavailable.
- Database unavailable.
- Disk full during download.
- Notification provider unavailable.
- Expiry occurs during an active download.
- Cleanup job crashes after storage deletion but before recording success.
- Quota reservation is abandoned.
- Client and server progress manifests disagree.
- Recovery key is lost.
- Recovery key is wrong.
- Device is revoked while an E2E transfer exists.

## Security tests
- Cross-user transfer ID access.
- Unverified recipient download.
- Expired transfer access.
- Revoked invitation redemption.
- MFA bypass attempts.
- Login/password-reset rate limiting.
- Presigned URL leakage and excessive lifetime.
- Path/filename injection and unsafe content rendering.
- Admin authorization and MFA enforcement.
- E2E server cannot decrypt test ciphertext with only server-side data.
- No secrets in logs.
- Recovery process does not leak or expose plaintext private keys to the server.

## Large-file acceptance tests
- Test progressively at 1 GB, 10 GB, and 100 GB.
- Measure memory use, disk use, transfer duration, retries, and CPU.
- Interrupt the network at different percentages and resume.
- Restart clients and backend.
- Verify final content hash after download.
- Test the maximum size on each supported platform, not just the desktop client.
- Verify cleanup of abandoned multipart uploads and partial downloads.

---

# 16. Deployment plan

## Private beta baseline
- One region.
- One production-like environment separate from local development.
- Managed MySQL where affordable.
- Single-region redundant object storage.
- HTTPS with valid certificates.
- Secret management through the deployment platform.
- Database migrations run as a controlled deployment step.
- Scheduled workers for reconciliation, notification delivery, expiry, and cleanup.
- Backups with tested restore procedure.
- Health checks, metrics, structured logs, and budget alerts.
- Invitation-only accounts.
- Conservative quotas/rate limits and manual beta onboarding.

Do not introduce Kubernetes or a distributed microservice deployment for the initial beta unless there is a concrete operational need.

## Environment configuration
Use environment variables or a secret manager for:
- database URL/user/password,
- object-storage endpoint/region/bucket,
- cloud credentials or workload identity,
- signing/session secrets,
- email provider credentials,
- push notification credentials,
- allowed frontend origins,
- maximum file size,
- default and maximum retention,
- quota limits,
- upload chunk size and concurrency,
- presigned URL lifetime,
- job retry policy,
- public application URL.

Provide `.env.example` with placeholders only. Never commit actual secrets.

## Cost controls
- Monitor storage and egress.
- Enforce user quotas and maximum file size.
- Set lifecycle rules for incomplete multipart uploads.
- Set budget alerts.
- Limit concurrent uploads/downloads per user.
- Keep temporary storage bounded.
- Choose a provider only after comparing current pricing, egress, request costs, redundancy, checksums, and lifecycle support.

---

# 17. Recommended repository structure

Adapt to the existing repository if one exists.

```text
file-sharing-platform/
├── README.md
├── LICENSE
├── .gitignore
├── .env.example
├── docker-compose.yml
├── docs/
│   ├── product-requirements.md
│   ├── architecture.md
│   ├── threat-model.md
│   ├── e2e-key-management.md
│   ├── api-openapi.yaml
│   ├── data-model.md
│   ├── deployment.md
│   ├── operations-runbook.md
│   ├── assumptions.md
│   ├── testing-plan.md
│   └── adr/
│       ├── ADR-001-modular-monolith.md
│       ├── ADR-002-object-storage.md
│       ├── ADR-003-resumable-transfers.md
│       ├── ADR-004-privacy-modes.md
│       ├── ADR-005-server-only-delivery.md
│       ├── ADR-006-durable-jobs.md
│       ├── ADR-007-retention.md
│       ├── ADR-008-authentication.md
│       ├── ADR-009-sender-deletion.md
│       ├── ADR-010-single-region-storage.md
│       ├── ADR-011-private-beta.md
│       ├── ADR-012-e2e-recovery.md
│       ├── ADR-013-expiry-grace.md
│       ├── ADR-014-metadata-backups.md
│       ├── ADR-015-100gb-target.md
│       ├── ADR-016-recovery-key.md
│       ├── ADR-017-email-verification.md
│       ├── ADR-018-quota-policy.md
│       ├── ADR-019-invitations.md
│       └── ADR-020-notifications.md
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       └── test/java/.../
├── web/
│   ├── package.json
│   └── src/
├── desktop/
│   └── README.md
├── mobile/
│   └── README.md
├── infrastructure/
│   ├── local/
│   └── deployment/
└── scripts/
    ├── dev-start
    ├── dev-test
    └── db-backup-restore-test
```

Do not create empty directories for every future component merely to match the diagram. Add them when the relevant phase begins.

---

# 18. Implementation roadmap

## Phase 0 — Repository and design validation
- Inspect repository and development environment.
- Confirm Java version, Spring Boot version, MySQL version, build tool, frontend toolchain, and target OS versions.
- Select a storage abstraction and local development provider.
- Write threat model and initial OpenAPI contract.
- Define transfer state machine and idempotency behavior.
- Design E2E key management separately before implementing E2E.
- Record assumptions and provider-specific constraints.

**Exit criteria:** Runnable skeleton, local setup instructions, schema migration baseline, and reviewed core state machine.

## Phase 1 — Identity and invitation-only beta
- Registration via invitation.
- Email verification.
- Password hashing and secure sessions.
- Password reset.
- MFA for administrators and optional MFA for ordinary users.
- Role-based access control.
- Audit security events.

**Exit criteria:** Security tests pass; unverified users cannot access files; invitations are protected and single-use.

## Phase 2 — Reliable standard-mode transfer core
- Transfer creation.
- Recipient association.
- Chunked/multipart upload.
- Persistent manifests.
- Signed upload/download access.
- Resumable upload and download.
- Idempotency.
- Checksums and final verification.
- MySQL state transitions.
- Local persistent queue for first client.

**Exit criteria:** Network interruption and restart tests pass; no false-success state; retry operations are safe.

## Phase 3 — Offline inbox, quota, retention
- Recipient inbox.
- 200 GB quota reservation/accounting.
- 100 GB file-size enforcement.
- 24-hour expiry clock after verified storage.
- Active-download grace period.
- Deletion worker and reconciliation.
- Sender-side optional deletion after durable-storage receipt.

**Exit criteria:** Quota race tests, expiry tests, deletion recovery, and storage reconciliation pass.

## Phase 4 — Email and push notifications
- Durable notification outbox.
- Email provider integration.
- Push provider integration.
- In-app inbox.
- Notification preferences.
- Retry/deduplication and generic push payloads.

**Exit criteria:** Provider outages do not lose transfer state or notification intent; no sensitive push payloads.

## Phase 5 — E2E encryption and recovery
- Complete threat model and protocol design.
- Select vetted cryptographic libraries and standard algorithms.
- Implement client-side encryption/decryption.
- Key distribution to recipients.
- User-controlled recovery key and encrypted recovery package.
- Device enrollment/revocation.
- Security review and adversarial tests.

**Exit criteria:** Independent security review, recovery tests, no server-side plaintext key exposure, and documented limitations. If these are not met, keep E2E behind a feature flag and do not advertise it as production-ready.

## Phase 6 — Desktop and mobile clients
- Persistent desktop transfer engine.
- Mobile background transfer integration.
- Secure local key storage.
- Device lifecycle and revocation.
- Cross-platform acceptance testing.
- Clear UI for paused/suspended/failed work.

**Exit criteria:** Resume/restart tests pass on every supported platform. Do not claim indefinite background execution where OS policy cannot guarantee it.

## Phase 7 — 100 GB and private beta hardening
- Test 1 GB, 10 GB, and 100 GB transfers.
- Load and failure-injection tests.
- Storage cost/egress monitoring.
- MySQL backup/restore drills.
- Rate limits and abuse controls.
- Runbooks and operational dashboards.
- Invitation-only rollout with conservative quotas.

**Exit criteria:** All critical acceptance tests pass, known limitations are documented, and the team can diagnose and recover stuck transfers.

---

# 19. Definition of done

A feature is not done merely because the happy path works.

For every feature:
- Code is reviewed and formatted.
- Relevant tests pass.
- Authorization and validation are covered.
- Retry and failure behavior are defined.
- Logs contain useful correlation IDs but no secrets.
- Documentation is updated.
- Database changes use migrations.
- UI reports the real state and actionable errors.
- There is no false success state after an interrupted operation.

For a transfer-related feature specifically:
- It handles network loss.
- It handles duplicate requests.
- It survives restart or has an explicitly documented limitation.
- It verifies integrity.
- It does not bypass quota, retention, or recipient authorization.
- It has reconciliation behavior for cross-system failure.

---

# 20. Initial acceptance checklist

The private beta is not ready until the relevant items below are tested and documented.

- [ ] Invitation-only registration works; invitation tokens expire and cannot be reused.
- [ ] Email verification is required before file access.
- [ ] MFA is mandatory for administrators.
- [ ] A sender can upload arbitrary binary files.
- [ ] An upload resumes after network loss without retransmitting verified chunks unnecessarily.
- [ ] An upload resumes after application restart.
- [ ] An offline recipient can later download a stored file.
- [ ] Final downloaded content matches the expected hash.
- [ ] A corrupted or missing chunk is detected.
- [ ] Repeated create/commit/complete/ack requests do not duplicate logical work.
- [ ] MySQL/object-storage inconsistency is reconciled or reported.
- [ ] Concurrent uploads cannot exceed the user's quota.
- [ ] 100 GB end-to-end transfer passes on every platform advertised as supporting it.
- [ ] 200 GB quota and temporary-upload limits behave correctly.
- [ ] Expiry blocks new downloads at the deadline.
- [ ] Eligible active downloads can finish within the fixed 24-hour grace period.
- [ ] Deletion is not reported complete until confirmed by storage.
- [ ] Automatic sender deletion is off by default and requires opt-in.
- [ ] Durable-storage receipt is checked before local deletion.
- [ ] Email/push provider outages do not lose transfer state.
- [ ] Push notifications contain no sensitive filenames or file contents by default.
- [ ] E2E mode prevents server-side decryption with only server-held data.
- [ ] E2E recovery works on a new device using the recovery secret.
- [ ] Lost recovery key behavior is clear and does not imply guaranteed recovery.
- [ ] No passwords, tokens, presigned URLs, or cryptographic secrets appear in logs.
- [ ] MySQL backup restoration is tested.
- [ ] Regional file-loss limitations are documented.
- [ ] Security and operations runbooks are available.

---

# 21. Remaining implementation-time choices

These are not reasons to delay all work. The agent should select and document suitable options before the relevant phase.

1. **Storage provider and region:** choose after comparing current price, egress, multipart support, checksum behavior, lifecycle controls, and durability documentation.
2. **Exact desktop/mobile framework:** validate background-transfer requirements and supported OS versions before committing.
3. **Email and push providers:** use adapters/interfaces where practical; choose providers based on beta budget and target platforms.
4. **Exact chunk size and concurrency:** determine through performance testing.
5. **Availability SLO, RPO, and RTO:** establish after deployment topology and backup tests are known.
6. **E2E cryptographic protocol:** must be decided and reviewed before enabling E2E for real users.
7. **Metadata audit retention:** define the minimum necessary duration and deletion rules.
8. **Active-download behavior on sender cancellation:** use the default in Section 3.4 unless product review changes it.
9. **Recipient notification preferences:** implement secure generic defaults and allow users to opt into less private detail later.
10. **Supported client versions:** publish only after real platform testing.

---

# 22. Final instructions to the coding agent

Start with Phase 0. Inspect the existing repository, then provide:

1. A concise summary of the repository and current implementation.
2. A proposed file-by-file implementation plan for Phase 1 and Phase 2.
3. Any blockers or security-sensitive assumptions.
4. A minimal runnable local development setup.
5. The first small implementation increment, with tests.

Do not begin by building every client, E2E cryptography, notifications, and deployment simultaneously. First establish a correct, testable backend and reliable standard-mode transfer workflow. Add E2E only after the key-management design is reviewed. Keep the application runnable and the documentation synchronized throughout.

**Product principle:** Reliability is not “the upload progress reached 100%.” Reliability means the system can prove what it has stored, resume interrupted work, detect inconsistencies, avoid false success, and explain clearly what can and cannot be recovered.
