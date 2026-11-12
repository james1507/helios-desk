package com.heliosdesk.servicelevels;

import com.heliosdesk.identity.*;
import com.heliosdesk.platform.*;
import com.heliosdesk.tickets.*;
import com.heliosdesk.tickets.TicketDtos.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ServiceLevelController {
  private final IdentityService identity;
  private final SlaService sla;
  private final JdbcTemplate db;
  private final TicketQuery tickets;
  private final Json json;
  private final Policies policies;

  public ServiceLevelController(
      IdentityService identity,
      SlaService sla,
      JdbcTemplate db,
      TicketQuery tickets,
      Json json,
      Policies policies) {
    this.identity = identity;
    this.sla = sla;
    this.db = db;
    this.tickets = tickets;
    this.json = json;
    this.policies = policies;
  }

  public record Update(
      long expectedVersion,
      @NotNull BusinessCalendar calendar,
      @NotNull Map<String, SlaService.Target> targets,
      boolean pauseOnPending,
      @Min(1) @Max(99) int warningRatio) {}

  public record PolicyView(UUID id, String name, long version, SlaService.Policy policy) {}

  @GetMapping("/sla/policies")
  Page<PolicyView> policies(HttpServletRequest req) {
    Actor a = identity.current(req);
    a.staff();
    var rows =
        db.query(
            "SELECT id,name,version FROM sla_policies WHERE tenant_id=? ORDER BY name",
            (r, n) ->
                new PolicyView(
                    r.getObject("id", UUID.class),
                    r.getString("name"),
                    r.getLong("version"),
                    sla.policy(a.tenantId())),
            a.tenantId());
    return new Page<>(rows, null, (long) rows.size());
  }

  @PatchMapping("/sla/policies/{id}")
  PolicyView update(
      @PathVariable UUID id, @Valid @RequestBody Update input, HttpServletRequest req) {
    return policies.update(identity.current(req), id, input);
  }

  @GetMapping("/tickets/{id}/sla")
  Page<Map<String, Object>> cycles(@PathVariable UUID id, HttpServletRequest req) {
    Actor a = identity.current(req);
    a.staff();
    tickets.require(a, id);
    return new Page<>(
        db.queryForList(
            "SELECT cycle_number AS \"cycleNumber\",cycle_started_at AS \"startedAt\",first_response_due_at AS \"firstResponseDueAt\",first_response_at AS \"firstResponseAt\",resolution_due_at AS \"resolutionDueAt\",completed_at AS \"completedAt\",paused_at AS \"pausedAt\",consumed_seconds AS \"consumedSeconds\" FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=? ORDER BY cycle_number",
            a.tenantId(),
            id),
        null,
        null);
  }

  @Service
  public static class Policies {
    private final JdbcTemplate db;
    private final Json json;

    public Policies(JdbcTemplate db, Json json) {
      this.db = db;
      this.json = json;
    }

    @Transactional
    public PolicyView update(Actor a, UUID id, Update input) {
      a.manage();
      if (!input.targets().keySet().equals(Workflow.PRIORITIES))
        throw new ApiException(400, "INVALID_POLICY", "Provide targets for all four priorities.");
      for (var t : input.targets().values())
        if (t == null
            || t.responseMinutes() < 1
            || t.resolutionMinutes() < 1
            || t.responseMinutes() > 525600
            || t.resolutionMinutes() > 525600)
          throw new ApiException(
              400, "INVALID_POLICY", "Targets must be 1–525600 business minutes.");
      int changed =
          db.update(
              "UPDATE sla_policies SET calendar=?::jsonb,targets=?::jsonb,pause_on_pending=?,warning_ratio=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?",
              json.write(input.calendar()),
              json.write(input.targets()),
              input.pauseOnPending(),
              input.warningRatio(),
              a.tenantId(),
              id,
              input.expectedVersion());
      if (changed == 0) {
        if (!Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM sla_policies WHERE tenant_id=? AND id=?)",
                Boolean.class,
                a.tenantId(),
                id))) throw ApiException.missing();
        throw new ApiException(
            409, "POLICY_VERSION_CONFLICT", "Policy changed. Reload before saving.");
      }
      db.update(
          "INSERT INTO audit_logs(id,tenant_id,actor_membership_id,action,resource_type,resource_id,after_safe,correlation_id,created_at) VALUES (?,?,?,'sla.policy_updated','SLA_POLICY',?,?::jsonb,?,now())",
          UUID.randomUUID(),
          a.tenantId(),
          a.membershipId(),
          id,
          json.write(Map.of("version", input.expectedVersion() + 1)),
          UUID.fromString(Problems.correlation()));
      return new PolicyView(
          id,
          "Standard support",
          input.expectedVersion() + 1,
          new SlaService.Policy(
              input.calendar(), input.targets(), input.pauseOnPending(), input.warningRatio()));
    }
  }
}
