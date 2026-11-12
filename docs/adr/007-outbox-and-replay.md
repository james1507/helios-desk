# ADR-007: Outbox and replay

Status: Accepted. Date: 2026-11-12.

Every ticket event writes outbox intent atomically. Idempotency uses a transaction-scoped advisory lock and a unique scoped request fingerprint. External email cannot promise universal exactly-once delivery.
