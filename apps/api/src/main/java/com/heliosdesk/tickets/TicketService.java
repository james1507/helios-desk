package com.heliosdesk.tickets;

import com.heliosdesk.audit.AuditService;
import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.*;
import com.heliosdesk.servicelevels.SlaService;
import com.heliosdesk.tickets.TicketDtos.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private final JdbcTemplate db;
  private final TicketRepository tickets;
  private final TicketAccess access;
  private final TicketQuery query;
  private final Idempotency idempotency;
  private final AuditService audit;
  private final SlaService sla;
  private final Json json;
  private final Clock clock;

  public TicketService(
      JdbcTemplate db,
      TicketRepository tickets,
      TicketAccess access,
      TicketQuery query,
      Idempotency idempotency,
      AuditService audit,
      SlaService sla,
      Json json,
      Clock clock) {
    this.db = db;
    this.tickets = tickets;
    this.access = access;
    this.query = query;
    this.idempotency = idempotency;
    this.audit = audit;
    this.sla = sla;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public View create(Actor a, Create input, String key) {
    a.writer();
    return idempotency.run(
        a,
        "tickets.create",
        key,
        input,
        View.class,
        () -> {
          access.category(a, input.categoryId());
          validSubject(input.subject());
          String priority = input.priority() == null ? "NORMAL" : input.priority();
          UUID requester = a.membershipId();
          if (a.customer()) {
            if (!Set.of("NORMAL", "LOW").contains(priority)
                || input.requesterMembershipId() != null
                || "AGENT".equals(input.source())) throw ApiException.forbidden();
          } else {
            requester =
                input.requesterMembershipId() == null
                    ? a.membershipId()
                    : input.requesterMembershipId();
            if (!Boolean.TRUE.equals(
                db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tenant_memberships WHERE tenant_id=? AND id=? AND status='ACTIVE')",
                    Boolean.class,
                    a.tenantId(),
                    requester))) throw ApiException.missing();
          }
          long number =
              db.queryForObject(
                  "UPDATE ticket_counters SET next_value=next_value+1 WHERE tenant_id=? RETURNING next_value-1",
                  Long.class,
                  a.tenantId());
          Instant now = clock.instant();
          Ticket t =
              Ticket.create(
                  a.tenantId(),
                  number,
                  input.subject().trim(),
                  priority,
                  a.customer() ? "PORTAL" : "AGENT",
                  requester,
                  input.categoryId(),
                  now);
          tickets.saveAndFlush(t);
          message(a, t, input.description().trim(), "PUBLIC", UUID.randomUUID(), now);
          sla.start(t, now, false);
          sla.refreshWarnings(t, now);
          event(a, t, "ticket.created", "PUBLIC", Map.of(), now);
          return query.detail(a, t.id);
        });
  }

  @Transactional
  public View reply(Actor a, UUID id, Reply input, long expected, String key) {
    query.authorize(a, id);
    a.writer();
    if (input.visibility().equals("PRIVATE") && !a.privateNotes()) throw ApiException.forbidden();
    return idempotency.run(
        a,
        "tickets." + id + ".reply",
        key,
        List.of(input, expected),
        View.class,
        () -> {
          Ticket t = locked(a, id);
          access.write(a, t);
          version(t, expected);
          var old =
              db.queryForList(
                  "SELECT body,visibility,author_membership_id FROM ticket_messages WHERE tenant_id=? AND ticket_id=? AND client_message_id=?",
                  a.tenantId(),
                  id,
                  input.clientMessageId());
          if (!old.isEmpty()) {
            var m = old.getFirst();
            if (!m.get("body").equals(input.body().trim())
                || !m.get("visibility").equals(input.visibility())
                || !m.get("author_membership_id").equals(a.membershipId()))
              throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Message ID already used.");
            return query.detail(a, id);
          }
          Instant now = clock.instant();
          String previous = t.status;
          if (input.visibility().equals("PUBLIC")) {
            if (a.customer() && Set.of("PENDING_CUSTOMER", "RESOLVED").contains(t.status)) {
              if (t.status.equals("RESOLVED")) Workflow.reopen(t, now);
              t.status = "OPEN";
              t.resolvedAt = null;
            } else if (!a.customer() && t.status.equals("NEW")) t.status = "OPEN";
            if (!a.customer()) {
              sla.publicReply(t, now);
              t.lastPublicReplyAt = now;
            }
          }
          if (!previous.equals(t.status)) sla.transition(t, previous, now);
          sla.refreshWarnings(t, now);
          message(a, t, input.body().trim(), input.visibility(), input.clientMessageId(), now);
          t.touch(now);
          tickets.saveAndFlush(t);
          event(
              a,
              t,
              input.visibility().equals("PRIVATE")
                  ? "note.added"
                  : a.customer() ? "customer.replied" : "agent.replied",
              input.visibility(),
              Map.of(),
              now);
          if (!previous.equals(t.status))
            event(
                a,
                t,
                "ticket.status_changed",
                "PUBLIC",
                Map.of("from", previous, "to", t.status),
                now);
          return query.detail(a, id);
        });
  }

  @Transactional
  public View claim(Actor a, UUID id, long expected, String key) {
    a.staff();
    a.writer();
    query.authorize(a, id);
    return idempotency.run(
        a,
        "tickets." + id + ".claim",
        key,
        expected,
        View.class,
        () -> {
          Ticket t = locked(a, id);
          access.write(a, t);
          if (t.assigneeMembershipId != null)
            throw new ApiException(
                409, "TICKET_ALREADY_CLAIMED", "Another agent has claimed this ticket.", t.version);
          version(t, expected);
          if (a.role().equals("AGENT") && t.teamId != null && !access.inTeam(a, t.teamId))
            throw ApiException.forbidden();
          t.assigneeMembershipId = a.membershipId();
          if (t.status.equals("NEW")) t.status = "OPEN";
          Instant now = clock.instant();
          t.touch(now);
          tickets.saveAndFlush(t);
          event(a, t, "ticket.assigned", "PRIVATE", Map.of("assigneeId", a.membershipId()), now);
          return query.detail(a, id);
        });
  }

  @Transactional
  public View assign(Actor a, UUID id, Assign input, long expected) {
    a.manage();
    Ticket t = locked(a, id);
    access.write(a, t);
    version(t, expected);
    access.assignee(a, input.teamId(), input.assigneeId());
    if (Objects.equals(t.teamId, input.teamId())
        && Objects.equals(t.assigneeMembershipId, input.assigneeId())) return query.detail(a, id);
    t.teamId = input.teamId();
    t.assigneeMembershipId = input.assigneeId();
    if (t.status.equals("NEW") && input.assigneeId() != null) t.status = "OPEN";
    Instant now = clock.instant();
    t.touch(now);
    tickets.saveAndFlush(t);
    event(a, t, "ticket.assigned", "PRIVATE", Map.of(), now);
    return query.detail(a, id);
  }

  @Transactional
  public View transition(Actor a, UUID id, Transition input, long expected) {
    Ticket t = locked(a, id);
    access.write(a, t);
    a.staff();
    version(t, expected);
    Instant now = clock.instant();
    boolean hasReply =
        Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ticket_messages m JOIN tenant_memberships a ON a.tenant_id=m.tenant_id AND a.id=m.author_membership_id WHERE m.tenant_id=? AND m.ticket_id=? AND m.visibility='PUBLIC' AND a.role NOT IN ('REQUESTER','AUDITOR'))",
                Boolean.class,
                a.tenantId(),
                id));
    Workflow.transition(a, t, input.toStatus(), input.reason(), hasReply, now);
    String old = t.status;
    t.status = input.toStatus();
    if (t.status.equals("RESOLVED")) {
      t.resolvedAt = now;
      // Resolution text is an explicit public reply; customers receive no internal reason fields.
      message(a, t, input.reason().trim(), "PUBLIC", UUID.randomUUID(), now);
      sla.publicReply(t, now);
      t.lastPublicReplyAt = now;
    }
    if (old.equals("RESOLVED") && t.status.equals("OPEN")) t.resolvedAt = null;
    if (t.status.equals("ON_HOLD")) {
      message(a, t, input.reason().trim(), "PRIVATE", UUID.randomUUID(), now);
    }
    if (t.status.equals("CLOSED")) t.closedAt = now;
    sla.transition(t, old, now);
    sla.refreshWarnings(t, now);
    t.touch(now);
    tickets.saveAndFlush(t);
    event(a, t, "ticket.status_changed", "PUBLIC", Map.of("from", old, "to", t.status), now);
    return query.detail(a, id);
  }

  @Transactional
  public View edit(Actor a, UUID id, Edit input, long expected) {
    a.staff();
    a.writer();
    Ticket t = locked(a, id);
    access.write(a, t);
    version(t, expected);
    access.category(a, input.categoryId());
    Instant now = clock.instant();
    if (input.subject() != null) {
      validSubject(input.subject());
      t.subject = input.subject().trim();
    }
    if (input.priority() != null && !input.priority().equals(t.priority)) {
      t.priority = input.priority();
      sla.priority(t, now);
      sla.refreshWarnings(t, now);
    }
    if (input.categoryId() != null) t.categoryId = input.categoryId();
    t.touch(now);
    tickets.saveAndFlush(t);
    event(a, t, "ticket.updated", "PRIVATE", Map.of("priority", t.priority), now);
    return query.detail(a, id);
  }

  private void validSubject(String subject) {
    if (subject.trim().length() < 5 || subject.trim().length() > 200)
      throw new ApiException(
          400, "VALIDATION_FAILED", "Subject must contain 5–200 characters after trimming.");
  }

  private Ticket locked(Actor a, UUID id) {
    Ticket t = tickets.lock(a.tenantId(), id).orElseThrow(ApiException::missing);
    access.read(a, t);
    return t;
  }

  private void version(Ticket t, long expected) {
    if (t.version != expected)
      throw new ApiException(
          409,
          "TICKET_VERSION_CONFLICT",
          "This ticket changed while you were editing. Reload; your draft is preserved.",
          t.version);
  }

  private void message(
      Actor a, Ticket t, String body, String visibility, UUID clientId, Instant now) {
    db.update(
        "INSERT INTO ticket_messages(id,tenant_id,ticket_id,author_membership_id,author_type,visibility,channel,body,client_message_id,created_at) VALUES (?,?,?,?,'HUMAN',?,?,?,?,?)",
        UUID.randomUUID(),
        a.tenantId(),
        t.id,
        a.membershipId(),
        visibility,
        a.customer() ? "PORTAL" : "AGENT",
        body,
        clientId,
        Timestamp.from(now));
  }

  private void event(Actor a, Ticket t, String type, String visibility, Object data, Instant now) {
    UUID eventId = UUID.randomUUID();
    db.update(
        "INSERT INTO ticket_events(id,tenant_id,ticket_id,event_type,actor_id,visibility,correlation_id,data,created_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        eventId,
        a.tenantId(),
        t.id,
        type,
        a.membershipId(),
        visibility,
        UUID.fromString(Problems.correlation()),
        json.write(data),
        Timestamp.from(now));
    audit.record(
        a,
        type,
        t.id,
        Map.of(),
        Map.of("status", t.status, "priority", t.priority, "version", t.version),
        now);
    var payload =
        Map.of(
            "ticketId",
            t.id,
            "version",
            t.version,
            "actorId",
            a.membershipId(),
            "visibility",
            visibility,
            "type",
            type);
    db.update(
        "INSERT INTO outbox_events(id,tenant_id,event_type,aggregate_id,payload,created_at,available_at,dedup_key) VALUES (?,?,?,?,?::jsonb,?,?,?)",
        UUID.randomUUID(),
        a.tenantId(),
        type,
        t.id,
        json.write(payload),
        Timestamp.from(now),
        Timestamp.from(now),
        eventId.toString());
  }
}
