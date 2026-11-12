# Production scenario evidence

Statuses apply only to the named evidence. Partial means the full specification scenario is still open. Backend methods are in `apps/api/src/test/java/com/heliosdesk/TicketIntegrationTest.java`; browser scenarios are in `apps/web/e2e/`.

| Scenario | Status | Evidence or remaining work |
| --- | --- | --- |
| PR-01 competing claims | Verified | `fiftyConcurrentClaimsOneWinner`: 50 clients, one winner |
| PR-02 duplicate create | Verified | `concurrentDuplicateRequests`: 20 concurrent replays |
| PR-03 changed replay body | Verified | `idempotencyAndMismatch` |
| PR-04 stale edits | Verified | `staleEditsAndReplyReplay`, browser draft-conflict journey |
| PR-05 crash after commit/before response | Partial | Stored replay outcome tested; process kill at this boundary pending |
| PR-06 crash before email dispatch | Partial | Notification lease recovery verified; email adapter pending |
| PR-07 provider accepts before worker ack | Pending | No email provider adapter |
| PR-08 inbound webhook replay | Pending | No inbound email adapter |
| PR-09 invalid email routing/signature | Pending | No inbound email adapter |
| PR-10 cross-tenant ticket ID | Verified | `crossTenantAndRequesterIsolation`, browser role/isolation journey |
| PR-11 private-note probes | Partial | API/search/SSE projections tested; exports/files not implemented |
| PR-12 Redis down | Not applicable | Core baseline has no Redis dependency |
| PR-13 database outage | Pending | Bounded acquisition/503 handler exists; controlled outage drill pending |
| PR-14 SSE disconnect | Verified | Browser drops network for 30 seconds; cursor replay/dedup/private exclusion/draft checks pass |
| PR-15 scheduler outage | Verified in simulation | `lateResponseIsBreachedEvenWhenSchedulerWasDown`; fake due/response instants, one intent |
| PR-16 pending across closed hours | Verified | BusinessCalendar fixtures + lifecycle pause/resume integration |
| PR-17 DST transition | Verified | New York spring/fall calendar fixtures |
| PR-18 forbidden uploads | Pending | No attachment pipeline |
| PR-19 membership revoked | Partial | Existing authenticated session denied in integration; deactivation-during-SSE E2E pending |
| PR-20 100k ticket query | Pending | No measured load dataset or EXPLAIN report |
| PR-21 mail provider throttles/fails | Pending | No email adapter |
| PR-22 competing SLA schedulers | Verified | 20 concurrent evaluations, one breach and one intent |
| PR-23 reply reopens resolution | Verified | Lifecycle integration preserves prior cycle |
| PR-24 reply to closed | Partial | Exact state matrix/API denial tested; dedicated portal scenario pending |
| PR-25 CSV injection | Pending | No export endpoint |
| PR-26 invite/reset token race | Pending | Account invitation/reset flows pending |
| PR-27 two tabs switch tenant | Verified | Production browser checks shared-session switch, cross-tenant denial and logout |
| PR-28 poison event | Verified for in-app worker | `privateNoteCannotNotifyRequesterAndPoisonMessageStops`, inspectable dead-letter row |

The isolated PostgreSQL backup/restore smoke check is documented in progress.md. No production fault-drill, RTO/RPO, scale or compliance claim is implied.
