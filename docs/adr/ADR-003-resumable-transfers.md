# ADR-003: Resumable Transfers and Manifest-Based Progress Tracking

## Status
Accepted

## Context
Per Specification Section 1.1, 3.5, 3.7, 5 (FR-RES-01 through FR-RES-08), and 11:
- Transfers must support large files (target 100 GB) reliably over lossy or intermittent network connections.
- Whole-file memory buffering in the server is prohibited.
- Transfers must be resumable after network interruptions, client crashes, or backend restarts.
- Progress tracking must be authoritative and verifiable. Object storage ETags on multipart objects cannot be assumed to be identical to simple single-part full-file MD5 or SHA-256 hashes.
- Incomplete, corrupted, or unverified files must never be marked as successful (`STORED_AND_VERIFIED`).

## Decision
1. **Multipart Chunk Architecture:**
   - Files are split into configurable chunk sizes (default 10 MB, configurable up to higher sizes for 100 GB files).
   - The backend tracks an upload manifest in MySQL (`upload_sessions` and `file_chunks`), where each chunk has an explicit state (`PENDING`, `UPLOADING`, `COMMITTED`, `VERIFIED`, `FAILED`).
2. **Short-Lived Pre-Signed Upload URLs:**
   - The client requests part authorization via `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/authorize`.
   - The backend returns a pre-signed S3 URL scoped specifically to that part index with a short TTL (default 300 seconds).
   - Large file bytes flow directly from the client to the object storage service, keeping backend API servers lightweight and responsive.
3. **Idempotent Chunk Commits:**
   - After uploading a part to storage, the client calls `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/commit` with the storage-returned ETag and client-calculated part checksum.
   - If a network error occurs during commit, retrying with the same ETag is idempotent.
4. **Resumption Reconciliation:**
   - Upon reconnecting, the client calls `GET /api/v1/upload-sessions/{sessionId}/manifest` to retrieve the list of committed parts.
   - Any part marked `COMMITTED` is skipped, resuming only missing or unverified chunks without retransmitting verified data.
5. **Two-Stage Final Verification:**
   - Upon client completion request, the backend instructs S3 to assemble the multipart upload.
   - The backend then verifies the completed object size and streams the assembled object through a fixed 64 KB buffer to compute the authoritative SHA-256 hash (`ChecksumService`).
   - If the verified hash matches `expectedSha256`, the transfer transitions to `STORED_AND_VERIFIED`, quota is committed, and a `DurableStorageReceipt` is returned.
   - If the hash mismatches or storage size does not match expected size, the transfer is marked `FAILED` immediately and no receipt is issued.

## Consequences
- **Positive:**
  - Network drops mid-transfer do not require restarting from byte 0.
  - Zero false-success states: transfers are proven verified before recipients can download.
  - Senders can safely delete local source files only after receiving and validating the `DurableStorageReceipt`.
- **Negative / Mitigations:**
  - Streaming SHA-256 verification of very large objects incurs backend disk/network read bandwidth from object storage. For 100 GB files in production, this verification is queued asynchronously via `background_jobs` to prevent HTTP request timeouts.

