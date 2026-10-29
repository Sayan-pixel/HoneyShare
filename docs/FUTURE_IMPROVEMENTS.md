# HoneyShare — Future Roadmap, Perfection & Optimization Guide
**Document Version:** 1.0  
**Target Architecture:** Zero-Cost, High-Reliability, Resumable Server-Backed File Delivery  

This document outlines architectural enhancements, performance optimizations, cryptographic hardening, and user-experience features recommended to evolve HoneyShare from a rock-solid beta into an enterprise-grade, high-performance file-sharing platform.

---

## 1. High-Priority Performance & Network Optimizations

### 1.1 Concurrent Chunk Upload Pipeline (3x–5x Speedup)
* **Current State:** The client uploads chunks sequentially one-by-one (`for (let i = 0; i < totalChunks; i++)`).
* **Optimization:** Implement a client-side concurrency pool (default: 3–4 parallel chunk streams via `Promise.all` with a semaphore).
* **Benefit:** Fully saturates available upload bandwidth on broadband and fiber connections, drastically reducing transfer times for files between 100 MB and 10 GB.

### 1.2 Web Workers for Client-Side Checksumming
* **Current State:** SHA-256 computation runs on the browser's main JavaScript thread. For multi-gigabyte files, this can cause micro-stutters in the browser UI.
* **Optimization:** Offload hashing to a dedicated background `Worker` (`hash-worker.js`) that reads file slices via `ReadableStream` or `FileReaderSync`.
* **Benefit:** 100% fluid, responsive UI even when hashing 10 GB+ files on lower-powered laptops and mobile devices.

### 1.3 HTTP Range Requests for Resumable & Streaming Downloads
* **Current State:** The download endpoint streams the full file from byte 0 to completion.
* **Optimization:** Implement `Accept-Ranges: bytes` and HTTP 206 Partial Content responses on `GET /api/v1/download-sessions/{id}/stream`.
* **Benefit:**
  * Allows download managers and mobile browsers to pause and resume interrupted downloads.
  * Enables in-browser instant audio/video preview without waiting for the full file to finish downloading.

### 1.4 Direct-to-S3 Presigned Uploads with CORS
* **Current State:** Browser uploads stream through the Spring Boot proxy (`/parts/{n}`) to eliminate local CORS friction on MinIO/RustFS.
* **Optimization:** In production environments, configure CORS on the object storage bucket so the browser uploads directly to S3 presigned URLs, with the backend acting solely as an authorization authority.
* **Benefit:** Zero backend server bandwidth and CPU consumption during chunk data transfer; enables boundless horizontal scaling.

---

## 2. Cryptographic & Security Hardening

### 2.1 End-to-End Encryption (E2E) UI Activation (Gate 5)
* **Current State:** Backend cryptographic primitives (`X25519`, `HKDF-SHA256`, `AES-256-GCM`, wrapped key envelopes) are fully built and tested under the `honeyshare.features.e2e-enabled` feature flag.
* **Optimization:** 
  * Integrate client-side key generation in the Web UI using Web Crypto `SubtleCrypto.generateKey("X25519")`.
  * Encrypt file chunks in the browser before transmission so the server never possesses the plaintext or decryption keys.
* **Benefit:** Zero-knowledge privacy compliant with strict confidentiality and healthcare/legal workflows.

### 2.2 Built-In Local HTTPS / TLS Support
* **Current State:** Runs over plain HTTP (`http://localhost:8080` or `http://10.1.0.212:8080`), requiring a fallback JS SHA-256 engine for non-localhost mobile browsers.
* **Optimization:** Configure Spring Boot with automated self-signed certificates or `mkcert` local development CA.
* **Benefit:** Establishes a permanent **Secure Context** (`isSecureContext = true`) across all LAN devices, unlocking hardware-accelerated Web Crypto, Push Notifications API, and Background Sync API on mobile browsers.

### 2.3 Password-Protected Transfers & Link Passes
* **Enhancement:** Allow senders to set an optional one-time transfer passphrase (hashed via Argon2id / PBKDF2).
* **Benefit:** Adds a second factor of authentication for sensitive files before recipients can authorize download sessions.

### 2.4 Asynchronous Antivirus & Malware Scanning
* **Enhancement:** Integrate ClamAV as an asynchronous zero-cost Docker microservice (`clamav/clamav:latest`).
* **Workflow:** When an upload completes, an asynchronous worker streams the assembled S3 object through the ClamAV daemon before changing the transfer state to `AVAILABLE_TO_RECIPIENT`. If malware is detected, the transfer is quarantined and deleted immediately.

---

## 3. User Experience & Collaboration Features

### 3.1 Admin Management Dashboard
* **Enhancement:** A dedicated admin panel tab in the UI (visible only to `ROLE_ADMIN`) providing:
  * One-click generation and sharing of invitation tokens (eliminating terminal commands).
  * Real-time user management (view active users, suspend/activate accounts).
  * Storage quota adjustments per user (e.g. increase quota from 200 GB to 500 GB).
  * Manual triggers for storage reconciliation and expired transfer cleanup.

### 3.2 Real-Time Server-Sent Events (SSE) or WebSockets
* **Current State:** UI polls endpoints or requires manual refresh.
* **Optimization:** Introduce an SSE channel (`GET /api/v1/notifications/stream`) connected to the Spring transactional outbox.
* **Benefit:** Instant toast notifications when:
  * A new file arrives in the Inbox.
  * A recipient finishes downloading a sent file.
  * An upload session completes storage verification.

### 3.3 Public Shareable Links for Non-Registered Guests
* **Enhancement:** Allow senders to toggle "Allow Guest Download via Link" for specific transfers with configurable download count limits (e.g. "Expire after 1 download").
* **Benefit:** Enables sharing files with external contacts who do not have a HoneyShare account.

### 3.4 Multi-File Bundling & Drag-and-Drop Folder Upload
* **Enhancement:** Allow dragging entire folders or selecting multiple files, packaging them on-the-fly or streaming as a unified zip archive without writing temporary zip files to disk.

---

## 4. Storage & Infrastructure Optimization

### 4.1 Content-Addressable Storage (CAS) Deduplication
* **Optimization:** If two users upload the exact same file (identical SHA-256), point both transfer records to the same underlying S3 storage key.
* **Benefit:** Saves significant disk space and eliminates redundant network writes while still enforcing individual user quotas and access controls independently.

### 4.2 Automated Hourly Background Retention Cleaner
* **Current State:** Cleanup can be triggered manually or during reconciliation checks.
* **Optimization:** Add a `@Scheduled(cron = "0 0 * * * *")` cron job that scans for expired transfers (`expiresAt <= NOW()`) and automatically performs remote S3 deletion, releases database records, and issues durable sender deletion receipts.

### 4.3 Production Observability & Prometheus Metrics
* **Optimization:** Expose custom Spring Boot Micrometer metrics for:
  * Active upload/download transfer count.
  * Storage I/O throughput (MB/s).
  * Average chunk upload latency.
  * Quota reservation vs. stored bytes ratio across the platform.

---

## 5. Implementation Priority Matrix

| Phase | Milestone | Complexity | Impact |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Concurrent Chunk Uploads (3-4 workers) | Low | **High** (Immediate 3x-5x speedup) |
| **Phase 2** | HTTP Range Request Support (`Accept-Ranges`) | Low | **High** (Resumable downloads & media streaming) |
| **Phase 3** | Admin UI Tab for Invitation Management | Low | **Medium** (Simplifies user onboarding) |
| **Phase 4** | Web Worker Background Hashing | Medium | **Medium** (Silky-smooth browser performance) |
| **Phase 5** | Local HTTPS / TLS Setup | Medium | **High** (Enables native Web Crypto on all phones) |
| **Phase 6** | Real-Time Notifications via SSE | Medium | **High** (Live notifications without page refreshes) |
| **Phase 7** | Full E2E Client-Side Encryption Activation | High | **Critical** (True zero-knowledge security) |
| **Phase 8** | Content-Addressable Storage Deduplication | High | **High** (Major disk savings) |

