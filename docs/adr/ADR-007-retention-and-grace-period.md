# ADR-007: Transfer Retention Lifecycle and Fixed Grace Period

## Status
Accepted

## Context
Transfers uploaded to HoneyShare are ephemeral to maintain storage sustainability, cost control, and user privacy. Transfers must expire after a configurable duration (default 24 hours). However, recipients may experience intermittent network connectivity while downloading large files (up to 100 GB).

If a transfer expires while a recipient is actively downloading, abruptly revoking access could cause loss of partial download progress. Conversely, allowing indefinite reconnects would allow malicious or buggy clients to keep content stored permanently, violating retention and cost guarantees.

## Decision
1. **Clock Initiation:** The retention clock begins when a transfer reaches `STORED_AND_VERIFIED`, not when the sender initiates the upload session.
2. **Transfer Expiry:** At `transfer.expiresAt`, the transfer transitions to `EXPIRED`. All new download authorization requests (`POST /api/v1/transfers/{id}/download-sessions`) are immediately rejected with `410 TRANSFER_EXPIRED`.
3. **Fixed Grace Period:** Download sessions that were authorized *before* `transfer.expiresAt` are granted a fixed grace period (default 24 hours, `ACTIVE_DOWNLOAD_GRACE_HOURS = 24`) to complete their download.
4. **Grace Clock Immutability:** A recipient client reconnecting or resuming during the grace period increments the session's `reconnectCount` and receives a fresh short-lived pre-signed download URL, but the session's `expiresAt` deadline is strictly immutable. Reconnecting never resets or extends the grace period clock.
5. **Session Expiry:** When `session.expiresAt` passes, the session is marked `EXPIRED`, download URLs cease functioning, and access is revoked permanently.
6. **Physical Deletion Deferral:** Asynchronous storage deletion checks `downloadSessionRepository.hasActiveUnexpiredSessions(transferId, now)`. If an active session is still within grace period, physical deletion is deferred until the grace period completes.

## Consequences
- Predictable and observable expiry behavior for senders and recipients.
- Active large file downloads tolerate network interruptions and app restarts without being cut short at the exact second of transfer expiry.
- Guaranteed upper bound on file retention in storage (`retention_period + grace_period = 48 hours maximum`).

