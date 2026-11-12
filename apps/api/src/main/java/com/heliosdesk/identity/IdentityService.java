package com.heliosdesk.identity;

import com.heliosdesk.platform.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
  private final JdbcTemplate db;

  public IdentityService(JdbcTemplate db) {
    this.db = db;
  }

  public Actor current(HttpServletRequest request) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser"))
      throw new ApiException(401, "UNAUTHENTICATED", "Sign in to continue.");
    UUID user = UUID.fromString(auth.getName());
    Object active = request.getSession().getAttribute("activeTenant");
    var rows =
        db.query(
            """
   SELECT m.id,m.tenant_id,m.role,u.display_name,u.email,t.name,t.slug,t.timezone
   FROM tenant_memberships m JOIN users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id
   WHERE m.user_id=? AND u.disabled_at IS NULL AND m.status='ACTIVE' AND t.status='ACTIVE'
   AND (?::uuid IS NULL OR m.tenant_id=?::uuid) ORDER BY t.slug
   """,
            (r, n) ->
                new Actor(
                    user,
                    r.getObject("tenant_id", UUID.class),
                    r.getObject("id", UUID.class),
                    r.getString("role"),
                    r.getString("display_name"),
                    r.getString("email"),
                    r.getString("name"),
                    r.getString("slug"),
                    r.getString("timezone")),
            user,
            active,
            active);
    if (rows.isEmpty()) {
      request.getSession().invalidate();
      throw new ApiException(401, "MEMBERSHIP_INACTIVE", "Membership no longer active.");
    }
    Actor a = rows.getFirst();
    if (active == null) request.getSession().setAttribute("activeTenant", a.tenantId());
    return a;
  }

  public List<Map<String, Object>> memberships(UUID user) {
    return db.queryForList(
        """
  SELECT t.id,t.slug,t.name,t.timezone,m.role FROM tenants t JOIN tenant_memberships m ON m.tenant_id=t.id
  JOIN users u ON u.id=m.user_id WHERE m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE' AND u.disabled_at IS NULL ORDER BY t.name
  """,
        user);
  }

  public void switchTenant(Actor a, String slug, HttpServletRequest req) {
    var ids =
        db.queryForList(
            "SELECT t.id FROM tenants t JOIN tenant_memberships m ON m.tenant_id=t.id WHERE t.slug=? AND m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'",
            UUID.class,
            slug,
            a.userId());
    if (ids.isEmpty()) throw ApiException.missing();
    req.getSession().setAttribute("activeTenant", ids.getFirst());
  }
}
