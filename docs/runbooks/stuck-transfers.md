# Operational Runbook: Diagnosing & Remediating Stuck Transfers

**Reference:** Sections 12, 15, and 18  

---

## 1. Symptoms & Triggers
A transfer is considered "stuck" if:
- It has remained in `PENDING` or `UPLOADING` state for > 2 hours without chunk activity.
- The sender client disconnected, closed the browser, or lost network connection mid-stream.
- A user reports that their 200 GB quota remains reserved even though an upload was abandoned.

---

## 2. Diagnostic Steps

### Step 1: Query Stuck Transfers in MySQL
```sql
SELECT t.id, t.sender_id, t.state, t.original_filename, t.file_size_bytes, t.created_at,
       us.id AS session_id, us.state AS session_state, us.last_activity_at
FROM transfers t
LEFT JOIN upload_sessions us ON t.id = us.transfer_id
WHERE t.state IN ('PENDING', 'UPLOADING')
  AND t.created_at < DATE_SUB(NOW(), INTERVAL 2 HOUR)
ORDER BY t.created_at ASC;
```

### Step 2: Inspect Committed vs Expected Chunks
```sql
SELECT upload_session_id, chunk_index, state, byte_length, committed_at
FROM file_chunks
WHERE upload_session_id = 'TARGET_SESSION_ID'
ORDER BY chunk_index ASC;
```
- If partial chunks are committed: The sender can resume from the latest committed chunk index without re-uploading completed parts.
- If no chunks committed for > 48 hours: The session is eligible for automated reconciliation.

---

## 3. Remediation Procedures

### Procedure A: Client Resumption (Preferred)
1. Instruct the user to re-open the transfer in the HoneyShare Web Client or Desktop Client.
2. The client fetches the manifest via `GET /api/v1/upload-sessions/{sessionId}/manifest`.
3. The client resumes uploading only the uncommitted chunk indices.

### Procedure B: Sender Cancellation
The sender can cancel the transfer via:
```http
POST /api/v1/transfers/{transferId}/cancel
Authorization: Bearer <sessionToken>
```
The backend immediately:
1. Transitions `transfers.state = 'CANCELLED'`.
2. Releases the active quota reservation in `quota_reservations`.
3. Queues asynchronous cleanup of any partial parts in object storage.

### Procedure C: Administrative Force Cancellation (Emergency)
If the sender account is disabled or inaccessible:
```sql
-- 1. Mark transfer cancelled
UPDATE transfers SET state = 'CANCELLED', updated_at = NOW() WHERE id = 'TARGET_TRANSFER_ID';

-- 2. Release active quota reservation
UPDATE quota_reservations 
SET state = 'RELEASED', released_at = NOW() 
WHERE transfer_id = 'TARGET_TRANSFER_ID' AND state = 'ACTIVE';

-- 3. Abort upload session
UPDATE upload_sessions 
SET state = 'ABORTED' 
WHERE transfer_id = 'TARGET_TRANSFER_ID' AND state = 'ACTIVE';
```
Then run the automated `ReconciliationService` to abort the remote S3 multipart upload.

