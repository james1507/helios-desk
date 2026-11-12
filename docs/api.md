# API usage

Prefix `/api/v1`. Same-origin browser requests use an HttpOnly session cookie. Responses and session-sensitive pages use `Cache-Control: no-store`.

1. GET `/auth/csrf`, keep the returned cookie and `token`/`headerName`.
2. POST `/auth/login` with JSON email/password and the CSRF header.
3. Fetch `/auth/csrf` again after login; tokens rotate.
4. GET `/auth/me` for active tenant and membership. Tenant switch requires a verified membership.
5. POST `/tickets` with a random UUID `Idempotency-Key`. POST replies with a fresh key and `clientMessageId`.
6. Read the ticket's ETag, e.g. `"v3"`; send it as `If-Match` for existing ticket writes.
7. POST `/auth/logout` with CSRF to invalidate the server session.

Errors use RFC 9457 with `code`, `status`, `detail`, `correlationId`; stale edits include `currentVersion` where available. Out-of-scope resource IDs return 404. Validation is 400, permission denial 403, version/replay conflict 409, invalid state transition 422.

Lists return `{items,nextCursor,totalCount}` with default 25/max 100. Queues support `q,status,priority,source,teamId,assigneeId,queue,cursor,limit`. Message and event pages use ascending `(created_at,id)` cursors. Audit currently returns the newest 100 safe records; advanced audit pagination is not implemented.

Checked-in contract: `packages/api-contract/openapi.json`. Generated frontend types: `apps/web/lib/schema.d.ts`. Authenticated schema endpoint: `/api/v1/openapi`. Regenerate against a running local demo with `make contract`; CI detects drift. API wrapper obtains CSRF, preserves cookies and returns typed errors without automatically retrying writes.

`GET /notifications?after=<sequence>&limit=50` replays authorized notifications in ascending order; omitted `after` returns the latest window. `PATCH /notifications/{id}/read` is idempotent. `GET /events/stream` uses the authenticated membership, supports `Last-Event-ID` or `after`, sends heartbeat comments and reconnects after 24 seconds. Its `no-transform` header prevents gzip/proxy buffering. Event bodies contain identifiers/version/type only. Queue/report caches refresh on events; ticket editors retain their draft and offer explicit refresh.

`GET /reports/overview?from=...&to=...` is staff-only and agent-scoped. `GET/PATCH /sla/policies` reads/updates versioned snapshot settings. `GET /tickets/{id}/sla` exposes authorized cycle history. `GET/POST /teams`, `PATCH /teams/{id}` support versioned team membership and soft deactivation. Management can request `includeInactive=true`; team creation is capped at 100 until cursor pagination is added.
