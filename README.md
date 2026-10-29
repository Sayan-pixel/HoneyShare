<div align="center">

# 🍯 HoneyShare

### *Enterprise-Grade, Resumable, Server-Backed File Sharing Platform*

[![HoneyShare CI](https://github.com/Sayan-pixel/HoneyShare/actions/workflows/ci.yml/badge.svg)](https://github.com/Sayan-pixel/HoneyShare/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/release-v0.1.0--beta-blue.svg?style=flat&logo=github)](https://github.com/Sayan-pixel/HoneyShare/releases/tag/v0.1.0-beta)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg?style=flat&logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg?style=flat&logo=springboot)](https://spring.io/projects/spring-boot)
[![MySQL 8.0](https://img.shields.io/badge/MySQL-8.0-blue.svg?style=flat&logo=mysql)](https://www.mysql.com/)
[![S3 Compatible](https://img.shields.io/badge/Storage-S3%20%2F%20MinIO-red.svg?style=flat&logo=amazons3)](https://min.io/)
[![Tests](https://img.shields.io/badge/tests-126%20passed-success.svg?style=flat)](https://github.com/Sayan-pixel/HoneyShare/actions)

<p align="center">
  <b>Designed for resilient file delivery even when recipients are offline or senders experience intermittent network loss.</b>
  <br />
  Includes parallel multipart uploads, Web Worker SHA-256 hashing, byte-range streaming, atomic quotas, and zero cloud spend.
</p>

[✨ Key Features](#-key-features) •
[⚡ Quick Start](#-quick-start-in-3-minutes) •
[🏛️ Architecture](#-system-architecture) •
[📡 API Reference](#-key-api-endpoints) •
[🛡️ Security Invariants](#-security--integrity-invariants) •
[📚 Documentation & ADRs](#-architecture-decision-records-adrs)

---

</div>

## 🌟 Highlights & Zero-Cost Architecture

HoneyShare is engineered with a **zero-cost local architecture**:
* Runs completely on your local workstation with Docker Compose.
* MinIO provides an S3-compatible object storage layer ($0 cloud spend).
* In-memory rate limiting and transactional outbox email/push loggers eliminate external SaaS dependencies.
* Verified for files up to **100 GB** with bounded $O(1)$ heap memory overhead.

---

## ✨ Key Features

| Capability | Technical Implementation | Benefit |
| :--- | :--- | :--- |
| ⚡ **Concurrent Chunk Uploads** | 3x parallel asynchronous worker pool via S3 multipart | 3x–5x upload speedup on large payloads |
| 🛡️ **Streaming SHA-256 Hashing** | Background Web Worker with pure-JS fallback for non-secure contexts | Zero UI freezing during heavy cryptographic computation |
| 📶 **Resumable HTTP Range Streaming** | RFC 7233 `Accept-Ranges: bytes` & HTTP 206 Partial Content | Resume interrupted downloads; stream audio/video directly |
| 🔒 **Hardened Authentication** | Argon2id password hashing + RFC 6238 TOTP MFA + Recovery Codes | Protection against brute-force and credential stuffing |
| 📊 **Atomic Quota Accounting** | 200 GB user limits with atomic database reservations | Strict over-subscription prevention under concurrent loads |
| ⏳ **Offline Recipient Inbox** | Asynchronous delivery + 24-hour immutable active-download grace clock | Recipients retrieve transfers whenever they come online |
| 🧹 **Automated Reconciliation** | Background sweeps for abandoned multipart sessions (>48h) | Automatic quota restoration and storage garbage collection |
| 🧾 **Durable Storage Receipts** | Cryptographically signed storage receipts | Senders safely delete local copies only after storage verification |

---

## 🏛️ System Architecture

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   HoneyShare Web Client (SPA)                         │
│  [Drag & Drop Upload]   [Web Worker SHA-256]   [Range Downloader]     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTP / REST / Byte Streams
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                HoneyShare Backend (Spring Boot 3.3.5)                  │
│                                                                        │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌────────────┐  │
│  │ Identity & MFA        │  │ Transfer Service      │  │ Outbox     │  │
│  │ (Argon2id / TOTP)     │  │ (Multipart / Chunks)  │  │ Worker     │  │
│  └───────────────────────┘  └───────────────────────┘  └────────────┘  │
│  ┌───────────────────────┐  ┌───────────────────────┐  ┌────────────┐  │
│  │ Quota Controller      │  │ Retention & Deletion  │  │ Rate       │  │
│  │ (200 GB Reservations) │  │ (24h Grace Clock)     │  │ Limiter    │  │
│  └───────────────────────┘  └───────────────────────┘  └────────────┘  │
└───────────────────┬─────────────────────────────────┬──────────────────┘
                    │                                 │
                    ▼                                 ▼
┌──────────────────────────────────────┐  ┌──────────────────────────────┐
│        MySQL 8.0 Database            │  │  S3-Compatible Object Store  │
│  (Flyway V001-V007, UTC Timestamps)  │  │   (MinIO / AWS S3 Bucket)    │
└──────────────────────────────────────┘  └──────────────────────────────┘
```

---

## ⚡ Quick Start in 3 Minutes

### Prerequisites
* **Java**: JDK 17+ (Eclipse Adoptium Temurin recommended)
* **Build Tool**: Apache Maven 3.9+
* **Containers**: Docker & Docker Compose

### 1. Clone & Configure Environment
```bash
git clone https://github.com/Sayan-pixel/HoneyShare.git
cd HoneyShare
cp .env.example .env
```

### 2. Start Local Infrastructure
Launch MySQL 8.0 and MinIO S3 object storage in background containers:
```bash
docker compose up -d
```
* **S3 Storage Console**: [http://localhost:9001](http://localhost:9001) (`minioadmin` / `minioadmin`)
* **S3 API Port**: `http://localhost:9000`
* **MySQL Database**: `localhost:3307` (`honeyshare` / `changeme`)

### 3. Run the Backend Application
```powershell
cd backend
mvn clean spring-boot:run
```
* **Web Client**: Open your browser at **[http://localhost:8080/](http://localhost:8080/)**
* **Health & Actuator**: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)

### 4. Run Automated Test Suite
```powershell
cd backend
mvn test
```
> **Results:** All **126 automated unit and component tests** pass out of the box with zero failures.

---

## 📡 Key API Endpoints

### 🔐 Authentication & Identity
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Register new account via valid invitation token |
| `POST` | `/api/v1/auth/login` | Authenticate and obtain stateful session bearer token |
| `POST` | `/api/v1/auth/logout` | Invalidate and revoke active session |
| `POST` | `/api/v1/auth/mfa/enroll` | Initialize TOTP secret setup with QR code payload |
| `POST` | `/api/v1/auth/mfa/verify` | Verify 6-digit TOTP code and issue recovery codes |
| `GET` | `/api/v1/users/me` | Fetch authenticated user profile and roles |
| `GET` | `/api/v1/users/me/quota` | Get live quota metrics (limit, stored, reserved, available) |

### 📤 Transfers & Resumable Uploads
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/transfers` | Create transfer and atomically reserve quota |
| `POST` | `/api/v1/transfers/{id}/upload-sessions` | Initiate multipart S3 upload session |
| `GET` | `/api/v1/upload-sessions/{id}/manifest` | Fetch upload manifest for resuming interrupted parts |
| `PUT` | `/api/v1/upload-sessions/{id}/parts/{n}` | Upload chunk part directly via streaming proxy |
| `POST` | `/api/v1/upload-sessions/{id}/complete` | Assemble multipart object & execute SHA-256 verification |
| `POST` | `/api/v1/transfers/{id}/sender-deletion-receipt` | Retrieve cryptographically signed durable-storage receipt |

### 📥 Recipient Inbox & Downloads
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/v1/inbox` | List verified incoming transfers ready for download |
| `POST` | `/api/v1/transfers/{id}/download-sessions` | Authorize download session (starts 24h grace period) |
| `GET` | `/api/v1/download-sessions/{id}/stream` | Stream file content with RFC 7233 byte-range support |
| `POST` | `/api/v1/download-sessions/{id}/complete` | Submit verified receiver acknowledgement |

---

## 🛡️ Security & Integrity Invariants

1. **Argon2id Key Derivation**: High-memory parameters (`m=65536, t=3, p=1`) resist GPU cracking.
2. **Stateless Session Tokens with DB Revocation**: Bearer tokens are stored exclusively as SHA-256 hashes (`token_hash`) in MySQL; plaintext tokens are never persisted or logged.
3. **Storage Key Isolation**: S3 object keys are generated as opaque ULIDs (`transfers/{ulid}/content.bin`). Original filenames are sanitized to prevent directory traversal.
4. **Bounded Memory Execution**: Full-file streaming SHA-256 calculations use fixed 64 KiB transfer buffers, preventing Out-Of-Memory (OOM) crashes even for 100 GB uploads.
5. **E2E Feature Guard**: End-to-End Encryption protocol endpoints remain disabled behind `honeyshare.features.e2e-enabled=false` until cryptographic client review passes.

---

## 🛠️ Disaster Recovery & Operational Drills

Executable PowerShell and Bash operational runbooks are located in [`scripts/`](scripts/):

* **Database Hot Backup**:
  ```powershell
  ./scripts/backup-db.ps1 -OutputDir "./backups"
  ```
* **Disaster Recovery Restore Drill**:
  ```powershell
  ./scripts/restore-db.ps1 -BackupFile "./backups/honeyshare-backup-YYYYMMDD.sql"
  ```
* **API Smoke Test Verification**:
  ```powershell
  ./scripts/smoke-test.ps1 -BaseUrl "http://localhost:8080"
  ```

---

## 📚 Architecture Decision Records (ADRs)

HoneyShare's design is documented through comprehensive Architecture Decision Records:

* [ADR-001: Modular Monolith Architecture](docs/adr/ADR-001-modular-monolith.md)
* [ADR-002: S3-Compatible Object Storage Abstraction](docs/adr/ADR-002-object-storage.md)
* [ADR-003: Resumable Transfers & Manifests](docs/adr/ADR-003-resumable-transfers.md)
* [ADR-004: Dual Privacy Modes (Standard vs E2E)](docs/adr/ADR-004-privacy-modes.md)
* [ADR-007: Retention Lifecycle & Fixed Grace Clock](docs/adr/ADR-007-retention-and-grace-period.md)
* [ADR-009: Sender-Side Deletion & Storage Receipts](docs/adr/ADR-009-sender-local-deletion.md)
* [ADR-012: User-Controlled E2E Key Recovery](docs/adr/ADR-012-e2e-recovery.md)
* [ADR-018: Quota Accounting & Storage Reconciliation](docs/adr/ADR-018-quota-accounting-and-reconciliation.md)
* [ADR-020: Transactional Outbox & Notification Privacy](docs/adr/ADR-020-notifications.md)

---

## 👤 Author & Maintainer

* **Author**: Sayan ([@Sayan-pixel](https://github.com/Sayan-pixel))
* **Project**: HoneyShare v0.1.0-beta
* **Repository**: [https://github.com/Sayan-pixel/HoneyShare](https://github.com/Sayan-pixel/HoneyShare)

<div align="center">
  <sub>Built with ❤️ using Spring Boot, MySQL, and S3-Compatible Storage.</sub>
</div>
