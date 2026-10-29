# ADR-018: Quota Accounting, Concurrency Safety, and Storage Reconciliation

## Status
Accepted

## Context
HoneyShare allocates a 200 GB stored-content quota per user. File transfers can be up to 100 GB each. Concurrent uploads by the same user could race to allocate storage beyond the 200 GB limit if capacity is checked only after files are uploaded. Furthermore, if network interruptions cause abandoned multipart uploads, orphaned quota reservations could permanently exhaust a user's quota.

## Decision
1. **Ownership Principle:** Quota is charged solely to the sender who creates and owns the transfer object. Recipients are not charged for receiving or downloading files.
2. **Atomic Reservation:** Before accepting file chunks, the backend creates an active `QuotaReservation` in MySQL within a database transaction. The reservation checks:
   `sum(active_reservations) + sum(stored_transfers) + requested_bytes <= user_quota_limit (200 GB)`.
   If capacity is insufficient, `413 QUOTA_EXCEEDED` is returned immediately before storage resources are consumed.
3. **Commitment Lifecycle:**
   - While uploading: bytes are marked `ACTIVE` reservation.
   - Upon successful verification: the reservation is marked `COMMITTED`, and the transfer state becomes `STORED_AND_VERIFIED`.
   - Upon confirmed deletion: the reservation is marked `RELEASED`, immediately restoring available capacity to the user.
4. **Reconciliation Worker:** A background reconciliation process runs periodically:
   - Detects stale upload sessions (inactive > 48 hours or belonging to cancelled transfers).
   - Aborts in-flight multipart uploads on object storage via `StoragePort.abortMultipartUpload()`.
   - Transitions session to `ABORTED` and releases orphaned quota reservations.
   - Detects missing storage objects for stored transfers and marks them `FAILED`, alerting operators.

## Consequences
- Guaranteed prevention of quota over-subscription, even under highly concurrent upload requests.
- Automatic self-healing of orphaned reservations from client disconnects or crashes.
- Accurate and audit-traceable storage accounting.

