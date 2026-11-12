# ADR-004: Server sessions and CSRF

Status: Accepted. Date: 2026-11-12.

Spring Session JDBC persists sessions. BCrypt cost 12; HttpOnly SameSite=Lax cookies; HTTPS Secure default. Login rotates session ID and CSRF token.
