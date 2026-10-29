# Operational Runbook: Storage Reconciliation & Orphan Cleanup

**Reference:** Sections 3.5, 12, 15, and 18  

---

## 1. Overview
In a high-throughput, large-file transfer system (up to 100 GB files), network drops, client crashes, or abrupt cancellations can leave:
1. **Abandoned Multipart Uploads:** S3 multipart uploads initiated but never completed or aborted, consuming storage space and costs.
2. **Stale Quota Reservations:** Quota reserved in MySQL for uploads that were never finalized.
3. **Storage Discrepancies:** Transfers marked `STORED_AND_VERIFIED` in MySQL where the remote object is unexpectedly missing.

The `ReconciliationService` runs as an automated scheduled background job and can also be triggered administratively.

---

## 2. Automated Scheduled Rules
The backend runs reconciliation according to the following thresholds:
- **Abandoned Multipart Timeout:** Sessions in `ACTIVE` state with no progress for > 48 hours are automatically aborted.
- **Quota Release:** The associated quota reservation is released back to the user's available 200 GB quota.
- **Remote S3 Abort:** An `AbortMultipartUpload` call is issued to MinIO/S3 using the saved `storage_upload_id` to release remote disk parts.

---

## 3. Investigating Discrepancies

### Step 1: Check Application Logs for Discrepancy Alerts
Search logs for:
`CRITICAL DATA DISCREPANCY: Transfer <id> marked AVAILABLE_TO_RECIPIENT but object ... is missing`

### Step 2: Query Unreconciled Stale Sessions in MySQL
```sql
SELECT us.id, us.transfer_id, us.storage_upload_id, us.created_at, us.last_activity_at
FROM upload_sessions us
WHERE us.state = 'ACTIVE'
  AND us.last_activity_at < DATE_SUB(NOW(), INTERVAL 48 HOUR);
```

### Step 3: Verify Remote S3 Multipart Uploads via CLI
Using MinIO Client (`mc`) or AWS CLI:
```bash
# List all active multipart uploads in bucket
mc multipart list local/honeyshare-files

# Or with AWS CLI
aws s3api list-multipart-uploads --bucket honeyshare-files --endpoint-url http://localhost:9000
```

### Step 4: Manually Aborting Stale Uploads (If Required)
If a multipart upload remains in MinIO after database cleanup:
```bash
# Abort specific upload
mc multipart abort local/honeyshare-files/transfers/TARGET_TRANSFER_ID/content.bin TARGET_UPLOAD_ID
```

