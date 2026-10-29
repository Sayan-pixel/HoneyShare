# Recorded Assumptions

This document tracks assumptions made in compliance with the **HoneyShare Specification (Section 0.14 & Section 3)**.

## 1. Development & Build Environment
- **Java Platform:** JDK 17 (Eclipse Adoptium Temurin 17.0.3) is used as the standard Java environment.
- **Maven Toolchain:** Apache Maven 3.9.14 is the build tool.
- **Database:** MySQL 8.0.x is the authoritative target metadata database. Flyway is used for all schema evolutions.
- **Unit Testing Database:** In-memory H2 with MySQL compatibility mode is used for unit tests without external container dependencies. Testcontainers with MySQL 8.0 is used for integration testing.

## 2. Storage Abstraction (ADR-002)
- **Local Development:** MinIO is configured via `docker-compose.yml` to provide S3-compatible object storage locally.
- **Cloud Provider Neutrality:** The application interacts with storage exclusively via an abstraction layer (`StoragePort`). Production cloud provider and region selection will be finalized in Phase 7 based on cost, durability, and multipart/checksum characteristics.

## 3. Cryptography & Security Defaults
- **Password Hashing:** Argon2id is used with parameters (memory=64MB, iterations=3, parallelism=1) configured via properties.
- **MFA Implementation:** TOTP according to RFC 6238 is used with 30-second time steps and 6-digit codes.
- **End-to-End Encryption (E2E):** E2E encryption is intentionally kept behind a feature flag (`honeyshare.features.e2e-enabled=false`) until key-management protocol, device enrollment, and recovery design have been reviewed. No custom cryptography is used.
- **Tokens and Secrets:** All session tokens, invitation tokens, email verification tokens, and password reset tokens are stored as SHA-256 hashes. The raw token is returned only once to the client upon creation.

## 4. Transfer & Quota Defaults (Spec Section 3)
- **Quota Accounting:** Quotas are charged to the owner/sender of the stored transfer object (200 GB default limit). Upload reservations are held atomically before large file parts are accepted.
- **Retention Clock:** The 24-hour default retention clock begins when the transfer reaches `STORED_AND_VERIFIED`, not when upload starts.
- **Expiry Grace Period:** At expiry, new download authorizations are denied; authorized active downloads are granted up to a 24-hour grace period to finish.
- **Grace Period Clock Immutability:** Reconnection/resuming an authorized download session during the grace period increments the reconnect count, but does NOT reset or extend the grace period deadline (`DownloadSession.expiresAt`).
- **Physical Deletion Engine:** Deletion is asynchronous and multi-step (`EXPIRED`/`CANCELLED` -> `DELETION_PENDING` -> `DELETED`). Stored quota is released only after remote object storage confirms deletion. If an active download session is still running within grace period, deletion is deferred.
- **Automatic Local Source Deletion:** Optional sender-side deletion is disabled by default and requires durable-storage receipt verification and explicit user opt-in.
- **Storage Reconciliation:** Incomplete upload sessions older than 48 hours or belonging to cancelled transfers are automatically aborted on object storage, and their reserved quota is released.
