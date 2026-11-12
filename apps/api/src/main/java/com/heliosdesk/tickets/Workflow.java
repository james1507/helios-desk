package com.heliosdesk.tickets;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.ApiException;
import java.time.*;
import java.util.*;

public final class Workflow {
  private Workflow() {}

  public static final Set<String> STATUSES =
      Set.of("NEW", "OPEN", "PENDING_CUSTOMER", "ON_HOLD", "RESOLVED", "CLOSED");
  public static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
  private static final Map<String, Set<String>> EDGES =
      Map.of(
          "NEW",
          Set.of("OPEN", "RESOLVED"),
          "OPEN",
          Set.of("PENDING_CUSTOMER", "ON_HOLD", "RESOLVED"),
          "PENDING_CUSTOMER",
          Set.of("OPEN", "RESOLVED"),
          "ON_HOLD",
          Set.of("OPEN", "RESOLVED"),
          "RESOLVED",
          Set.of("OPEN", "CLOSED"),
          "CLOSED",
          Set.of());

  public static void transition(
      Actor a, Ticket t, String to, String reason, boolean hasPublicReply, Instant now) {
    a.staff();
    a.writer();
    if (!EDGES.getOrDefault(t.status, Set.of()).contains(to)) throw invalid();
    if (to.equals("CLOSED") && !a.manager()) throw ApiException.forbidden();
    if ((to.equals("RESOLVED") || to.equals("ON_HOLD")) && (reason == null || reason.isBlank()))
      throw new ApiException(422, "REASON_REQUIRED", "Provide a resolution or hold reason.");
    if (to.equals("PENDING_CUSTOMER") && !hasPublicReply)
      throw new ApiException(
          422, "PUBLIC_REPLY_REQUIRED", "Send a public reply before waiting for customer.");
    if (t.status.equals("RESOLVED") && to.equals("OPEN")) reopen(t, now);
  }

  public static void reopen(Ticket t, Instant now) {
    if (t.resolvedAt == null || !now.isBefore(t.resolvedAt.plus(Duration.ofDays(7))))
      throw new ApiException(
          422, "REOPEN_WINDOW_EXPIRED", "Create a follow-up ticket; the reopen window has ended.");
  }

  public static ApiException invalid() {
    return new ApiException(
        422, "INVALID_TICKET_TRANSITION", "This status transition is not allowed.");
  }
}
