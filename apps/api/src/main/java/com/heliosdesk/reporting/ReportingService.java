package com.heliosdesk.reporting;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportingService {
  public record Count(String name, long count) {}

  public record Trend(String day, long created, long resolved) {}

  public record Compliance(String metric, long met, long eligible) {}

  public record Overview(
      Instant from,
      Instant to,
      String timezone,
      List<Count> statuses,
      List<Count> priorities,
      List<Count> workload,
      List<Trend> trend,
      List<Compliance> compliance) {}

  private final JdbcTemplate db;
  private final Clock clock;

  public ReportingService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Overview overview(Actor a, Instant from, Instant to) {
    a.staff();
    if (from == null) from = clock.instant().minus(Duration.ofDays(30));
    if (to == null) to = clock.instant();
    if (!from.isBefore(to) || Duration.between(from, to).toDays() > 366)
      throw new ApiException(400, "INVALID_DATE_RANGE", "Choose a positive range up to 366 days.");
    String scope = "t.tenant_id=?";
    var args = new ArrayList<Object>();
    args.add(a.tenantId());
    if (a.role().equals("AGENT")) {
      scope +=
          " AND (t.assignee_membership_id=? OR t.team_id IS NULL OR EXISTS(SELECT 1 FROM team_members x JOIN teams tt ON tt.tenant_id=x.tenant_id AND tt.id=x.team_id WHERE x.tenant_id=t.tenant_id AND x.team_id=t.team_id AND x.membership_id=? AND tt.is_active))";
      args.add(a.membershipId());
      args.add(a.membershipId());
    }
    var statuses =
        counts(
            "SELECT status name,count(*) count FROM tickets t WHERE "
                + scope
                + " GROUP BY status ORDER BY status",
            args);
    var priorities =
        counts(
            "SELECT priority name,count(*) count FROM tickets t WHERE "
                + scope
                + " AND status NOT IN ('RESOLVED','CLOSED') GROUP BY priority ORDER BY priority",
            args);
    var workload =
        counts(
            "SELECT coalesce(u.display_name,'Unassigned') name,count(*) count FROM tickets t LEFT JOIN tenant_memberships m ON m.tenant_id=t.tenant_id AND m.id=t.assignee_membership_id LEFT JOIN users u ON u.id=m.user_id WHERE "
                + scope
                + " AND t.status NOT IN ('RESOLVED','CLOSED') GROUP BY m.id,u.display_name ORDER BY count(*) DESC",
            args);
    var dated = new ArrayList<>(args);
    dated.add(Timestamp.from(from));
    dated.add(Timestamp.from(to));
    var created =
        db.query(
            "SELECT (t.created_at AT TIME ZONE 'UTC')::date::text AS report_day,count(*) FROM tickets t WHERE "
                + scope
                + " AND t.created_at>=? AND t.created_at<? GROUP BY report_day ORDER BY report_day",
            (r, n) -> new Trend(r.getString(1), r.getLong(2), 0),
            dated.toArray());
    var resolved =
        db.query(
            "SELECT (c.completed_at AT TIME ZONE 'UTC')::date::text AS report_day,count(*) FROM ticket_sla_cycles c JOIN tickets t ON t.tenant_id=c.tenant_id AND t.id=c.ticket_id WHERE "
                + scope
                + " AND c.completed_at>=? AND c.completed_at<? GROUP BY report_day ORDER BY report_day",
            (r, n) -> new Trend(r.getString(1), 0, r.getLong(2)),
            dated.toArray());
    var trend = new TreeMap<String, Trend>();
    for (var t : created) trend.put(t.day(), t);
    for (var t : resolved)
      trend.merge(t.day(), t, (x, y) -> new Trend(x.day(), x.created(), y.resolved()));
    var slaArgs = new ArrayList<>(dated);
    slaArgs.add(Timestamp.from(clock.instant()));
    var response =
        db.queryForObject(
            "SELECT count(*) FILTER(WHERE c.first_response_at<=c.first_response_due_at AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='FIRST_RESPONSE')),count(*) FROM ticket_sla_cycles c JOIN tickets t ON t.tenant_id=c.tenant_id AND t.id=c.ticket_id WHERE "
                + scope
                + " AND c.cycle_started_at>=? AND c.cycle_started_at<? AND c.first_response_due_at IS NOT NULL AND (c.first_response_at IS NOT NULL OR c.first_response_due_at<=?)",
            (r, n) -> new Compliance("FIRST_RESPONSE", r.getLong(1), r.getLong(2)),
            slaArgs.toArray());
    var resolution =
        db.queryForObject(
            "SELECT count(*) FILTER(WHERE c.completed_at<=c.resolution_due_at AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='RESOLUTION')),count(*) FROM ticket_sla_cycles c JOIN tickets t ON t.tenant_id=c.tenant_id AND t.id=c.ticket_id WHERE "
                + scope
                + " AND c.cycle_started_at>=? AND c.cycle_started_at<? AND (c.completed_at IS NOT NULL OR (c.paused_at IS NULL AND c.resolution_due_at<=?) OR EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='RESOLUTION'))",
            (r, n) -> new Compliance("RESOLUTION", r.getLong(1), r.getLong(2)),
            slaArgs.toArray());
    return new Overview(
        from,
        to,
        "UTC",
        statuses,
        priorities,
        workload,
        new ArrayList<>(trend.values()),
        List.of(response, resolution));
  }

  private List<Count> counts(String sql, List<Object> args) {
    return db.query(
        sql, (r, n) -> new Count(r.getString("name"), r.getLong("count")), args.toArray());
  }
}
