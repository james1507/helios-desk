package com.heliosdesk.tickets;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.*;
import com.heliosdesk.tickets.TicketDtos.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TicketQuery {
  private final JdbcTemplate db;
  private final NamedParameterJdbcTemplate named;
  private final TicketRepository tickets;
  private final TicketAccess access;

  public TicketQuery(JdbcTemplate db, TicketRepository tickets, TicketAccess access) {
    this.db = db;
    this.named = new NamedParameterJdbcTemplate(db);
    this.tickets = tickets;
    this.access = access;
  }

  private static final String SELECT =
      """
  SELECT t.*,ru.display_name requester_name,ru.email requester_email,au.display_name assignee_name,tm.name team_name,
  s.first_response_due_at,s.resolution_due_at FROM tickets t
  JOIN tenant_memberships rm ON rm.tenant_id=t.tenant_id AND rm.id=t.requester_membership_id
  JOIN users ru ON ru.id=rm.user_id
  LEFT JOIN tenant_memberships am ON am.tenant_id=t.tenant_id AND am.id=t.assignee_membership_id
  LEFT JOIN users au ON au.id=am.user_id
  LEFT JOIN teams tm ON tm.tenant_id=t.tenant_id AND tm.id=t.team_id
  LEFT JOIN LATERAL (SELECT first_response_due_at,resolution_due_at FROM ticket_sla_cycles
    WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY cycle_number DESC LIMIT 1) s ON true
  """;

  public void authorize(Actor a, UUID id) {
    var rows =
        db.query(
            "SELECT id,tenant_id,requester_membership_id,assignee_membership_id,team_id FROM tickets WHERE tenant_id=? AND id=?",
            (r, n) -> {
              var t = new Ticket();
              t.id = r.getObject("id", UUID.class);
              t.tenantId = r.getObject("tenant_id", UUID.class);
              t.requesterMembershipId = r.getObject("requester_membership_id", UUID.class);
              t.assigneeMembershipId = r.getObject("assignee_membership_id", UUID.class);
              t.teamId = r.getObject("team_id", UUID.class);
              return t;
            },
            a.tenantId(),
            id);
    if (rows.isEmpty()) throw ApiException.missing();
    access.read(a, rows.getFirst());
  }

  public Ticket require(Actor a, UUID id) {
    Ticket t = tickets.findByTenantIdAndId(a.tenantId(), id).orElseThrow(ApiException::missing);
    access.read(a, t);
    return t;
  }

  public View detail(Actor a, UUID id) {
    require(a, id);
    return db.queryForObject(
        SELECT + " WHERE t.tenant_id=? AND t.id=?", (r, n) -> view(a, r), a.tenantId(), id);
  }

  public Page<View> list(
      Actor a,
      String q,
      String status,
      String priority,
      String queue,
      String source,
      UUID teamId,
      UUID assigneeId,
      String cursor,
      int limit) {
    if (limit < 1 || limit > 100)
      throw new ApiException(400, "INVALID_LIMIT", "Limit must be between 1 and 100.");
    if (status != null && !Workflow.STATUSES.contains(status))
      throw new ApiException(400, "INVALID_FILTER", "Unknown status.");
    if (priority != null && !Workflow.PRIORITIES.contains(priority))
      throw new ApiException(400, "INVALID_FILTER", "Unknown priority.");
    if (q != null && q.length() > 200)
      throw new ApiException(400, "INVALID_FILTER", "Search is too long.");
    var p = new HashMap<String, Object>();
    p.put("tenant", a.tenantId());
    p.put("member", a.membershipId());
    p.put("limit", limit + 1);
    String where = " WHERE t.tenant_id=:tenant";
    if (a.customer()) where += " AND t.requester_membership_id=:member";
    if (a.role().equals("AGENT"))
      where +=
          " AND (t.assignee_membership_id=:member OR t.team_id IS NULL OR EXISTS(SELECT 1 FROM team_members x JOIN teams xt ON xt.tenant_id=x.tenant_id AND xt.id=x.team_id AND xt.is_active WHERE x.tenant_id=t.tenant_id AND x.team_id=t.team_id AND x.membership_id=:member))";
    if (status != null) {
      where += " AND t.status=:status";
      p.put("status", status);
    }
    if (priority != null) {
      where += " AND t.priority=:priority";
      p.put("priority", priority);
    }
    if (source != null) {
      if (!Set.of("PORTAL", "AGENT", "EMAIL").contains(source))
        throw new ApiException(400, "INVALID_FILTER", "Unknown source.");
      where += " AND t.source=:source";
      p.put("source", source);
    }
    if (teamId != null) {
      a.staff();
      where += " AND t.team_id=:team";
      p.put("team", teamId);
    }
    if (assigneeId != null) {
      a.staff();
      where += " AND t.assignee_membership_id=:assignee";
      p.put("assignee", assigneeId);
    }
    if (queue != null)
      switch (queue) {
        case "mine" -> {
          a.staff();
          where += " AND t.assignee_membership_id=:member";
        }
        case "unassigned" -> {
          a.staff();
          where +=
              " AND t.assignee_membership_id IS NULL AND t.status NOT IN ('RESOLVED','CLOSED')";
        }
        case "team" -> {
          a.staff();
          where +=
              " AND EXISTS(SELECT 1 FROM team_members x JOIN teams xt ON xt.tenant_id=x.tenant_id AND xt.id=x.team_id AND xt.is_active WHERE x.tenant_id=t.tenant_id AND x.team_id=t.team_id AND x.membership_id=:member)";
        }
        case "at-risk" -> {
          a.staff();
          where +=
              " AND EXISTS(SELECT 1 FROM ticket_sla_cycles c WHERE c.tenant_id=t.tenant_id AND c.ticket_id=t.id AND ((c.first_response_at IS NULL AND c.first_response_warning_at<=now() AND c.first_response_due_at>now()) OR (c.completed_at IS NULL AND c.paused_at IS NULL AND c.resolution_warning_at<=now() AND c.resolution_due_at>now())))";
        }
        case "overdue" -> {
          a.staff();
          where +=
              " AND EXISTS(SELECT 1 FROM ticket_sla_cycles c WHERE c.tenant_id=t.tenant_id AND c.ticket_id=t.id AND ((c.first_response_at IS NULL AND c.first_response_due_at<=now()) OR (c.completed_at IS NULL AND c.paused_at IS NULL AND c.resolution_due_at<=now())))";
        }
        default -> throw new ApiException(400, "INVALID_FILTER", "Unknown queue.");
      }
    if (q != null && !q.isBlank()) {
      p.put("q", q.trim());
      p.put("like", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
      where +=
          " AND (to_tsvector('english',t.subject) @@ plainto_tsquery('english',:q) OR t.subject ILIKE :like OR ('HD-'||lpad(t.ticket_number::text,greatest(6,length(t.ticket_number::text)),'0')) ILIKE :like OR EXISTS(SELECT 1 FROM ticket_messages m WHERE m.tenant_id=t.tenant_id AND m.ticket_id=t.id AND m.visibility='PUBLIC' AND to_tsvector('english',m.body) @@ plainto_tsquery('english',:q))";
      if (!a.customer())
        where +=
            " OR EXISTS(SELECT 1 FROM tenant_memberships rm JOIN users u ON u.id=rm.user_id WHERE rm.tenant_id=t.tenant_id AND rm.id=t.requester_membership_id AND u.email ILIKE :like)";
      where += ")";
    }
    Long count = named.queryForObject("SELECT count(*) FROM tickets t" + where, p, Long.class);
    if (cursor != null) {
      var c = decode(cursor);
      where += " AND (t.last_activity_at,t.id)<(:cursorAt,:cursorId)";
      p.put("cursorAt", Timestamp.from(c.at()));
      p.put("cursorId", c.id());
    }
    var result =
        named.query(
            SELECT + where + " ORDER BY t.last_activity_at DESC,t.id DESC LIMIT :limit",
            p,
            (r, n) -> view(a, r));
    boolean more = result.size() > limit;
    var items = result.subList(0, Math.min(limit, result.size()));
    String next = null;
    if (more) {
      var last = items.getLast();
      next = encode(last.updatedAt(), last.id());
    }
    return new Page<>(items, next, count);
  }

  public Page<Message> messages(Actor a, UUID id, String cursor, int limit) {
    require(a, id);
    if (limit < 1 || limit > 100)
      throw new ApiException(400, "INVALID_LIMIT", "Limit must be 1–100.");
    var args = new ArrayList<Object>(List.of(a.tenantId(), id));
    String where = " WHERE m.tenant_id=? AND m.ticket_id=?";
    if (!a.privateNotes()) where += " AND m.visibility='PUBLIC'";
    if (cursor != null) {
      var c = decode(cursor);
      where += " AND (m.created_at,m.id)>(?,?)";
      args.add(Timestamp.from(c.at()));
      args.add(c.id());
    }
    args.add(limit + 1);
    var result =
        db.query(
            "SELECT m.*,u.display_name,tm.role FROM ticket_messages m JOIN tenant_memberships tm ON tm.tenant_id=m.tenant_id AND tm.id=m.author_membership_id JOIN users u ON u.id=tm.user_id"
                + where
                + " ORDER BY m.created_at,m.id LIMIT ?",
            (r, n) ->
                new Message(
                    r.getObject("id", UUID.class),
                    r.getString("body"),
                    com.heliosdesk.conversations.Markdown.render(r.getString("body")),
                    r.getString("visibility"),
                    r.getString("display_name"),
                    r.getString("role"),
                    instant(r, "created_at")),
            args.toArray());
    boolean more = result.size() > limit;
    var items = result.subList(0, Math.min(limit, result.size()));
    String next = more ? encode(items.getLast().createdAt(), items.getLast().id()) : null;
    return new Page<>(items, next, null);
  }

  public Page<Event> timeline(Actor a, UUID id, String cursor, int limit) {
    require(a, id);
    if (limit < 1 || limit > 100)
      throw new ApiException(400, "INVALID_LIMIT", "Limit must be 1–100.");
    var args = new ArrayList<Object>(List.of(a.tenantId(), id));
    String where = " WHERE e.tenant_id=? AND e.ticket_id=?";
    if (!a.privateNotes()) where += " AND e.visibility='PUBLIC'";
    if (cursor != null) {
      var c = decode(cursor);
      where += " AND (e.created_at,e.id)>(?,?)";
      args.add(Timestamp.from(c.at()));
      args.add(c.id());
    }
    args.add(limit + 1);
    var result =
        db.query(
            "SELECT e.*,coalesce(u.display_name,'Helios System') display_name FROM ticket_events e LEFT JOIN tenant_memberships m ON m.tenant_id=e.tenant_id AND m.id=e.actor_id LEFT JOIN users u ON u.id=m.user_id"
                + where
                + " ORDER BY e.created_at,e.id LIMIT ?",
            (r, n) ->
                new Event(
                    r.getObject("id", UUID.class),
                    r.getString("event_type"),
                    r.getString("display_name"),
                    Map.of(),
                    instant(r, "created_at")),
            args.toArray());
    boolean more = result.size() > limit;
    var items = result.subList(0, Math.min(limit, result.size()));
    return new Page<>(
        items, more ? encode(items.getLast().createdAt(), items.getLast().id()) : null, null);
  }

  private static View view(Actor a, ResultSet r) throws SQLException {
    boolean staff = !a.customer();
    return new View(
        r.getObject("id", UUID.class),
        r.getLong("ticket_number"),
        "HD-%06d".formatted(r.getLong("ticket_number")),
        r.getString("subject"),
        r.getString("status"),
        r.getString("priority"),
        r.getString("source"),
        r.getLong("version"),
        r.getObject("requester_membership_id", UUID.class),
        r.getString("requester_name"),
        r.getString("requester_email"),
        staff ? r.getObject("assignee_membership_id", UUID.class) : null,
        staff ? r.getString("assignee_name") : null,
        staff ? r.getObject("team_id", UUID.class) : null,
        staff ? r.getString("team_name") : null,
        r.getObject("category_id", UUID.class),
        instant(r, "created_at"),
        instant(r, "updated_at"),
        instant(r, "resolved_at"),
        staff ? instant(r, "first_response_due_at") : null,
        staff ? instant(r, "resolution_due_at") : null,
        !a.auditor() && !r.getString("status").equals("CLOSED"),
        a.manager());
  }

  private static Instant instant(ResultSet r, String field) throws SQLException {
    Timestamp t = r.getTimestamp(field);
    return t == null ? null : t.toInstant();
  }

  private record Cursor(Instant at, UUID id) {}

  private static String encode(Instant at, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
  }

  private static Cursor decode(String cursor) {
    try {
      if (cursor.length() > 200) throw new IllegalArgumentException();
      String[] p =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (p.length != 2) throw new IllegalArgumentException();
      return new Cursor(Instant.parse(p[0]), UUID.fromString(p[1]));
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Invalid pagination cursor.");
    }
  }
}
