# SLA clock design

`BusinessCalendar` consumes real seconds inside local-date intervals converted through an IANA ZoneId. Lunch/weekends/holidays consume no business time. Gaps use the timezone's forward resolution; overlap starts use the earlier offset and ends the later offset, explicitly counting both repeated hours. Zero-length/overlapping intervals and invalid weekdays/zones are rejected. A ten-year computation horizon bounds malformed configurations.

Cycle 1 snapshots calendar, targets and pause policy at creation. Only public human staff replies complete its response clock. Pending customer pauses resolution when enabled; on hold continues. A reopen creates a separate resolution cycle; the original completion remains frozen. Changes to active priority use the cycle snapshot, preserving elapsed business seconds.

Unit fixtures cover split shift 11:30 + 60 min = 13:30, Friday 17:30 + 60 = Monday 09:30, Monday holiday = Tuesday 09:30, pause 11:00–14:00 = resolution at 17:00, late response business duration, closed-hours start, spring/fall New York DST and invalid calendars. Integration verifies pause/resume and reopen history.

The scheduler locks indexed cycle candidates with `SKIP LOCKED`, persists warnings/breaches and outbox intent in one transaction, and reconciles late completed responses after downtime. A resolution deadline reached before pending-customer pause still breaches; a deadline that falls during an on-time pause does not. Twenty concurrent evaluations produce one breach/intent in integration tests. Unique cycle/metric constraints make repeated evaluation safe. Policy/calendar edits use versions and preserve cycle snapshots.

Warning instants are stored in business time. Pause intervals are replayed when recalculating priority deadlines; a reduced target can remain overdue. Automatic closure uses a nullable system actor, preserves completed cycles, and appends event/audit/outbox rows atomically.

Report dates use UTC, inclusive `from` and exclusive `to`. Resolution trends count completed cycles, including reopened cycles separately. Compliance denominator includes completed or overdue cycles begun in the range; first response applies only to cycle 1. Paused resolution cycles enter the denominator on completion, resume or recorded breach. Historical breaches cannot become compliant after a priority change. Empty denominators display an em dash. Workload/backlog are current scoped queues, independent of the selected trend range. Median business resolution/channel/CSAT metrics remain pending.
