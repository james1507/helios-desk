# AGENTS.md — Codex instructions for Helios Desk

## Source of truth

Read `PROJECT_SPEC.md` fully before modifying code. It is the domain and acceptance source of truth. Read `docs/progress.md` if present. Do not substitute a simplified CRUD demo. The delivery sequence is P0 -> P1 -> P2 -> P3 -> P4; P5 is optional.

## Workflow

1. Inspect repository and environment, discover package/build tools, create an actionable phase plan in `docs/progress.md`.
2. Implement small vertical slices with real Postgres persistence, API contracts, typed frontend, tests, and documentation.
3. Run the appropriate commands after every slice; fix failures, don't ignore them. Preserve a working runnable branch.
4. If environment tool access blocks a test, state the exact command/limitation and leave the requirement **unverified**.
5. Before each milestone completes, evaluate the corresponding acceptance gate in `PROJECT_SPEC.md` and capture evidence.
6. Do not change architecture or domain semantics without updating ADR/docs and corresponding tests.
7. When asked to continue, read `docs/progress.md`, pick highest-priority incomplete phase requirement, and continue without restarting.

## Requirements that must not be weakened

- Tenant isolation in all service/query paths and DB foreign keys; cross-tenant IDs return 404.
- Private notes and staff-only objects must never leak through customer APIs, search, exports, notifications, email, SSE or attachments.
- Atomic ticket creation, ticket number allocation, event write, SLA initialization, outbox and idempotency.
- Ticket state machine, version guard with 409 response, atomic claim concurrency.
- Business-hour SLA exactness including holidays, pause, reopen, DST tests.
- Durable outbox and retry-safe email/webhook/notification handlers.
- Spring Security session cookies with CSRF; no `localStorage` auth tokens.
- Real React pages backed by working API; no fake or disabled UI controls advertised as functional.

## Engineering conventions

- English throughout implementation. Java backend, TypeScript frontend, PostgreSQL, Flyway, same-origin `/api`.
- Backend service-layer authorization; validate input and database constraints; no secrets in commits/logs.
- API error code + correlation ID, versioned `/api/v1`, ISO-8601 UTC timestamps, OpenAPI typed client.
- Use Testcontainers integration tests and Playwright end-to-end tests for critical flows.
- Keep `.env.example` with nonsecret placeholders, deterministic dev-only seed, Docker Compose quickstart.
- All new behavior requires appropriate tests; concurrency, authorization, and retries require negative/failure-path tests.
- Avoid adding Kafka, Elasticsearch, Kubernetes, AI agents, billing or other optional infrastructure until P0–P4 are solid.

## Report format after each milestone

- Completed features and modified paths.
- Commands run and observed result (PASS/FAIL/NOT RUN).
- Security and consistency invariants covered by tests.
- Known defects, blockers and assumptions.
- Updated `docs/progress.md` and next P-phase tasks.
