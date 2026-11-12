# ADR-008: Durable live updates

Status: Accepted. Date: 2026-11-12.

Durable per-recipient notifications use transactional counters, unique recipient/event keys and monotonic sequence IDs. SSE reads committed rows after the cursor and checks active session/tenant/membership every 1.5 seconds. Heartbeats and 24-second connection turnover bound stale streams. `Cache-Control: no-cache, no-transform` avoids proxy compression buffering; the UI falls back to bounded polling. Draft editors refresh explicitly. P2 acceptance status is tracked in progress.md.
