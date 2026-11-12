package com.heliosdesk.servicelevels;

import com.heliosdesk.platform.Json;
import com.heliosdesk.tickets.Ticket;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SlaService {
  public record Target(long responseMinutes, long resolutionMinutes) {}

  public record Policy(
      BusinessCalendar calendar,
      Map<String, Target> targets,
      boolean pauseOnPending,
      int warningRatio) {}

  private final JdbcTemplate db;
  private final Json json;

  public SlaService(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public Policy policy(UUID tenant) {
    return db.queryForObject(
        "SELECT calendar::text,targets::text,pause_on_pending,warning_ratio FROM sla_policies WHERE tenant_id=? ORDER BY name LIMIT 1",
        (r, n) ->
            new Policy(
                json.read(r.getString(1), BusinessCalendar.class),
                readTargets(r.getString(2)),
                r.getBoolean(3),
                r.getInt(4)),
        tenant);
  }

  private Map<String, Target> readTargets(String text) {
    return json.read(
        text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Target>>() {});
  }

  public static Map<String, Target> defaults() {
    return Map.of(
        "URGENT",
        new Target(30, 240),
        "HIGH",
        new Target(60, 480),
        "NORMAL",
        new Target(240, 1440),
        "LOW",
        new Target(480, 2880));
  }

  public void start(Ticket t, Instant now, boolean reopen) {
    Policy p = policy(t.tenantId);
    Target target = p.targets().get(t.priority);
    int cycle =
        db.queryForObject(
            "SELECT coalesce(max(cycle_number),0)+1 FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=?",
            Integer.class,
            t.tenantId,
            t.id);
    db.update(
        "INSERT INTO ticket_sla_cycles(id,tenant_id,ticket_id,cycle_number,policy_snapshot,cycle_started_at,resolution_started_at,first_response_due_at,resolution_due_at) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
        UUID.randomUUID(),
        t.tenantId,
        t.id,
        cycle,
        json.write(p),
        Timestamp.from(now),
        Timestamp.from(now),
        reopen ? null : Timestamp.from(p.calendar().add(now, target.responseMinutes() * 60)),
        Timestamp.from(p.calendar().add(now, target.resolutionMinutes() * 60)));
  }

  public void refreshWarnings(Ticket t, Instant now) {
    var rows =
        db.queryForList(
            "SELECT id,policy_snapshot::text,cycle_started_at,consumed_seconds,resolution_started_at,paused_at,cycle_number FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=? AND completed_at IS NULL ORDER BY cycle_number DESC LIMIT 1",
            t.tenantId,
            t.id);
    if (rows.isEmpty()) return;
    var c = rows.getFirst();
    Policy p = json.read((String) c.get("policy_snapshot"), Policy.class);
    Target target = p.targets().get(t.priority);
    long threshold = target.resolutionMinutes() * 60 * (100 - p.warningRatio()) / 100;
    Instant resolutionWarning =
        c.get("paused_at") == null
            ? resolutionDeadline(
                t.tenantId,
                (UUID) c.get("id"),
                p.calendar(),
                ((Timestamp) c.get("cycle_started_at")).toInstant(),
                threshold,
                now)
            : null;
    Instant responseWarning =
        ((Number) c.get("cycle_number")).intValue() == 1
            ? p.calendar()
                .add(
                    ((Timestamp) c.get("cycle_started_at")).toInstant(),
                    target.responseMinutes() * 60 * (100 - p.warningRatio()) / 100)
            : null;
    db.update(
        "UPDATE ticket_sla_cycles SET first_response_warning_at=?,resolution_warning_at=? WHERE tenant_id=? AND id=?",
        responseWarning == null ? null : Timestamp.from(responseWarning),
        resolutionWarning == null ? null : Timestamp.from(resolutionWarning),
        t.tenantId,
        c.get("id"));
  }

  public void publicReply(Ticket t, Instant now) {
    db.update(
        "UPDATE ticket_sla_cycles SET first_response_at=? WHERE tenant_id=? AND ticket_id=? AND cycle_number=1 AND first_response_at IS NULL",
        Timestamp.from(now),
        t.tenantId,
        t.id);
  }

  public void transition(Ticket t, String old, Instant now) {
    if (old.equals("RESOLVED") && t.status.equals("OPEN")) {
      start(t, now, true);
      return;
    }
    var rows =
        db.queryForList(
            "SELECT id,policy_snapshot::text,cycle_started_at,consumed_seconds,resolution_started_at,paused_at FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=? AND completed_at IS NULL ORDER BY cycle_number DESC LIMIT 1",
            t.tenantId,
            t.id);
    if (rows.isEmpty()) return;
    var c = rows.getFirst();
    UUID id = (UUID) c.get("id");
    Policy p = json.read((String) c.get("policy_snapshot"), Policy.class);
    long consumed = ((Number) c.get("consumed_seconds")).longValue();
    if (c.get("paused_at") == null)
      consumed +=
          p.calendar().between(((Timestamp) c.get("resolution_started_at")).toInstant(), now);
    if (old.equals("PENDING_CUSTOMER") && c.get("paused_at") != null) {
      db.update(
          "UPDATE sla_pause_intervals SET ended_at=? WHERE tenant_id=? AND cycle_id=? AND ended_at IS NULL",
          Timestamp.from(now),
          t.tenantId,
          id);
      Instant resumedDue =
          resolutionDeadline(
              t.tenantId,
              id,
              p.calendar(),
              ((Timestamp) c.get("cycle_started_at")).toInstant(),
              p.targets().get(t.priority).resolutionMinutes() * 60,
              now);
      db.update(
          "UPDATE ticket_sla_cycles SET paused_at=NULL,resolution_started_at=?,resolution_due_at=? WHERE tenant_id=? AND id=?",
          Timestamp.from(now),
          Timestamp.from(resumedDue),
          t.tenantId,
          id);
    }
    if (t.status.equals("PENDING_CUSTOMER") && p.pauseOnPending()) {
      db.update(
          "UPDATE ticket_sla_cycles SET paused_at=? WHERE tenant_id=? AND id=?",
          Timestamp.from(now),
          t.tenantId,
          id);
      db.update(
          "INSERT INTO sla_pause_intervals(id,tenant_id,cycle_id,started_at) VALUES (?,?,?,?)",
          UUID.randomUUID(),
          t.tenantId,
          id,
          Timestamp.from(now));
    }
    db.update(
        "UPDATE ticket_sla_cycles SET consumed_seconds=?,resolution_started_at=?,completed_at=? WHERE tenant_id=? AND id=?",
        consumed,
        Timestamp.from(now),
        t.status.equals("RESOLVED") ? Timestamp.from(now) : null,
        t.tenantId,
        id);
  }

  public void priority(Ticket t, Instant now) {
    var rows =
        db.queryForList(
            "SELECT id,policy_snapshot::text,cycle_started_at,consumed_seconds,resolution_started_at,paused_at FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=? AND completed_at IS NULL ORDER BY cycle_number DESC LIMIT 1",
            t.tenantId,
            t.id);
    if (rows.isEmpty()) return;
    var c = rows.getFirst();
    Policy p = json.read((String) c.get("policy_snapshot"), Policy.class);
    Target target = p.targets().get(t.priority);
    long elapsed = ((Number) c.get("consumed_seconds")).longValue();
    if (c.get("paused_at") == null)
      elapsed +=
          p.calendar().between(((Timestamp) c.get("resolution_started_at")).toInstant(), now);
    Instant response =
        p.calendar()
            .add(
                ((Timestamp) c.get("cycle_started_at")).toInstant(), target.responseMinutes() * 60);
    Instant due =
        resolutionDeadline(
            t.tenantId,
            (UUID) c.get("id"),
            p.calendar(),
            ((Timestamp) c.get("cycle_started_at")).toInstant(),
            target.resolutionMinutes() * 60,
            now);
    db.update(
        "UPDATE ticket_sla_cycles SET first_response_due_at=CASE WHEN cycle_number=1 THEN ?::timestamptz ELSE NULL END,resolution_due_at=?,consumed_seconds=?,resolution_started_at=? WHERE tenant_id=? AND id=?",
        Timestamp.from(response),
        Timestamp.from(due),
        elapsed,
        Timestamp.from(now),
        t.tenantId,
        c.get("id"));
  }

  // Replaying durable pauses preserves a past breach when a priority target shrinks.
  private Instant resolutionDeadline(
      UUID tenant,
      UUID cycle,
      BusinessCalendar calendar,
      Instant started,
      long seconds,
      Instant now) {
    Instant cursor = started;
    for (var pause :
        db.queryForList(
            "SELECT started_at,ended_at FROM sla_pause_intervals WHERE tenant_id=? AND cycle_id=? ORDER BY started_at,id",
            tenant,
            cycle)) {
      Instant paused = ((Timestamp) pause.get("started_at")).toInstant();
      long consumed = calendar.between(cursor, paused);
      if (seconds <= consumed) return calendar.add(cursor, seconds);
      seconds -= consumed;
      cursor =
          pause.get("ended_at") == null ? now : ((Timestamp) pause.get("ended_at")).toInstant();
    }
    return calendar.add(cursor, seconds);
  }
}
