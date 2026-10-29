# ADR-020: Notifications, Transactional Outbox, Deduplication, and Push Privacy

## Status
Accepted

## Context
When domain state changes occur (such as transfer verification, recipient assignment, download completion, cancellation, or expiration), notifications must be delivered to affected users across multiple channels:
- In-App Notification Inbox (`inbox_notifications`)
- Email (`EmailPort`)
- Push Notifications (`PushPort`)

However, directly invoking external third-party communication providers inside the core database transaction is unsafe:
1. Provider outages or network latency would cause core transfer database transactions to roll back or time out.
2. If the transaction commits but the network request fails, notification intent is permanently lost.
3. Mobile push notifications that include file names or metadata leak private content to platform push services (APNs, FCM) and lock screens.
4. Paid external cloud services could generate unexpected billing charges.

## Decision
1. **Transactional Outbox Pattern:** All notification intents are written to `outbox_events` in MySQL within the *same atomic transaction* as the domain state change. If the database transaction commits, notification delivery is guaranteed to be attempted.
2. **Zero-Cost Local Defaults:** By default, email and push adapters operate in `local` mode (`LocalEmailAdapter`, `LocalPushAdapter`) using application logging and local capture. This guarantees **$0 financial cost** and ensures developers and self-hosters are never billed.
3. **Idempotency & Deduplication:** Every outbox event has a deterministic `(eventId, channel)` unique constraint. Duplicate publishes for the same domain transition (e.g. retried upload completion) are cleanly rejected at the database level.
4. **Push Privacy (FR-NOTIFY-04):** Push notification payloads are strictly generic by default (e.g. *"You have a new file. Open HoneyShare to view it."*). File names, sizes, presigned URLs, cryptographic secrets, and sender identities are never included in push notification payloads.
5. **Exponential Backoff with Jitter:** The `OutboxWorker` processes pending events. Transient failures (e.g., mail server disconnect) trigger exponential backoff ($2^{\text{attempt}} \times 1\text{s} + \text{jitter}$). After 5 failed attempts, the event is moved to `DEAD_LETTER` state and alerts operators without silent data loss.
6. **User Preferences:** Users can independently toggle email, push, and in-app notifications via `user_notification_preferences`. Opted-out channels are marked `SENT` without dispatching external messages.

## Consequences
- Reliable at-least-once notification delivery that never compromises core transfer state transactions.
- Zero chance of unexpected cloud billing or credit card charges.
- User privacy preserved on device lock screens and external push networks.

