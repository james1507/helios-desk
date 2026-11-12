# Security controls and current limits

- Active user/membership/tenant is queried on every service request; disabling any of them blocks future access. Role comes from PostgreSQL, not request headers or JSON.
- Tenant-scoped query authorization precedes pagination/counting; composite foreign keys reject cross-tenant relationships. Requesters can only read their own records.
- Public/private visibility is immutable. Customer detail/messages/timeline/search projections omit private notes. Auditors currently cannot read private notes (explicit deny policy).
- BCrypt cost 12. HttpOnly SameSite=Lax server-session cookie. Secure cookies default on; local HTTP demo opts out explicitly. Login rotates session ID and CSRF. No browser auth tokens in localStorage.
- CSRF required for login/logout and all unsafe authenticated methods. CORS is not enabled. Database login throttling currently enforces 20 attempts/account/15 minutes.
- Message Markdown is rendered server-side by CommonMark with raw HTML escaped and unsafe URLs removed, then filtered by an explicit OWASP HTML policy. No images/styles/event handlers/embedded media are allowed. Original text remains stored; requester filtering happens before rendering. `MarkdownTest` covers script URLs, entity-obfuscated schemes, raw HTML and remote images.
- Parameterized SQL and enum validation at trust boundaries. Security errors redact SQL and application details; request correlation IDs are server generated.
- Next.js CSP prevents framing, cross-origin fetch and form actions. Development-compatible `unsafe-inline`/`unsafe-eval` remain; production nonce CSP is a P4 task.
- Dev seed requires both `dev` profile and an explicit 12+ character password. Production does not silently create demo users. Compose is a localhost-bound demo configuration.

- SSE checks persisted session/active tenant and reloads membership status/role on every poll. Team access is rechecked when reading notifications. Read-only identity resolution does not rewrite established tenant attributes, preventing long-lived requests from overwriting a later tenant switch.

ASVS 5.0.0 is a reference, not certification. Automated evidence covers CSRF, IDOR, tenant composite constraints, requester projection, permission denials, optimistic edits, concurrent claims and Markdown restrictions. Per-IP throttling, email verification/reset/invitations, explicit deactivation-during-stream E2E, file/webhook security and formal backend dependency scanning need their phase gates. No production readiness claim is made.
