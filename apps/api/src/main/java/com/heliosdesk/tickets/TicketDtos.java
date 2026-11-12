package com.heliosdesk.tickets;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class TicketDtos {
  private TicketDtos() {}

  public record Create(
      @NotBlank @Size(min = 5, max = 200) String subject,
      @NotBlank @Size(max = 20000) String description,
      @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
      @Schema(nullable = true) UUID categoryId,
      @Pattern(regexp = "PORTAL|AGENT") String source,
      UUID requesterMembershipId) {}

  public record Reply(
      @NotBlank @Size(max = 20000) String body,
      @NotBlank @Pattern(regexp = "PUBLIC|PRIVATE") String visibility,
      @NotNull UUID clientMessageId) {}

  public record Transition(
      @NotBlank @Pattern(regexp = "NEW|OPEN|PENDING_CUSTOMER|ON_HOLD|RESOLVED|CLOSED")
          String toStatus,
      @Size(max = 2000) String reason) {}

  public record Assign(
      @Schema(nullable = true) UUID teamId, @Schema(nullable = true) UUID assigneeId) {}

  public record Edit(
      @Size(min = 5, max = 200) String subject,
      @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
      @Schema(nullable = true) UUID categoryId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record View(
      UUID id,
      long ticketNumber,
      String ticketKey,
      String subject,
      String status,
      String priority,
      String source,
      long version,
      UUID requesterMembershipId,
      String requesterName,
      String requesterEmail,
      @Schema(nullable = true) UUID assigneeId,
      @Schema(nullable = true) String assigneeName,
      @Schema(nullable = true) UUID teamId,
      @Schema(nullable = true) String teamName,
      @Schema(nullable = true) UUID categoryId,
      Instant createdAt,
      Instant updatedAt,
      @Schema(nullable = true) Instant resolvedAt,
      @Schema(nullable = true) Instant firstResponseDueAt,
      @Schema(nullable = true) Instant resolutionDueAt,
      boolean canWrite,
      boolean canManage) {}

  public record Message(
      UUID id,
      String body,
      String renderedBody,
      String visibility,
      String authorName,
      String authorRole,
      Instant createdAt) {}

  public record Event(
      UUID id, String eventType, String actorName, Map<String, Object> data, Instant createdAt) {}

  public record Page<T>(
      List<T> items,
      @Schema(nullable = true) String nextCursor,
      @Schema(nullable = true) Long totalCount) {}
}
