package com.heliosdesk.tenancy;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.*;
import com.heliosdesk.tickets.TicketDtos.Page;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {
  public record TeamView(
      UUID id, String name, boolean isActive, long version, List<UUID> memberIds) {}

  public record Input(
      @NotBlank @Size(max = 100) String name,
      boolean isActive,
      @Min(0) long expectedVersion,
      @NotNull @Size(max = 100) Set<@NotNull UUID> memberIds) {}

  private final JdbcTemplate db;
  private final Json json;

  public TeamService(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public Page<TeamView> list(Actor a, boolean inactive) {
    a.staff();
    if (inactive && !a.manager() && !a.auditor()) throw ApiException.forbidden();
    var rows =
        db.query(
            "SELECT t.id,t.name,t.is_active,t.version,coalesce(array_agg(m.membership_id) FILTER(WHERE m.membership_id IS NOT NULL),'{}'::uuid[]) members FROM teams t LEFT JOIN team_members m ON m.tenant_id=t.tenant_id AND m.team_id=t.id WHERE t.tenant_id=? AND (? OR t.is_active) GROUP BY t.id ORDER BY t.name LIMIT 100",
            (r, n) ->
                new TeamView(
                    r.getObject("id", UUID.class),
                    r.getString("name"),
                    r.getBoolean("is_active"),
                    r.getLong("version"),
                    Arrays.asList((UUID[]) r.getArray("members").getArray())),
            a.tenantId(),
            inactive);
    return new Page<>(rows, null, (long) rows.size());
  }

  @Transactional
  public TeamView save(Actor a, UUID id, Input input) {
    a.manage();
    String name = input.name().trim();
    if (name.isEmpty()) throw new ApiException(400, "INVALID_TEAM", "Team name is required.");
    for (UUID member : input.memberIds()) {
      if (!Boolean.TRUE.equals(
          db.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM tenant_memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.id=? AND m.status='ACTIVE' AND u.disabled_at IS NULL AND m.role IN ('OWNER','ADMIN','MANAGER','AGENT'))",
              Boolean.class,
              a.tenantId(),
              member))) throw ApiException.missing();
    }
    boolean creating = id == null;
    db.queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE", UUID.class, a.tenantId());
    if (!creating
        && !Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM teams WHERE tenant_id=? AND id=?)",
                Boolean.class,
                a.tenantId(),
                id))) throw ApiException.missing();
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM teams WHERE tenant_id=? AND name=? AND (?::uuid IS NULL OR id<>?))",
            Boolean.class,
            a.tenantId(),
            name,
            id,
            id))) throw new ApiException(409, "TEAM_NAME_CONFLICT", "Team name is already in use.");
    // ponytail: cap at 100 teams; add cursor pagination before raising this limit.
    if (creating
        && db.queryForObject(
                "SELECT count(*) FROM teams WHERE tenant_id=?", Long.class, a.tenantId())
            >= 100)
      throw new ApiException(422, "TEAM_LIMIT", "This workspace supports up to 100 teams.");
    if (creating) {
      id = UUID.randomUUID();
      db.update(
          "INSERT INTO teams(id,tenant_id,name,is_active) VALUES (?,?,?,?)",
          id,
          a.tenantId(),
          name,
          input.isActive());
    } else {
      int changed =
          db.update(
              "UPDATE teams SET name=?,is_active=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?",
              name,
              input.isActive(),
              a.tenantId(),
              id,
              input.expectedVersion());
      if (changed == 0) {
        if (!Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM teams WHERE tenant_id=? AND id=?)",
                Boolean.class,
                a.tenantId(),
                id))) throw ApiException.missing();
        throw new ApiException(409, "TEAM_VERSION_CONFLICT", "Team changed. Reload before saving.");
      }
      db.update("DELETE FROM team_members WHERE tenant_id=? AND team_id=?", a.tenantId(), id);
    }
    for (UUID member : input.memberIds())
      db.update(
          "INSERT INTO team_members(tenant_id,team_id,membership_id) VALUES (?,?,?)",
          a.tenantId(),
          id,
          member);
    long version = creating ? 0 : input.expectedVersion() + 1;
    db.update(
        "INSERT INTO audit_logs(id,tenant_id,actor_membership_id,action,resource_type,resource_id,after_safe,correlation_id,created_at) VALUES (?,?,?,'team.saved','TEAM',?,?::jsonb,?,now())",
        UUID.randomUUID(),
        a.tenantId(),
        a.membershipId(),
        id,
        json.write(
            Map.of(
                "version",
                version,
                "active",
                input.isActive(),
                "memberCount",
                input.memberIds().size())),
        UUID.fromString(Problems.correlation()));
    return new TeamView(id, name, input.isActive(), version, List.copyOf(input.memberIds()));
  }
}
