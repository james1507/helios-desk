package com.heliosdesk.tenancy;

import com.heliosdesk.audit.AuditService;
import com.heliosdesk.identity.*;
import com.heliosdesk.tickets.TicketDtos.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DirectoryController {
  private final IdentityService identity;
  private final JdbcTemplate db;
  private final AuditService audit;
  private final TeamService teams;

  public DirectoryController(
      IdentityService identity, JdbcTemplate db, AuditService audit, TeamService teams) {
    this.identity = identity;
    this.db = db;
    this.audit = audit;
    this.teams = teams;
  }

  @GetMapping("/teams")
  Page<TeamService.TeamView> teams(
      @RequestParam(defaultValue = "false") boolean includeInactive, HttpServletRequest req) {
    return teams.list(identity.current(req), includeInactive);
  }

  @PostMapping("/teams")
  TeamService.TeamView createTeam(
      @Valid @RequestBody TeamService.Input input, HttpServletRequest req) {
    return teams.save(identity.current(req), null, input);
  }

  @PatchMapping("/teams/{id}")
  TeamService.TeamView updateTeam(
      @PathVariable UUID id, @Valid @RequestBody TeamService.Input input, HttpServletRequest req) {
    return teams.save(identity.current(req), id, input);
  }

  @GetMapping("/tenant/members")
  Page<Map<String, Object>> members(HttpServletRequest req) {
    Actor a = identity.current(req);
    a.staff();
    var rows =
        db.queryForList(
            "SELECT m.id,m.role,u.display_name AS \"name\",u.email FROM tenant_memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.status='ACTIVE' AND u.disabled_at IS NULL ORDER BY u.display_name",
            a.tenantId());
    return new Page<>(rows, null, (long) rows.size());
  }

  @GetMapping("/categories")
  Page<Map<String, Object>> categories(HttpServletRequest req) {
    Actor a = identity.current(req);
    var rows =
        db.queryForList(
            "SELECT id,name FROM ticket_categories WHERE tenant_id=? AND is_active ORDER BY name",
            a.tenantId());
    return new Page<>(rows, null, (long) rows.size());
  }

  @GetMapping("/audit")
  Page<Map<String, Object>> audit(HttpServletRequest req) {
    var rows = audit.list(identity.current(req));
    return new Page<>(rows, null, null);
  }
}
