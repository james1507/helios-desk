# Core product demo (3–5 minutes)

1. Open http://localhost:3100. Login `customer1@northstar.test` with local demo password. Create a ticket with a unique subject. Reload; persistence remains visible.
2. In a separate browser profile login `agent1@northstar.test`. Find the subject, open ticket, choose Assign to me. Add an Internal note; persistent amber composer explains visibility.
3. Reload customer portal. Inspect its message response: note text is absent. Send a public reply as agent, then reply as customer.
4. As agent resolve ticket with a clear public resolution message. Customer sees the resolution. Reply within seven days to reopen and retain cycle history.
5. Login manager in another profile. Review status/priority, use Manage assignment, inspect Audit logs and safe correlation IDs.
6. Open Notifications as customer; public activity persists and can be marked read. Keep a reply draft open while staff responds; use the explicit new-activity refresh without losing the draft.
7. As manager open Reports, inspect chart/table and formula definitions, then Service levels to edit targets/business hours. Existing cycles preserve their snapshots. Team directory supports staff team membership and soft deactivation.
8. Login `owner@bluepeak.test`. A Northstar ticket URL returns resource not found. `owner@northstar.test` also belongs to Bluepeak; switch workspace and observe another open tab update.

Screenshot evidence in `docs/screenshots` comes from Playwright against the working application. CSAT, email and attachment flows remain unimplemented and are not part of this demo.
