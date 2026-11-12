# Worker backlog and scheduler lag

Inspect API structured logs and the `outbox_events` status counts through a trusted database operator connection. Never log payloads or copy private messages to alerts. Compare oldest pending `available_at`, expired `claimed_until`, attempt counts and `last_error` class. Liveness alone does not prove progress.

Workers claim 30 jobs per tick with 30-second leases. A failed process returns work through lease expiry; successful notification inserts are idempotent per recipient/event. Attempts stop at eight. Restore the database or fix the failing event handler before requeueing a reviewed dead letter. Reset its status to `RETRY`, attempts to zero, available time to database `now()`, and clear lease/token in a tenant-and-ID-scoped operator transaction. Preserve an operator audit outside the immutable product event history. Do not delete outbox rows to hide failures.

SLA evaluation runs every ten seconds, scans up to 300 indexed candidates and records warnings/breaches with unique cycle/metric keys. Restarting resumes due work, including late completed first responses. Inspect `sla_evaluations_total` and `sla_breaches_total` through the internal management endpoint. End-to-end lag gauges, dashboards and operator UI are pending P4 work.

Outbound email is not implemented; this worker currently delivers durable in-app notifications only. Email-provider outage handling must be verified when the adapter is added.
