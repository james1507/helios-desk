# Helios Desk implementation progress

Updated: 2026-11-12. Requirements originate in PROJECT_SPEC.md; attached prompt is reference material. No phase is complete until its gate has evidence.

## Plan

1. P0: initialize Next.js/Spring Boot monorepo, PostgreSQL/Flyway schema, JDBC sessions/CSRF, two-tenant seed, typed API, Compose and CI. Verify clean startup and tenant boundaries.
2. P1: implement transactional ticket create/replay, scoped queues/detail, public/private conversation, atomic claim/assignment, version guards, exact state machine and append-only audit. Build customer and staff screens. Verify unit, PostgreSQL integration/concurrency and Playwright journeys.
3. P2: calendars, immutable SLA cycles, pause/reopen, breach scheduler, durable outbox/notifications, replayable SSE and computed reports. Verify reference cases and worker recovery before widening scope.
4. P3: signed email, private scanned attachments, knowledge, macros, CSAT and safe export. Gate on security/replay tests.
5. P4: monitoring, fault drills, measured load/restore, security review and final documentation.

## Status

| Phase | Implementation | Verification |
| --- | --- | --- |
| P0 | Implemented | Docker build/start + login/isolation verified |
| P1 | Implemented core; advanced requirements remain open | 78 backend + 2 frontend unit + 8 production-container E2E pass |
| P2 | Operational core implemented; full gate remains open | Scheduler, worker replay, reporting, editors, 30-second SSE reconnect and two-tab switch pass |
| P3 | Pending | Pending |
| P4 | Pending | Pending |

## Environment

- macOS arm64, Node 24.20.0, Java 21.0.12.1, Docker 29.7.2 available.
- Existing unrelated containers preserved; dedicated project and ports will be used.
- Only synthetic demo data. Development seed explicitly profile-gated.

## Evidence

- `./apps/api/mvnw -f apps/api/pom.xml spotless:apply verify`: PASS, 78 tests, real PostgreSQL 17.7 via Testcontainers. Includes 50 competing claims, 20 concurrent idempotency requests, 20 scheduler evaluations, rollback, lease replay, warning dedup, poison dead-letter, deactivation, team/policy version checks, pre-pause resolution breach reconciliation and six Markdown security cases.
- `npm --prefix apps/web test`: PASS, 2 tests.
- Frontend lint/typecheck/Prettier check and production build: PASS.
- `docker compose --env-file .env -f infra/compose.yml up -d --wait`: PASS; DB/API/web/MinIO/Mailpit healthy, bucket initializer exits successfully.
- `BASE_URL=http://localhost:3101 npm --prefix apps/web run e2e`: PASS, all 8 journeys in 1.0 minute against a freshly recreated isolated production Compose stack. Includes customer/agent lifecycle, tenant/role denial, conflict draft, responsive/axe checks, 30-second SSE network drop with cursor replay/private exclusion, real reports/policy save, team create/deactivate and two-tab tenant switch/logout. Screenshots in docs/screenshots. Sidebar overflow, replay refetch batching and self-broadcast fixes are included in the tested image.
- `npm audit`: PASS, 0 vulnerabilities after minimatch 11.0.8 override.
- Hosted GitHub CI: NOT RUN (local repo only).
- Separate `helios-clean-check` Compose project: PASS, all containers healthy after migrations/seed on new volumes, same built application images, web on port 3101. No existing demo/unrelated volumes reused.
- PostgreSQL backup/restore smoke: PASS into isolated `helios_restore`; 50 tickets, 149 messages, 53 cycles, 16 users. Source/restored ordered ticket digest both `787e03228de6205b9b3b1bf5c1f3fbcd`. This does not verify object recovery or production RTO/RPO.
- Contract regeneration is stable (`cmp` of regenerated OpenAPI and TypeScript schema): PASS.

## Delivered operational slice

- Flyway V3: durable per-recipient notifications/counters, outbox claim tokens, SLA warnings and stored warning instants, nullable system audit/event actors. V4: team versions and nonblank names.
- Outbox consumer uses `SKIP LOCKED`, expiring leases, conditional claim-token acknowledgement, bounded jittered retries and eight-attempt dead letters. Notification uniqueness makes delivery-before-ack replay safe.
- SLA scheduler writes breach/warning and intent atomically, reconciles late completed responses, preserves paused deadline calculations and historical cycle snapshots. Seven-day automatic close records a system actor.
- Notification inbox/read counts, replayable SSE/heartbeats, polling fallback, explicit draft-preserving refresh, versioned policy/calendar/team editors and scoped real reports.
- CommonMark rendering followed by OWASP HTML allowlist; original messages retained, raw HTML and scriptable links excluded, remote images omitted.

## Important remaining requirements

P1/P2/P3/P4 acceptance gates remain open. Advanced ticket filters/tags/follow-up links, @mentions, calendar preview, full agent/manager metrics, account onboarding/invite/reset/administration and the full prescribed aged/channel/KB seed variety remain incomplete. P2's core persistence/reliability work does not complete these broader requirements.

P3 remains unimplemented: signed inbound/outbound email, scanned private attachments, knowledge publishing, macros, CSAT and CSV export. MinIO/Mailpit infrastructure is ready but has no product adapter yet. P4 monitoring stack, complete dependency/security review, three incident drills, 100k benchmark/query plans and full backup/object restore remain pending. Only the database smoke restore is verified. No full-spec or production-readiness claim.

Next work: finish the remaining P1/P2 acceptance requirements before widening into P3. See acceptance-checklist.md and production-scenarios.md for exact gaps.
