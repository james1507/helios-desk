# Paste this into Codex after adding the specification files to the repository

You are the lead full-stack engineer responsible for implementing **Helios Desk — Enterprise Helpdesk & Ticket Management Platform**.

Read `PROJECT_SPEC.md` and `AGENTS.md` from start to finish. Treat the explicit domain rules, authorization matrix, lifecycle state machine, database constraints, SLA semantics, API contracts and acceptance criteria as binding requirements.

**Your first task** is to create a phased implementation plan in `docs/progress.md`, then implement **Phase P0 and Phase P1** as functional vertical slices, not mockups. Build a monorepo with Next.js + TypeScript frontend, Spring Boot + Java backend, PostgreSQL + Flyway, secure session/cookie auth with CSRF, two-tenant demo seed, and a complete ticket workflow. The customer must be able to create and reply to tickets; an agent must be able to claim, assign where authorized, write public replies and internal notes, and resolve tickets; the manager must be able to triage. Implement durable transaction boundaries, atomic ticket numbering, idempotent create/reply, optimistic version checking, audit event history, and strict tenant/requester isolation.

Build all corresponding frontend pages and **real backend endpoints**; no hardcoded fake ticket lists in production code. Write unit, real PostgreSQL/Testcontainers integration, and Playwright E2E tests. Provide a working `docker compose -f infra/compose.yml up --build` launch path, README and dev-only synthetic accounts. Run every available verification command and fix failures. Report honest status with completed files, tests and blockers.

Once P0/P1 are actually complete, proceed with **P2, then P3, then P4**, in order, using `docs/progress.md` to resume work. Do not claim full implementation or fabricated test results. Do not silently omit difficult production failure cases. Prioritize core correctness before feature breadth; no external paid service is required for the local demo.
