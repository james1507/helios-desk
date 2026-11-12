package com.heliosdesk.notifications;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.ApiException;
import com.heliosdesk.tickets.TicketDtos.Page;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  public record Notice(
      UUID id,
      long sequence,
      UUID ticketId,
      String type,
      long version,
      Instant createdAt,
      @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant readAt) {}

  private final JdbcTemplate db;

  public NotificationService(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public void deliver(
      UUID event,
      UUID tenant,
      UUID ticket,
      String type,
      String visibility,
      long version,
      UUID actor) {
    boolean customerVisible =
        visibility.equals("PUBLIC")
            && Set.of("agent.replied", "ticket.status_changed").contains(type);
    var recipients =
        db.queryForList(
            """
   SELECT m.id FROM tenant_memberships m JOIN users u ON u.id=m.user_id JOIN tickets t ON t.tenant_id=m.tenant_id AND t.id=?
   WHERE m.tenant_id=? AND m.status='ACTIVE' AND u.disabled_at IS NULL AND (?::uuid IS NULL OR m.id<>?)
   AND ((m.role='REQUESTER' AND m.id=t.requester_membership_id AND ?)
     OR (m.role IN ('OWNER','ADMIN','MANAGER','AUDITOR'))
     OR (m.role='AGENT' AND (t.team_id IS NULL OR t.assignee_membership_id=m.id OR EXISTS(
       SELECT 1 FROM team_members tm JOIN teams team ON team.tenant_id=tm.tenant_id AND team.id=tm.team_id
       WHERE tm.tenant_id=t.tenant_id AND tm.team_id=t.team_id AND tm.membership_id=m.id AND team.is_active))))
   ORDER BY m.id
   """,
            UUID.class,
            ticket,
            tenant,
            actor,
            actor,
            customerVisible);
    for (UUID recipient : recipients) {
      db.update(
          "INSERT INTO notification_counters(tenant_id,recipient_membership_id) VALUES (?,?) ON CONFLICT DO NOTHING",
          tenant,
          recipient);
      db.queryForObject(
          "SELECT next_value FROM notification_counters WHERE tenant_id=? AND recipient_membership_id=? FOR UPDATE",
          Long.class,
          tenant,
          recipient);
      boolean exists =
          Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM notifications WHERE tenant_id=? AND recipient_membership_id=? AND outbox_event_id=?)",
                  Boolean.class,
                  tenant,
                  recipient,
                  event));
      if (exists) continue;
      long sequence =
          db.queryForObject(
              "UPDATE notification_counters SET next_value=next_value+1 WHERE tenant_id=? AND recipient_membership_id=? RETURNING next_value-1",
              Long.class,
              tenant,
              recipient);
      db.update(
          "INSERT INTO notifications(id,tenant_id,recipient_membership_id,recipient_sequence,outbox_event_id,ticket_id,type,ticket_version,created_at) VALUES (?,?,?,?,?,?,?,?,now())",
          UUID.randomUUID(),
          tenant,
          recipient,
          sequence,
          event,
          ticket,
          type,
          version);
    }
  }

  public Page<Notice> feed(Actor a, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 100)
      throw new ApiException(400, "INVALID_CURSOR", "Invalid notification cursor or limit.");
    var rows =
        db.query(
            """
   SELECT n.* FROM notifications n JOIN tickets t ON t.tenant_id=n.tenant_id AND t.id=n.ticket_id
   WHERE n.tenant_id=? AND n.recipient_membership_id=? AND n.recipient_sequence>?
   AND (?<>'REQUESTER' OR (t.requester_membership_id=? AND n.type IN ('agent.replied','ticket.status_changed')))
   AND (?<>'AGENT' OR t.assignee_membership_id=? OR t.team_id IS NULL OR EXISTS(SELECT 1 FROM team_members tm JOIN teams tt ON tt.tenant_id=tm.tenant_id AND tt.id=tm.team_id WHERE tm.tenant_id=t.tenant_id AND tm.team_id=t.team_id AND tm.membership_id=? AND tt.is_active))
   ORDER BY n.recipient_sequence LIMIT ?
   """,
            (r, i) ->
                new Notice(
                    r.getObject("id", UUID.class),
                    r.getLong("recipient_sequence"),
                    r.getObject("ticket_id", UUID.class),
                    r.getString("type"),
                    r.getLong("ticket_version"),
                    r.getTimestamp("created_at").toInstant(),
                    r.getTimestamp("read_at") == null
                        ? null
                        : r.getTimestamp("read_at").toInstant()),
            a.tenantId(),
            a.membershipId(),
            after,
            a.role(),
            a.membershipId(),
            a.role(),
            a.membershipId(),
            a.membershipId(),
            limit + 1);
    boolean more = rows.size() > limit;
    var items = rows.subList(0, Math.min(rows.size(), limit));
    return new Page<>(items, more ? Long.toString(items.getLast().sequence()) : null, null);
  }

  public long latest(Actor a) {
    Long value =
        db.queryForObject(
            "SELECT coalesce(max(recipient_sequence),0) FROM notifications WHERE tenant_id=? AND recipient_membership_id=?",
            Long.class,
            a.tenantId(),
            a.membershipId());
    return value == null ? 0 : value;
  }

  public long unread(Actor a) {
    return db.queryForObject(
        "SELECT count(*) FROM notifications WHERE tenant_id=? AND recipient_membership_id=? AND read_at IS NULL",
        Long.class,
        a.tenantId(),
        a.membershipId());
  }

  public void read(Actor a, UUID id) {
    int updated =
        db.update(
            "UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE tenant_id=? AND recipient_membership_id=? AND id=?",
            a.tenantId(),
            a.membershipId(),
            id);
    if (updated == 0) throw ApiException.missing();
  }

  public boolean active(Actor a) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenant_memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.id=? AND m.user_id=? AND m.status='ACTIVE' AND m.role=? AND u.disabled_at IS NULL AND t.status='ACTIVE')",
            Boolean.class,
            a.tenantId(),
            a.membershipId(),
            a.userId(),
            a.role()));
  }
}
