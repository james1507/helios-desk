package com.heliosdesk.tickets;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TicketAccess {
  private final JdbcTemplate db;

  public TicketAccess(JdbcTemplate db) {
    this.db = db;
  }

  public void read(Actor a, Ticket t) {
    if (!t.tenantId.equals(a.tenantId())) throw ApiException.missing();
    if (a.customer() && !t.requesterMembershipId.equals(a.membershipId()))
      throw ApiException.missing();
    if (a.role().equals("AGENT")
        && !a.membershipId().equals(t.assigneeMembershipId)
        && t.teamId != null
        && !inTeam(a, t.teamId)) throw ApiException.missing();
  }

  public boolean inTeam(Actor a, UUID team) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM team_members tm JOIN teams t ON t.id=tm.team_id AND t.tenant_id=tm.tenant_id WHERE tm.tenant_id=? AND tm.team_id=? AND tm.membership_id=? AND t.is_active)",
            Boolean.class,
            a.tenantId(),
            team,
            a.membershipId()));
  }

  public void write(Actor a, Ticket t) {
    read(a, t);
    a.writer();
    if (t.status.equals("CLOSED"))
      throw new ApiException(
          422, "TICKET_CLOSED", "Closed tickets are read-only. Create a follow-up ticket.");
  }

  public void assignee(Actor a, UUID team, UUID assignee) {
    if (team != null
        && !Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM teams WHERE tenant_id=? AND id=? AND is_active)",
                Boolean.class,
                a.tenantId(),
                team))) throw ApiException.missing();
    if (assignee != null) {
      boolean valid =
          Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM tenant_memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.id=? AND m.status='ACTIVE' AND u.disabled_at IS NULL AND m.role IN ('OWNER','ADMIN','MANAGER','AGENT'))",
                  Boolean.class,
                  a.tenantId(),
                  assignee));
      if (!valid) throw ApiException.missing();
      if (team != null
          && !Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM team_members WHERE tenant_id=? AND team_id=? AND membership_id=?)",
                  Boolean.class,
                  a.tenantId(),
                  team,
                  assignee)))
        throw new ApiException(422, "ASSIGNEE_NOT_IN_TEAM", "Assignee must belong to this team.");
    }
  }

  public void category(Actor a, UUID id) {
    if (id != null
        && !Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ticket_categories WHERE tenant_id=? AND id=? AND is_active)",
                Boolean.class,
                a.tenantId(),
                id))) throw ApiException.missing();
  }
}
