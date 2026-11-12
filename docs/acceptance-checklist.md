# Verified acceptance checklist

Use `docs/progress.md` for dated command evidence. Checked items have code and executed test evidence; unchecked items are not claimed complete.

## Core workflow

- [x] Customer login/create/own-ticket view/public reply; staff claim/reply/private note/resolve. `TicketIntegrationTest.completeLifecycleAndPrivateProjection` and Playwright lifecycle.
- [x] Exact 36-pair status matrix plus role/reason/reopen guards. `WorkflowTest`.
- [x] Atomic scoped ticket numbers; concurrent allocation produces unique numbers. `uniqueNumbersUnderConcurrentCreate`.
- [x] Same request key replays; changed payload conflicts; concurrent duplicate requests produce one ticket. `idempotencyAndMismatch`, `concurrentDuplicateRequests`.
- [x] 50 concurrent claims produce one winner; stale edits conflict; draft retained in browser. `fiftyConcurrentClaimsOneWinner`, Playwright conflict test.
- [x] Requester private-note exclusion in messages/timeline/detail/search; cross-tenant and other-requester denial. Integration and Playwright isolation.
- [x] Transaction rollback includes counter/ticket/message/SLA/event/audit/outbox/idempotency. Injected outbox failure test.
- [x] Append-only message/event/audit storage, safe audit endpoint with role restrictions.
- [x] Responsive core pages and zero axe WCAG A/AA violations on login/overview in Chromium; mobile overflow check.
- [x] PostgreSQL integration tests run through Testcontainers; no in-memory replacement.
- [x] Generated OpenAPI and generated core frontend types; production Next.js build passes.
- [x] Frontend API unit tests pass; CSRF and If-Match sent without automatic mutation retry.
- [ ] Advanced query filters: tags/requester/date windows/sorts and private-note opt-in search.
- [x] Server-side CommonMark + OWASP restricted HTML policy. Six formatting/XSS/image cases pass in `MarkdownTest`.

## Service operations

- [x] Business calendar split shifts/weekend/holiday/DST/invalid interval fixtures pass.
- [x] Cycle snapshot, public first-response timestamp, pending pause/resume, preserved reopen cycle integration.
- [x] Warning/breach scheduler, late-response reconciliation, 20 competing evaluations and deduplicated breach intent.
- [x] Outbox consumer lease/retry/DLQ; expired lease replay does not duplicate notifications; poison event stops.
- [x] Durable notification inbox, unread/read APIs and private-note exclusion from requester notification rows.
- [x] 30-second SSE reconnect/cursor replay/dedup/private exclusion/draft preservation and two-tab tenant switch/logout pass in production-container Playwright.
- [ ] Deactivation-during-stream E2E (session revocation integration already passes).
- [x] Manager calendar/policy/team editors; snapshot/version safeguards; real scoped compliance/trends/workload queries and denial tests.
- [ ] Calendar preview, @mentions, fuller dashboard metrics, aged seed scenarios and advanced team/category/tag management.

## Omnichannel

- [ ] Provider email adapter, raw-byte signature, routing/dedup/quarantine tests.
- [ ] Private attachment upload/scan/download pipeline and malware/security tests.
- [ ] Knowledge drafts/publishing/version history and customer search isolation.
- [ ] Macros, CSAT and secure report CSV export.
- [ ] Registration/email verification/invitations/password reset/account administration.

## Delivery and operations

- [x] Clean Compose startup on isolated new volumes; migrations, seed and healthy application/dependency containers verified.
- [x] CI workflows exist; hosted execution is unverified until pushed.
- [x] Frontend dependency audit reports zero vulnerabilities after compatible minimatch override.
- [ ] Full backend dependency/CVE review and secret scan evidence.
- [ ] Prometheus/Grafana/OTel dashboards and measured incident drills.
- [ ] 100k benchmark, query plans and measured latency targets.
- [x] Isolated synthetic PostgreSQL backup/restore smoke: counts and ordered ticket digest match.
- [ ] Full DB/object backup/restore drill, hardened deployment and P4 release evidence.
