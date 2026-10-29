# ADR-009: Sender-Side Local File Deletion and Durable Storage Receipts

## Status
Accepted

## Context
Senders uploading large files (e.g. 10 GB to 100 GB) may wish to automatically delete their local copy after upload to reclaim local disk space. However, premature deletion of local source files (e.g., when the backend merely received the request, or while multipart assembly is incomplete) leads to irreversible data loss if the remote storage fails or verification fails.

## Decision
1. **Disabled by Default:** Sender clients must keep local source files by default. Automatic source deletion requires explicit user opt-in per transfer or in application settings.
2. **Durability Requirement:** Automatic local deletion is strictly prohibited until the sender client receives and validates an authoritative `SenderDeletionReceipt` issued by the backend (`POST /api/v1/transfers/{transferId}/sender-deletion-receipt`).
3. **Receipt Validation Conditions:** The backend will issue a receipt only when:
   - All expected multipart chunks have been received and verified.
   - The assembled object size matches `fileSizeBytes`.
   - The streaming full-file SHA-256 matches `expectedSha256`.
   - The transfer is committed in MySQL as `STORED_AND_VERIFIED` (or subsequent active delivery states).
4. **Cryptographic Receipt Token:** The receipt contains an HMAC/SHA-256 token binding `transferId`, `storageObjectKey`, `verifiedSha256`, `storedAt`, and `stateVersion`.
5. **Durability Limitation Transparency:** The receipt and client UI clearly state:
   *"Remote durable storage verified and acknowledged. Note: A remote receipt does not protect against regional storage disasters in single-region deployments."*

## Consequences
- Prevents catastrophic accidental data loss on sender workstations and mobile devices.
- Clear contract between client transfer engine and storage backend.

