# HoneyShare Disaster Recovery Plan & Restore Drills

**Specification Reference:** Sections 14, 15, and 16  
**Status:** Active  

---

## 1. Overview & Recovery Objectives

HoneyShare employs a split-state architecture:
1. **Relational Metadata (MySQL):** Transfers, chunk manifests, user accounts, sessions, invitations, devices, and transactional outbox.
2. **Binary Payload Storage (S3 / MinIO):** Encrypted or standard chunks assembled into objects under `transfers/{transferId}/content.bin`.

### Recovery Targets
- **Recovery Time Objective (RTO):** < 30 minutes for complete service restoration from cold backup.
- **Recovery Point Objective (RPO):** < 1 hour for relational state; zero payload loss for transfers marked `STORED_AND_VERIFIED`.

---

## 2. Backup Architecture

### 2.1 MySQL Database Backups
- **Scheduled Automated Dumps:** Daily full logical backups taken using `mysqldump` with `--single-transaction --quick --routines --triggers`.
- **Binary Logging (Production):** MySQL binlogs enabled with 7-day retention for point-in-time recovery (PITR).
- **Execution Script:** `scripts/backup-db.ps1` (Windows) / `scripts/backup-db.sh` (Linux).

### 2.2 Object Storage Protection
- **Object Versioning:** Enabled on production S3 buckets to prevent accidental deletion or ransomware overwrites.
- **Lifecycle Management:** Storage provider lifecycle rules align with HoneyShare's 24-hour default retention clock and 24-hour active download grace period.

---

## 3. Step-by-Step Restore Drill Procedure

A disaster recovery drill must be practiced regularly to ensure that backups are valid and restorable.

### Step 1: Create a Fresh Backup Snapshot
```powershell
./scripts/backup-db.ps1 -OutputDir "./backups"
```
Or in Bash:
```bash
./scripts/backup-db.sh ./backups
```
This generates a timestamped file: `./backups/honeyshare-backup-YYYYMMDD-HHMMSS.sql`.

### Step 2: Simulate Failure or Provision Clean Target Database
If conducting a drill on local Docker:
```bash
docker compose down -v
docker compose up -d mysql minio minio-init
```
Wait for MySQL container to become healthy (`docker ps`).

### Step 3: Execute Restore Drill
Run the automated restore script:
```powershell
./scripts/restore-db.ps1 -BackupFile "./backups/honeyshare-backup-YYYYMMDD-HHMMSS.sql"
```
Or in Bash:
```bash
./scripts/restore-db.sh ./backups/honeyshare-backup-YYYYMMDD-HHMMSS.sql
```

### Step 4: Verify Post-Restore Data Consistency
The script automatically queries table row counts. Verify that:
1. `users` and `user_sessions` tables match expected user counts.
2. `transfers` table contains all committed transfers.
3. `devices` and `transfer_key_envelopes` are restored without corruption.

### Step 5: Run Automated Storage Reconciliation
After database restoration, execute the HoneyShare reconciliation service to align MySQL state with actual object store contents:
- Any transfer marked `STORED_AND_VERIFIED` where the S3 object is missing will be detected and logged as a critical data discrepancy.
- Any orphaned multipart uploads (>48 hours) will be automatically aborted and their quota reservations released.

### Step 6: Verify Outbox Processing
Verify that the `outbox_worker` resumes processing pending events (`outbox_events` with status `PENDING` or `RETRYING`) with exponential backoff and jitter.

---

## 4. Runbook Checklist

- [x] Tested logical backup script (`scripts/backup-db.ps1` / `scripts/backup-db.sh`).
- [x] Tested logical restore drill script (`scripts/restore-db.ps1` / `scripts/restore-db.sh`).
- [x] Bounded-memory streaming SHA-256 verification validated by `LargeFileTransferBenchmarkTest`.
- [x] 100 GB multipart constraint validation confirmed ($\le 10,000$ parts, $\ge 5\,\text{MiB}$ part size).
- [x] S3/MinIO reconciliation engine verified for orphaned uploads and storage discrepancies.

