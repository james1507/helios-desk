package com.heliosdesk.tickets;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {
  @Id public UUID id;

  @Column(nullable = false)
  public UUID tenantId;

  public long ticketNumber;
  public String subject;
  public String status;
  public String priority;
  public String source;
  public UUID requesterMembershipId;
  public UUID assigneeMembershipId;
  public UUID teamId;
  public UUID categoryId;
  @Version public Long version;
  public Instant createdAt;
  public Instant updatedAt;
  public Instant lastActivityAt;
  public Instant resolvedAt;
  public Instant closedAt;
  public Instant lastPublicReplyAt;

  protected Ticket() {}

  public static Ticket create(
      UUID tenant,
      long number,
      String subject,
      String priority,
      String source,
      UUID requester,
      UUID category,
      Instant now) {
    var t = new Ticket();
    t.id = UUID.randomUUID();
    t.tenantId = tenant;
    t.ticketNumber = number;
    t.subject = subject;
    t.priority = priority;
    t.source = source;
    t.requesterMembershipId = requester;
    t.categoryId = category;
    t.status = "NEW";
    t.createdAt = now;
    t.updatedAt = now;
    t.lastActivityAt = now;
    return t;
  }

  public void touch(Instant now) {
    updatedAt = now;
    lastActivityAt = now;
  }
}
