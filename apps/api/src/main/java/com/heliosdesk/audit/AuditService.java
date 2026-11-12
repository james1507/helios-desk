package com.heliosdesk.audit;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate db;
  private final Json json;

  public AuditService(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public void record(
      Actor a, String action, UUID resource, Object before, Object after, Instant now) {
    db.update(
        "INSERT INTO audit_logs(id,tenant_id,actor_membership_id,action,resource_type,resource_id,before_safe,after_safe,correlation_id,created_at) VALUES (?,?,?,?,'TICKET',?,?::jsonb,?::jsonb,?,?)",
        UUID.randomUUID(),
        a.tenantId(),
        a.membershipId(),
        action,
        resource,
        json.write(before),
        json.write(after),
        UUID.fromString(Problems.correlation()),
        java.sql.Timestamp.from(now));
  }

  public List<Map<String, Object>> list(Actor a) {
    if (!a.manager() && !a.auditor()) throw ApiException.forbidden();
    return db.queryForList(
        "SELECT l.id,l.action,l.resource_id AS \"resourceId\",l.after_safe AS \"after\",l.correlation_id AS \"correlationId\",l.created_at AS \"createdAt\",coalesce(u.display_name,'Helios System') AS \"actorName\" FROM audit_logs l LEFT JOIN tenant_memberships m ON m.id=l.actor_membership_id AND m.tenant_id=l.tenant_id LEFT JOIN users u ON u.id=m.user_id WHERE l.tenant_id=? ORDER BY l.created_at DESC,l.id DESC LIMIT 100",
        a.tenantId());
  }
}
