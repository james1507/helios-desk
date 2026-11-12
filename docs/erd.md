# Core physical schema

```mermaid
erDiagram
 USERS ||--o{ TENANT_MEMBERSHIPS : belongs
 TENANTS ||--o{ TENANT_MEMBERSHIPS : contains
 TENANTS ||--|| TICKET_COUNTERS : allocates
 TENANTS ||--o{ TEAMS : owns
 TEAMS ||--o{ TEAM_MEMBERS : includes
 TENANT_MEMBERSHIPS ||--o{ TEAM_MEMBERS : joins
 TENANTS ||--o{ TICKETS : isolates
 TENANT_MEMBERSHIPS ||--o{ TICKETS : requests
 TICKETS ||--o{ TICKET_MESSAGES : conversation
 TICKETS ||--o{ TICKET_EVENTS : timeline
 TICKETS ||--o{ TICKET_SLA_CYCLES : tracks
 TICKET_SLA_CYCLES ||--o{ SLA_PAUSE_INTERVALS : pauses
 TICKET_SLA_CYCLES ||--o{ SLA_BREACHES : violates
 TICKET_SLA_CYCLES ||--o{ SLA_WARNINGS : warns
 TENANTS ||--o{ AUDIT_LOGS : audits
 TENANTS ||--o{ OUTBOX_EVENTS : emits
 OUTBOX_EVENTS ||--o{ NOTIFICATIONS : delivers
 TENANT_MEMBERSHIPS ||--o{ NOTIFICATIONS : receives
 TENANT_MEMBERSHIPS ||--|| NOTIFICATION_COUNTERS : sequences
 TENANT_MEMBERSHIPS ||--o{ IDEMPOTENCY_RECORDS : retries
 SPRING_SESSION ||--o{ SPRING_SESSION_ATTRIBUTES : persists
```

Tenant-owned references use `(tenant_id,id)` unique keys and composite foreign keys, including requester, assignee, team, message author and SLA cycle. Every queue/search predicate starts with tenant and role scope. Queue order is `(last_activity_at DESC,id DESC)`, encoded as an opaque keyset cursor. Public full-text message search uses a partial GIN index.

SLA policy JSON stores a bounded calendar and priority target configuration; each cycle snapshots it. This intentionally uses fewer normalized configuration tables than the suggested physical schema, preserving immutable historical behavior. See migrations for authoritative constraints.
