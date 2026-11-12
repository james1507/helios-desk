# Tenant access incident

Capture the correlation ID, actor membership ID and affected resource ID in restricted incident records. Do not copy ticket bodies or session cookies. Verify the active user, tenant and membership state directly in PostgreSQL and confirm the expected service authorization rule before changing any data.

Disable the specific compromised membership/user through a reviewed tenant-scoped operator change when authorized. Subsequent requests reload membership/role; SSE checks the persisted session and active membership each poll. Invalidate affected JDBC sessions for immediate containment, preserving safe audit evidence. Reproduce any isolation defect with two synthetic tenants and a negative integration test before releasing a fix.

There is no global tenant-reading platform-admin API. Account administration UI, security alerting and a complete incident drill remain pending.
