# ADR-005: Tenant boundaries

Status: Accepted. Date: 2026-11-12.

Every business query includes tenant ID; composite foreign keys prevent cross-tenant links. Membership roles are reloaded from DB for each request. RLS is not enabled.
