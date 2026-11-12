# ADR-011: Executed release checks

Status: Accepted. Date: 2026-11-12.

Use real PostgreSQL/Testcontainers for transaction, tenant and concurrency tests; deterministic calendar fixtures for SLA arithmetic; Playwright against production containers for user journeys. Record observed results separately from unexecuted hosted CI and open acceptance gates. A green UI screenshot cannot substitute for failure-path tests.
