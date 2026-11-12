# Database outage and restore

API connection acquisition is bounded at three seconds; dependency failures return a safe 503. Check database readiness, connection saturation and structured correlation IDs. Do not automatically retry non-idempotent mutations. Clients can replay create/reply operations using the original idempotency key and body after recovery.

For a private local backup, use `pg_dump -Fc` with a database operator identity and store the result outside source control. Restore into a new, isolated database with `pg_restore --no-owner --no-privileges`; verify Flyway history, tenant count, ticket/message/SLA counts and a deterministic ticket digest before routing any traffic. PostgreSQL backups contain private data and password hashes; apply access controls and retention. Production recovery requires coordinated object-storage backup and session invalidation.

The local smoke drill restored the synthetic clean-demo database into `helios_restore` and compared ticket digests. See progress.md for observed evidence. This is not an RTO/RPO measurement, a complete object-storage recovery drill or production backup certification. Never restore over the active demo database to perform a test.
