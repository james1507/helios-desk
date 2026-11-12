# Threat model

| Boundary / threat | Control | Evidence / remaining work |
| --- | --- | --- |
| Browser → API / CSRF | Spring synchronizer token and cookie session | Integration test rejects missing token |
| Tenant → tenant / IDOR | Query-scoped membership + tenant, composite FK | Integration + browser 404 checks |
| Customer → staff / private content | Role-safe SQL projections | Public timeline/search/messages negative assertions |
| Concurrent writers / lost updates | If-Match, JPA version, atomic claim | 50 competing claims and stale-draft browser test |
| Retry / duplicate side effects | Scoped request fingerprint + transactional outbox | Concurrent duplicate create and injected failure rollback |
| Stored text / XSS | React escaping, no raw HTML | Safe text path; rich Markdown pending |
| Credentials / brute force | BCrypt cost 12, account throttle, no logs | IP-based throttle remains pending |
| Upload / malware, email / forged webhook | Private bucket exists | Integration is pending P3; no exposed upload/webhook endpoints |
| Operator / destructive history | Append-only triggers and redacted audit | Deletion rejected in integration test; retention policy pending |

Assets: user identities, tenant tickets, private notes, sessions and file objects. Threat actors: unauthenticated clients, malicious requesters, compromised staff accounts, external providers and mistaken operators. PostgreSQL and local operator remain trusted; DB superusers can bypass application controls. No cross-tenant platform-admin interface is exposed.
