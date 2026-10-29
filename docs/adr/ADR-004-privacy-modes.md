# ADR-004: Dual Privacy Modes (Standard vs End-to-End Encryption)

## Status
Accepted

## Context
HoneyShare users transfer high-value, sensitive files (e.g. source code, video production assets, confidential documents) up to 100 GB in size. Users have distinct security postures:
1. Some organizations require server-side inspections, DLP scanning, automated thumbnail generation, and centralized audit compliance.
2. Other teams require zero-knowledge confidentiality where even a total compromise of the storage and database infrastructure cannot expose file contents.

Marketing a platform as "end-to-end encrypted" when the server retains access to symmetric keys or recovery backdoors creates severe legal liability and false security guarantees.

## Decision
We implement two explicit, distinct transfer privacy modes:

### 1. Standard Mode (`PrivacyMode.STANDARD`)
- **Encryption:** TLS 1.3 in transit; AES-256 server-side encryption at rest via MinIO/S3 object storage provider.
- **Server Access:** The backend manages storage credentials and has authorized access to file streams. This allows future features like automated virus/malware scanning, thumbnail rendering, and administrative compliance audits.
- **Disclosure:** The UI and API explicitly disclose to users that authorized server-side components can process plaintext.

### 2. End-to-End Mode (`PrivacyMode.E2E`)
- **Encryption:** Client-side authenticated encryption (AES-256-GCM / XChaCha20-Poly1305) on the sender's device before any bytes leave the local endpoint.
- **Key Distribution:** File encryption keys ($K_{file}$) are wrapped using X25519 key agreement and HKDF-SHA256 for each active recipient device. Wrapped key envelopes are stored on the server as opaque ciphertext.
- **Server Invariants:** The backend NEVER generates, receives, computes, stores, or logs plaintext file keys, device private keys, or recovery secrets.
- **Feature Flag Gate:** In accordance with Specification Section 18 Gate 5, E2E mode is maintained behind the feature flag `honeyshare.features.e2e-enabled` (default `false`) until formal cryptographic protocol review and client engine testing are complete. Attempting to create an E2E transfer when disabled returns `403 Forbidden` (`E2E_FEATURE_DISABLED`).

## Consequences
### Positive
- Strict separation between operational convenience (Standard) and uncompromising confidentiality (E2E).
- Honest, verifiable privacy architecture without marketing misrepresentations.
- Server database compromises yield zero file plaintext for E2E transfers.

### Negative / Trade-offs
- E2E mode does not allow server-side transcoding, thumbnail generation, or content inspection.
- Loss of recipient device keys and recovery secrets renders E2E transfers permanently unrecoverable.
- E2E transfers still expose operational routing metadata (file size, timestamps, recipient identifiers) to the backend.

