# HoneyShare

[![HoneyShare CI](https://github.com/Sayan-pixel/HoneyShare/actions/workflows/ci.yml/badge.svg)](https://github.com/Sayan-pixel/HoneyShare/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-v0.1.0--beta-blue.svg)](https://github.com/Sayan-pixel/HoneyShare/releases/tag/v0.1.0-beta)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)

Reliable, cross-platform, server-backed file-sharing and delivery system.

Designed for dependable file delivery even when the recipient is offline or the sender temporarily loses connectivity.

---

## Zero-Cost Guarantee
HoneyShare is engineered with a **zero-cost local architecture**. All local default services (MinIO S3-compatible object store, local email catcher, local push logger) run entirely on your local machine with zero external cloud spend or API billing. No paid accounts are required.

---

## Core Features
- **Invitation-Only Beta (Phase 1):** Email & password authentication with strict invitation tokens.
- **Email Verification (Phase 1):** Email verification mandatory before file access.
- **Role-Based Access & MFA (Phase 1):** Mandatory MFA for administrators; optional for regular users.
- **Resumable Transfers (Phase 2):** Chunked/multipart uploads with manifest-based progress tracking and cryptographic SHA-256 verification.
- **Offline Recipient Inbox (Phase 3):** Recipients query their inbox anytime and download files when returning online.
- **Retention & Grace Period (Phase 3):** 24h default retention starting from `STORED_AND_VERIFIED`; 24h active download grace period with immutable grace clock.
- **Asynchronous Deletion Engine (Phase 3):** Asynchronous storage deletion with confirmed remote deletion and automatic stored-quota release.
- **Storage Reconciliation (Phase 3):** Automatic cleanup of abandoned multipart upload sessions (>48h) and release of stale quota reservations.
- **Durable Deletion Receipt (Phase 3):** Senders verify remote durable storage via cryptographic receipt before deleting local files.
- **Atomic Quota Accounting (Phase 2 & 3):** 200 GB user quota with atomic reservation preventing concurrent upload over-subscription.
- **Transactional Outbox & Notifications (Phase 4):** Transactional outbox pattern, asynchronous worker with exponential backoff & jitter, generic push privacy (FR-NOTIFY-04), and customizable user notification preferences.
- **Dual Privacy Modes & E2E Key Architecture (Phase 5):**
  - Standard server-side encrypted mode.
  - End-to-End Encryption (E2E) protocol using X25519 key agreement, HKDF-SHA256, and AES-256-GCM.
  - Device public key registry storing ONLY public keys (rejection of private key PEMs).
  - User-controlled Argon2id recovery packages with zero server-side plaintext exposure.
  - Feature-flagged gate (`honeyshare.features.e2e-enabled=false`) until protocol review and client engines are verified.
- **Web Client Engine (Phase 6):** Modern, zero-host-dependency single page application served directly by Spring Boot at `http://localhost:8080/`, featuring Web Crypto API streaming hashing, drag-and-drop resumable upload with pause/resume, inbox downloads, notification feed, and interactive quota visualizer.
- **Large-File Hardening & Operations (Phase 7):**
  - Verified 1 GB, 10 GB, and 100 GB multipart constraints ($\le 10,000$ parts, $\ge 5\,\text{MiB}$ part size).
  - Bounded-memory streaming SHA-256 calculation ($O(1)$ heap overhead).
  - Executable MySQL disaster recovery drill scripts (`backup-db`, `restore-db`).
  - Production runbooks for stuck transfers, storage reconciliation, and abuse prevention.

---

## Technology Stack
- **Backend:** Java 17, Spring Boot 3.3.5, Spring Security, Spring Data JPA
- **Database:** MySQL 8.0 / 8.4 (InnoDB, UTC timestamps, Flyway migrations)
- **Object Storage:** S3-compatible (MinIO for local development)
- **ID Strategy:** ULID (26-character URL-safe sortable identifier)
- **Password & Key Hashing:** Argon2id via Bouncy Castle
- **MFA:** TOTP (RFC 6238) with backup recovery codes

---

## Key API Endpoints

### Identity & Authentication
- `POST /api/v1/auth/register` — Register using valid invitation token
- `POST /api/v1/auth/login` — Authenticate and start session
- `POST /api/v1/auth/logout` — Revoke active session
- `POST /api/v1/auth/email-verification/request` & `confirm` — Verify email
- `POST /api/v1/auth/mfa/enroll`, `confirm`, `verify`, `recovery` — Manage MFA
- `POST /api/v1/invitations` — Administrator invitation management
- `GET /api/v1/users/me` — Current authenticated user profile
- `GET /api/v1/users/me/quota` — Detailed quota status (limit, stored, reserved, remaining)

### Transfers & Uploads
- `POST /api/v1/transfers` — Create transfer and reserve quota atomically
- `GET /api/v1/transfers/{id}` — Authorize and view transfer status
- `GET /api/v1/transfers/sent` — List transfers sent by the authenticated user
- `POST /api/v1/transfers/{id}/upload-sessions` — Initiate multipart upload session
- `GET /api/v1/upload-sessions/{sessionId}/manifest` — Resumable upload manifest
- `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/authorize` — Issue pre-signed upload URL
- `POST /api/v1/upload-sessions/{sessionId}/parts/{partIndex}/commit` — Commit uploaded part
- `POST /api/v1/upload-sessions/{sessionId}/complete` — Finalize multipart assembly & verify SHA-256
- `POST /api/v1/transfers/{id}/cancel` — Cancel transfer and release quota
- `POST /api/v1/transfers/{id}/recipients` — Add recipient to transfer
- `DELETE /api/v1/transfers/{id}/recipients/{userId}` — Revoke recipient
- `POST /api/v1/transfers/{id}/sender-deletion-receipt` — Request cryptographic durable-storage receipt

### Recipient Inbox & Downloads
- `GET /api/v1/inbox` — List received transfers in recipient inbox
- `GET /api/v1/inbox/{transferId}` — View incoming transfer details
- `POST /api/v1/transfers/{id}/download-sessions` — Authorize download session (enforcing 24h grace period)
- `POST /api/v1/download-sessions/{id}/complete` — Record verified receipt acknowledgement
- `POST /api/v1/download-sessions/{id}/cancel` — Abandon download session

### Notifications & User Preferences
- `GET /api/v1/notifications` — Paged list of in-app notifications
- `POST /api/v1/notifications/{id}/read` — Mark notification read
- `POST /api/v1/notifications/read-all` — Mark all notifications read
- `GET /api/v1/notifications/unread-count` — Unread notification count
- `GET /api/v1/users/me/preferences` — Get notification preferences
- `PATCH /api/v1/users/me/preferences` — Update channel and event preferences (email, push, in-app)

### Devices & E2E Key Management (Feature-Flagged)
- `POST /api/v1/users/me/devices` — Register client device public key
- `GET /api/v1/users/me/devices` — List my registered devices
- `POST /api/v1/users/me/devices/{deviceId}/revoke` — Revoke device immediately
- `GET /api/v1/users/{userId}/devices/active` — Active public keys of recipient for key wrapping
- `POST /api/v1/transfers/{transferId}/e2e-envelopes` — Store wrapped key envelopes
- `GET /api/v1/transfers/{transferId}/e2e-envelopes/{deviceId}` — Retrieve device-specific key envelope
- `POST /api/v1/users/me/e2e-recovery` — Store user-controlled encrypted recovery package
- `GET /api/v1/users/me/e2e-recovery` — Retrieve recovery package and Argon2id salt

---

## Local Development Setup

### Prerequisites
- JDK 17 (e.g. Eclipse Adoptium Temurin 17)
- Apache Maven 3.9+
- Docker & Docker Compose (or local MySQL 8.0 & MinIO)

### 1. Environment Configuration
Copy the environment template:
```bash
cp .env.example .env
```
Ensure credentials and configuration match your local setup.

### 2. Start Infrastructure (Docker Compose)
Start local MySQL and S3 storage:
```bash
docker compose up -d
```
- S3 Storage Web Console is available at `http://localhost:9001` (user: `minioadmin`, pass: `minioadmin`).  
- S3 API endpoint is on `http://localhost:9000`.
- MySQL is exposed on host port `3307` (container port `3306`, database: `honeyshare`, user: `honeyshare`, pass: `changeme`).

### 3. Run Backend & Open Web Client
```bash
cd backend
mvn clean spring-boot:run
```
Open your browser at `http://localhost:8080/` to use the HoneyShare Web Client. Actuator health and metrics run on port `8081`.

### 4. Run Test Suite
```bash
cd backend
mvn test
```
All **126 tests** pass out of the box with zero failures.

---

## Disaster Recovery & Operational Scripts

### Database Backup & Restore Drills
- **Create Database Backup:**
  ```powershell
  ./scripts/backup-db.ps1 -OutputDir "./backups"
  ```
  Or in Bash:
  ```bash
  ./scripts/backup-db.sh ./backups
  ```
- **Execute Restore Drill:**
  ```powershell
  ./scripts/restore-db.ps1 -BackupFile "./backups/honeyshare-backup-YYYYMMDD-HHMMSS.sql"
  ```
  Or in Bash:
  ```bash
  ./scripts/restore-db.sh ./backups/honeyshare-backup-YYYYMMDD-HHMMSS.sql
  ```
- **API Smoke Test:**
  ```powershell
  ./scripts/smoke-test.ps1 -BaseUrl "http://localhost:8080"
  ```
  Or in Bash:
  ```bash
  ./scripts/smoke-test.sh http://localhost:8080
  ```

---

## Architecture Documentation & Decisions (ADRs)
- [ADR-001: Modular Monolith](docs/adr/ADR-001-modular-monolith.md)
- [ADR-002: S3-Compatible Object Storage](docs/adr/ADR-002-object-storage.md)
- [ADR-003: Resumable Transfers & Manifests](docs/adr/ADR-003-resumable-transfers.md)
- [ADR-004: Dual Privacy Modes (Standard vs End-to-End Encryption)](docs/adr/ADR-004-privacy-modes.md)
- [ADR-007: Transfer Retention Lifecycle and Fixed Grace Period](docs/adr/ADR-007-retention-and-grace-period.md)
- [ADR-009: Sender-Side Local File Deletion and Durable Storage Receipts](docs/adr/ADR-009-sender-local-deletion.md)
- [ADR-012: User-Controlled E2E Key Recovery and Encrypted Recovery Packages](docs/adr/ADR-012-e2e-recovery.md)
- [ADR-018: Quota Accounting, Concurrency Safety, and Storage Reconciliation](docs/adr/ADR-018-quota-accounting-and-reconciliation.md)
- [ADR-020: Notifications, Transactional Outbox, Deduplication, and Push Privacy](docs/adr/ADR-020-notifications.md)
- [End-to-End Encryption (E2E) Protocol & Key Management Specification](docs/e2e-key-management.md)
- [Disaster Recovery Plan & Restore Drills](docs/disaster-recovery.md)
- [Desktop Transfer Queue Engine Architecture](docs/desktop-engine-architecture.md)
- [Runbook: Diagnosing & Remediating Stuck Transfers](docs/runbooks/stuck-transfers.md)
- [Runbook: Storage Reconciliation & Orphan Cleanup](docs/runbooks/storage-reconciliation.md)
- [Runbook: Rate Limiting, Abuse Controls & Security](docs/runbooks/rate-limiting-and-abuse.md)
- [Recorded Assumptions](docs/assumptions.md)
- [Initial Threat Model](docs/threat-model.md)
