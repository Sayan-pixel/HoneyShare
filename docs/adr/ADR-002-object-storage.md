# ADR-002: S3-Compatible Object Storage Abstraction

## Status
Accepted

## Context
Per specification Section 2, Section 7.1, and Section 21:
- Storage must be redundant object storage within one selected region.
- Server must stream large-file data (target up to 100 GB) without buffering full files in the JVM heap.
- Where supported, clients upload and download via short-lived, pre-signed URLs directly to/from object storage to keep data plane throughput decoupled from API control-plane throughput.
- For local development, an emulator/compatible storage engine must be available without requiring cloud vendor credentials.

## Decision
1. **Interface Abstraction:** The backend accesses storage strictly through a `StoragePort` interface defined in the domain layer.
2. **Local Provider:** **MinIO** is used for local development and local testing via Docker Compose (`quay.io/minio/minio`).
3. **AWS SDK v2:** The AWS Java SDK v2 (`software.amazon.awssdk:s3`) with S3Presigner is selected to generate pre-signed upload/download URLs and perform multipart operations. This SDK works seamlessly with MinIO and standard S3-compatible cloud providers (AWS S3, Cloudflare R2, Google Cloud Storage in S3 compatibility mode).
4. **Cloud Selection Deferred:** Final cloud provider and region selection will be evaluated in Phase 7 based on cost, durability, request pricing, and lifecycle rules.

## Consequences
- **Positive:**
  - Developers can run end-to-end upload/download workflows locally with zero cloud dependencies.
  - Zero code changes required in core transfer/manifest logic when migrating from MinIO in local dev to AWS S3 in production.
  - High transfer bandwidth does not burden the Spring Boot server memory or network interfaces.
- **Negative / Mitigations:**
  - Emulators may have subtle differences in ETag semantics or multi-part validation compared to production providers (e.g. AWS S3 multipart ETags have `-partCount` suffixes). The platform never assumes an S3 ETag is a simple full-file MD5/SHA-256 hash.

