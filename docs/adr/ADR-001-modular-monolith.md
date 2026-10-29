# ADR-001: Modular Monolith Architecture

## Status
Accepted

## Context
The HoneyShare specification requires a cross-platform, server-backed file-sharing system designed initially for an invitation-only small private beta. 

Section 0.4 of the specification dictates:
> "Prefer the simplest architecture that satisfies the requirements. Do not introduce microservices, Kubernetes, Kafka, or other operationally expensive infrastructure without a demonstrated need."

The platform must support high reliability, transactional integrity across user metadata, transfer states, and quota accounting, while delegating heavy file byte streaming to object storage.

## Decision
We implement HoneyShare as a **Modular Monolith** using Spring Boot and Java 17 within a single deployable artifact.

Internal architectural boundaries are strictly organized by domain modules:
- `identity`: User onboarding, invitations, email verification, sessions, MFA, password reset.
- `transfers`: Transfer entities, state machines, upload/download sessions, manifest records.
- `storage`: StoragePort abstraction interfacing with S3-compatible storage.
- `integrity`: Streaming SHA-256 calculation and verification.
- `quotas`: Concurrency-safe atomic reservations and reconciliation.
- `audit`: Security event logging with out-of-band transaction isolation.
- `jobs`: Database-backed persistent job queue (leases, exponential backoff, dead-letter tracking).

## Consequences
- **Positive:**
  - Simple deployment and operations (single JVM container + managed MySQL).
  - Transactional atomicity for control-plane mutations (e.g., inviting a user, redeeming an invitation, reserving quota).
  - Eliminates network serialization overhead, distributed tracing complexity, and network partition failure modes between internal services.
- **Negative / Mitigations:**
  - Requires discipline to enforce module boundaries in code. Enforced via package structure and explicit port/service interfaces.
  - Background workers run within the same application process in beta; can be split into a separate worker entrypoint if CPU-bound integrity workloads require independent scaling.

