package com.heliosdesk.identity;

import com.heliosdesk.platform.ApiException;
import java.util.UUID;

public record Actor(
    UUID userId,
    UUID tenantId,
    UUID membershipId,
    String role,
    String name,
    String email,
    String tenantName,
    String tenantSlug,
    String timezone) {
  public boolean customer() {
    return role.equals("REQUESTER");
  }

  public boolean auditor() {
    return role.equals("AUDITOR");
  }

  public boolean manager() {
    return role.equals("OWNER") || role.equals("ADMIN") || role.equals("MANAGER");
  }

  public boolean privateNotes() {
    return !customer() && !auditor();
  }

  public void writer() {
    if (auditor()) throw ApiException.forbidden();
  }

  public void staff() {
    if (customer()) throw ApiException.forbidden();
  }

  public void manage() {
    if (!manager()) throw ApiException.forbidden();
  }
}
