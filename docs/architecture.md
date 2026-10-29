# HoneyShare Architecture & Modular Monolith Design

## 1. High-Level Architecture Overview

HoneyShare is architected as a modular monolith in Java (Spring Boot) backed by MySQL for transactional metadata and S3-compatible object storage for file bytes.

```
+-------------------------------------------------------------------+
|                        Client Applications                        |
|       (Web: React/TS; Desktop: Tauri/Rust; Mobile: Native)        |
+-------------------------------------------------------------------+
                                  |
                                  | HTTPS (REST / JSON)
                                  v
+-------------------------------------------------------------------+
|                     HoneyShare Backend (Spring Boot)              |
|                                                                   |
|  +-----------------------+     +-------------------------------+  |
|  |     Control Plane     |     |         Recovery Plane        |  |
|  | - Identity & Auth     |     | - Idempotency & Progress Manifest|
|  | - Transfers & Sessions|     | - Storage Reconciliation      |  |
|  | - Atomic Quotas       |     | - Expiry & Deletion Engine    |  |
|  +-----------------------+     +-------------------------------+  |
|                                                                   |
|                       +-------------------+                       |
|                       |    Data Plane     |                       |
|                       | - Short-lived     |                       |
|                       |   Pre-signed URLs |                       |
|                       | - Streaming proxy |                       |
|                       +-------------------+                       |
+-------------------------------------------------------------------+
                |                                   |
                | SQL / JDBC                        | S3 API (Signed URLs)
                v                                   v
+-------------------------------+   +-------------------------------+
|      MySQL 8.0 Metadata       |   |   S3-Compatible Object Store  |
| - Users & Invitations         |   | - Chunks & Multi-part Objects |
| - Sessions & Audit Events     |   | - Completed File Objects      |
| - Manifests & Quota Rows      |   | - Bucket Lifecycle Rules      |
+-------------------------------+   +-------------------------------+
```

## 2. Separation of Planes

1. **Control Plane:**
   - User identity, invitation lifecycle, authentication, MFA verification.
   - Transfer states and recipient state machines.
   - Atomic quota reservations and tracking.
   - Outbox pattern for notifications.
   - All state mutations are transactional in MySQL.

2. **Data Plane:**
   - File bytes transfer directly between client and S3 object storage via short-lived, pre-signed upload/download URLs issued by the control plane.
   - For environments or proxies where direct storage access is constrained, streaming chunk proxying is supported.
   - No entire files are buffered in server JVM heap memory.

3. **Recovery Plane:**
   - Periodic reconciliation workers detect abandoned multipart uploads, orphaned objects, and stale quota reservations.
   - Retention policy worker enforces 24h expiration and 24h active-download grace period.
   - Deletion worker verifies physical object removal with S3 before marking database records `DELETED`.

## 3. Module Boundaries
```
com.honeyshare
├── common/              # Common utilities, ULID, crypto tokens, domain exceptions
├── config/              # Properties, security configuration, password encoder
├── identity/            # User onboarding, invitations, auth, MFA, password reset
│   ├── domain/
│   ├── port/
│   ├── service/
│   └── adapter/web/
├── transfers/           # Transfers, upload sessions, chunks, state transitions
├── storage/             # StoragePort interface & MinIO/S3 adapters
├── integrity/           # SHA-256 streaming hashing and verification
├── quotas/              # Atomic quota reservation and reconciliation
├── audit/               # Security event audit logging
├── jobs/                # Durable database-backed background jobs
└── retention/           # Lifecycle, expiry, and deletion processing
```

## 4. State Machine Design
All status transitions are guarded by explicit business rules:
- An upload reaches `STORED_AND_VERIFIED` only after all expected chunks are committed, full-file size is validated, and checksum matches.
- A transfer cannot be downloaded until its state is `AVAILABLE_TO_RECIPIENT`.
- Expiry blocks new download authorizations while allowing ongoing eligible sessions up to 24 hours of grace.

