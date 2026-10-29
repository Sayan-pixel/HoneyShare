# HoneyShare Desktop Transfer Queue Engine Architecture

**Specification Reference:** Sections 3.7, 6, and Phase 6  
**Status:** Architecture Design & Client Reference  

---

## 1. Overview & Objectives

While the Web Client provides frictionless browser transfers, transferring multi-gigabyte or 100 GB files over unreliable networks requires a **persistent desktop transfer engine** with:
1. **Durable Local State:** Transfers and chunk progress survive client restarts, crashes, and OS reboots.
2. **Network Resilience & Backoff:** Automatic exponential backoff with jitter on disconnects, resuming without re-uploading committed chunks.
3. **Bounded Concurrency & Backpressure:** Fixed thread pools (default 4 concurrent part uploads) to avoid socket exhaustion.
4. **Hardware Secure Storage:** Device private keys stored in OS-level credential vaults (Windows DPAPI, macOS Keychain, Linux Secret Service).
5. **Accurate OS Power/Sleep Integration:** Handles sleep/wake cycles gracefully without corrupting in-flight chunks.

---

## 2. Queue Engine Architecture

```
+-------------------------------------------------------------------------+
|                         HoneyShare Desktop Client                       |
|                                                                         |
|  +--------------------+    +--------------------+    +---------------+  |
|  |   UI / Tray View   |<-->|    Queue Manager   |<-->|  Local State  |  |
|  |  (React/Electron)  |    |  (Priority Queue)  |    | (SQLite/JSON) |  |
|  +--------------------+    +--------------------+    +---------------+  |
|                                     |                                   |
|                +--------------------+-------------------+               |
|                |                                        |               |
|         +-------------+                          +-------------+        |
|         | Chunk Worker|                          | Chunk Worker|        |
|         | (Part i..n) |                          | (Part i..n) |        |
|         +-------------+                          +-------------+        |
|                |                                        |               |
|                +--------------------+-------------------+               |
|                                     |                                   |
|                    +----------------------------------+                 |
|                    |   Platform Secure Key Vault      |                 |
|                    | (DPAPI / Keychain / SecretServ)  |                 |
|                    +----------------------------------+                 |
+-------------------------------------------------------------------------+
                                      | HTTPS (Pre-signed URLs)
                                      v
                         +--------------------------+
                         | HoneyShare S3 / MinIO    |
                         +--------------------------+
```

---

## 3. Persistent Local State Model

The desktop engine maintains an embedded SQLite database (`~/.honeyshare/transfers.db`) or transactional JSON journal tracking local tasks:

```sql
CREATE TABLE local_transfers (
    transfer_id          TEXT PRIMARY KEY,
    file_path            TEXT NOT NULL,
    file_size_bytes      INTEGER NOT NULL,
    expected_sha256      TEXT NOT NULL,
    privacy_mode         TEXT NOT NULL,
    session_id           TEXT,
    total_parts          INTEGER NOT NULL,
    completed_parts      INTEGER NOT NULL DEFAULT 0,
    state                TEXT NOT NULL, -- QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
    error_message        TEXT,
    created_at           DATETIME NOT NULL,
    updated_at           DATETIME NOT NULL
);

CREATE TABLE local_part_journal (
    transfer_id          TEXT NOT NULL,
    part_index           INTEGER NOT NULL,
    byte_offset          INTEGER NOT NULL,
    byte_length          INTEGER NOT NULL,
    part_sha256          TEXT,
    etag                 TEXT,
    status               TEXT NOT NULL, -- PENDING, UPLOADING, COMMITTED, FAILED
    PRIMARY KEY (transfer_id, part_index)
);
```

### Crash Recovery Protocol
1. On desktop engine launch, the `QueueManager` reads all records with state `RUNNING` or `QUEUED`.
2. For each active transfer, the engine contacts the backend: `GET /api/v1/upload-sessions/{sessionId}/manifest`.
3. It reconciles local journal state with the backend manifest:
   - Parts marked `COMMITTED` on the server are marked `COMMITTED` locally.
   - Any part marked `UPLOADING` at crash time is reverted to `PENDING` and re-read from local disk.
4. Upload seamlessly resumes from the first uncommitted part index.

---

## 4. Secure Credential & Private Key Storage

In accordance with FR-SEC-03 and Phase 5 zero-knowledge invariants:
- **Windows:** Keys are encrypted using `CryptProtectData` (DPAPI) with user scope (`CRYPTPROTECT_UI_FORBIDDEN`).
- **macOS:** Stored as generic passwords in Apple Keychain with access-control list restricting access to HoneyShare.
- **Linux:** Stored via Freedesktop Secret Service API (GNOME Keyring / KWallet).

The desktop engine never stores plaintext device private keys or recovery passphrases in unencrypted configuration files.

