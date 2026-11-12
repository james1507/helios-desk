package com.heliosdesk.tickets;

import static org.assertj.core.api.Assertions.*;

import com.heliosdesk.identity.Actor;
import com.heliosdesk.platform.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowTest {
  static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

  static Actor actor(String role) {
    return new Actor(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        role,
        "Test",
        "test@example.test",
        "Test",
        "test",
        "UTC");
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transitions() {
    var valid =
        Set.of(
            "NEW:OPEN",
            "NEW:RESOLVED",
            "OPEN:PENDING_CUSTOMER",
            "OPEN:ON_HOLD",
            "OPEN:RESOLVED",
            "PENDING_CUSTOMER:OPEN",
            "PENDING_CUSTOMER:RESOLVED",
            "ON_HOLD:OPEN",
            "ON_HOLD:RESOLVED",
            "RESOLVED:OPEN",
            "RESOLVED:CLOSED");
    return Workflow.STATUSES.stream()
        .flatMap(
            from ->
                Workflow.STATUSES.stream()
                    .map(
                        to ->
                            org.junit.jupiter.params.provider.Arguments.of(
                                from, to, valid.contains(from + ":" + to))));
  }

  @ParameterizedTest
  @MethodSource("transitions")
  void everyEdge(String from, String to, boolean allowed) {
    var a = actor("MANAGER");
    var t =
        Ticket.create(
            a.tenantId(), 1, "Test ticket", "NORMAL", "PORTAL", a.membershipId(), null, NOW);
    t.status = from;
    t.resolvedAt = NOW.minusSeconds(60);
    if (allowed)
      assertThatCode(() -> Workflow.transition(a, t, to, "Resolution text", true, NOW))
          .doesNotThrowAnyException();
    else
      assertThatThrownBy(() -> Workflow.transition(a, t, to, "Reason", true, NOW))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code).isEqualTo("INVALID_TICKET_TRANSITION"));
  }

  @Test
  void customerAuditorAndCloseGuards() {
    var t =
        Ticket.create(
            UUID.randomUUID(), 1, "Test ticket", "NORMAL", "PORTAL", UUID.randomUUID(), null, NOW);
    assertThatThrownBy(() -> Workflow.transition(actor("REQUESTER"), t, "OPEN", null, true, NOW))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Workflow.transition(actor("AUDITOR"), t, "OPEN", null, true, NOW))
        .isInstanceOf(ApiException.class);
    t.status = "RESOLVED";
    t.resolvedAt = NOW;
    assertThatThrownBy(() -> Workflow.transition(actor("AGENT"), t, "CLOSED", null, true, NOW))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void reasonAndPublicReplyRequired() {
    var a = actor("AGENT");
    var t =
        Ticket.create(
            a.tenantId(), 1, "Test ticket", "NORMAL", "PORTAL", a.membershipId(), null, NOW);
    t.status = "OPEN";
    assertThatThrownBy(() -> Workflow.transition(a, t, "RESOLVED", null, true, NOW))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Workflow.transition(a, t, "PENDING_CUSTOMER", null, false, NOW))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void reopenBoundary() {
    var a = actor("AGENT");
    var t =
        Ticket.create(
            a.tenantId(), 1, "Test ticket", "NORMAL", "PORTAL", a.membershipId(), null, NOW);
    t.resolvedAt = NOW.minus(Duration.ofDays(7));
    assertThatThrownBy(() -> Workflow.reopen(t, NOW)).isInstanceOf(ApiException.class);
  }
}
