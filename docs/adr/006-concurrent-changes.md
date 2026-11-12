# ADR-006: Concurrent changes

Status: Accepted. Date: 2026-11-12.

JPA @Version guards updates. Row locks serialize claim; If-Match rejects stale input. Public IDs use a counter UPDATE RETURNING inside the same transaction.
