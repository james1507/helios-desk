package com.heliosdesk.tickets;

import com.heliosdesk.platform.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TicketMaintenance {
  private final JdbcTemplate db;
  private final Json json;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public TicketMaintenance(
      JdbcTemplate db,
      Json json,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${helios.workers-enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 60000)
  public void tick() {
    if (enabled) closeDue(clock.instant());
  }

  @Scheduled(cron = "0 0 * * * *")
  public void cleanup() {
    if (!enabled) return;
    db.update("DELETE FROM idempotency_records WHERE expires_at<now()");
    db.update("DELETE FROM auth_attempts WHERE attempted_at<now()-interval '24 hours'");
  }

  public void closeDue(Instant now) {
    tx.executeWithoutResult(
        status -> {
          for (var t :
              db.queryForList(
                  "SELECT id,tenant_id,version FROM tickets WHERE status='RESOLVED' AND resolved_at<=? ORDER BY resolved_at,id FOR UPDATE SKIP LOCKED LIMIT 100",
                  Timestamp.from(now.minus(Duration.ofDays(7))))) {
            UUID id = (UUID) t.get("id"),
                tenant = (UUID) t.get("tenant_id"),
                event = UUID.randomUUID(),
                correlation = UUID.randomUUID();
            long version = ((Number) t.get("version")).longValue() + 1;
            db.update(
                "UPDATE tickets SET status='CLOSED',closed_at=?,updated_at=?,last_activity_at=?,version=? WHERE tenant_id=? AND id=?",
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                version,
                tenant,
                id);
            db.update(
                "INSERT INTO ticket_events(id,tenant_id,ticket_id,event_type,actor_id,visibility,correlation_id,data,created_at) VALUES (?,?,?,'ticket.status_changed',NULL,'PUBLIC',?,'{\"from\":\"RESOLVED\",\"to\":\"CLOSED\",\"automatic\":true}',?)",
                event,
                tenant,
                id,
                correlation,
                Timestamp.from(now));
            db.update(
                "INSERT INTO audit_logs(id,tenant_id,actor_membership_id,action,resource_type,resource_id,after_safe,correlation_id,created_at) VALUES (?,?,NULL,'ticket.auto_closed','TICKET',?,'{\"status\":\"CLOSED\"}',?,?)",
                UUID.randomUUID(),
                tenant,
                id,
                correlation,
                Timestamp.from(now));
            var payload =
                new OutboxWorker.Payload(id, version, null, "PUBLIC", "ticket.status_changed");
            db.update(
                "INSERT INTO outbox_events(id,tenant_id,event_type,aggregate_id,payload,created_at,available_at,dedup_key) VALUES (?,?, 'ticket.status_changed',?,?::jsonb,?,?,?)",
                UUID.randomUUID(),
                tenant,
                id,
                json.write(payload),
                Timestamp.from(now),
                Timestamp.from(now),
                event.toString());
          }
        });
  }
}
