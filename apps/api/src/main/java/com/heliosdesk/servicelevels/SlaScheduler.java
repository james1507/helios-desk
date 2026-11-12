package com.heliosdesk.servicelevels;

import com.heliosdesk.platform.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SlaScheduler {
  private final JdbcTemplate db;
  private final Json json;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private final boolean enabled;

  public SlaScheduler(
      JdbcTemplate db,
      Json json,
      Clock clock,
      PlatformTransactionManager manager,
      MeterRegistry metrics,
      @Value("${helios.workers-enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.metrics = metrics;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${helios.sla-delay-ms:10000}")
  public void tick() {
    if (enabled) evaluate(clock.instant());
  }

  public void evaluate(Instant now) {
    tx.executeWithoutResult(
        status -> {
          var cycles =
              db.queryForList(
                  """
   SELECT c.*,t.priority,t.version,t.requester_membership_id FROM ticket_sla_cycles c JOIN tickets t ON t.tenant_id=c.tenant_id AND t.id=c.ticket_id
   WHERE (c.first_response_due_at<=? AND (c.first_response_at IS NULL OR c.first_response_at>c.first_response_due_at)
     AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='FIRST_RESPONSE'))
   OR ((c.paused_at IS NULL OR c.resolution_due_at<=c.paused_at) AND c.resolution_due_at<=? AND (c.completed_at IS NULL OR c.completed_at>c.resolution_due_at)
     AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='RESOLUTION'))
   OR (c.first_response_at IS NULL AND c.first_response_warning_at<=? AND NOT EXISTS(SELECT 1 FROM sla_warnings w WHERE w.tenant_id=c.tenant_id AND w.cycle_id=c.id AND w.metric='FIRST_RESPONSE') AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='FIRST_RESPONSE'))
   OR (c.completed_at IS NULL AND c.paused_at IS NULL AND c.resolution_warning_at<=? AND NOT EXISTS(SELECT 1 FROM sla_warnings w WHERE w.tenant_id=c.tenant_id AND w.cycle_id=c.id AND w.metric='RESOLUTION') AND NOT EXISTS(SELECT 1 FROM sla_breaches b WHERE b.tenant_id=c.tenant_id AND b.cycle_id=c.id AND b.metric='RESOLUTION'))
   ORDER BY c.resolution_due_at,c.id FOR UPDATE OF c SKIP LOCKED LIMIT 300
   """,
                  Timestamp.from(now),
                  Timestamp.from(now),
                  Timestamp.from(now),
                  Timestamp.from(now));
          for (var c : cycles) {
            metrics.counter("sla_evaluations_total").increment();
            var p =
                json.read((String) c.get("policy_snapshot").toString(), SlaService.Policy.class);
            metric(
                c,
                p,
                "FIRST_RESPONSE",
                "first_response_due_at",
                "first_response_at",
                p.targets().get((String) c.get("priority")).responseMinutes(),
                now);
            if (c.get("paused_at") == null
                || !((Timestamp) c.get("resolution_due_at")).after((Timestamp) c.get("paused_at")))
              metric(
                  c,
                  p,
                  "RESOLUTION",
                  "resolution_due_at",
                  "completed_at",
                  p.targets().get((String) c.get("priority")).resolutionMinutes(),
                  now);
          }
        });
  }

  private void metric(
      Map<String, Object> c,
      SlaService.Policy policy,
      String metric,
      String dueColumn,
      String stopColumn,
      long target,
      Instant now) {
    Timestamp due = (Timestamp) c.get(dueColumn), stop = (Timestamp) c.get(stopColumn);
    if (due == null) return;
    UUID tenant = (UUID) c.get("tenant_id"), cycle = (UUID) c.get("id");
    boolean breached =
        !now.isBefore(due.toInstant())
            && (stop == null || stop.toInstant().isAfter(due.toInstant()));
    if (breached) {
      int inserted =
          db.update(
              "INSERT INTO sla_breaches(id,tenant_id,ticket_id,cycle_id,metric,breached_at) VALUES (?,?,?,?,?,?) ON CONFLICT(tenant_id,cycle_id,metric) DO NOTHING",
              UUID.randomUUID(),
              tenant,
              c.get("ticket_id"),
              cycle,
              metric,
              due);
      if (inserted > 0) {
        metrics.counter("sla_breaches_total", "metric", metric).increment();
        intent(c, "sla.breached", metric, now);
      }
    } else if (stop == null
        && now.isBefore(due.toInstant())
        && policy.calendar().between(now, due.toInstant())
            <= target * 60 * policy.warningRatio() / 100) {
      int inserted =
          db.update(
              "INSERT INTO sla_warnings(tenant_id,cycle_id,metric,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
              tenant,
              cycle,
              metric,
              Timestamp.from(now));
      if (inserted > 0) intent(c, "sla.warning", metric, now);
    }
  }

  private void intent(Map<String, Object> c, String type, String metric, Instant now) {
    String dedup = c.get("id") + ":" + metric + ":" + type;
    var payload =
        new OutboxWorker.Payload(
            (UUID) c.get("ticket_id"),
            ((Number) c.get("version")).longValue(),
            null,
            "PRIVATE",
            type);
    db.update(
        "INSERT INTO outbox_events(id,tenant_id,event_type,aggregate_id,payload,created_at,available_at,dedup_key) VALUES (?,?,?,?,?::jsonb,?,?,?) ON CONFLICT(tenant_id,dedup_key) DO NOTHING",
        UUID.randomUUID(),
        c.get("tenant_id"),
        type,
        c.get("ticket_id"),
        json.write(payload),
        Timestamp.from(now),
        Timestamp.from(now),
        dedup);
  }
}
