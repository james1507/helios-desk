package com.heliosdesk.platform;

import com.heliosdesk.notifications.NotificationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {
  public record Payload(
      UUID ticketId, long version, UUID actorId, String visibility, String type) {}

  public record Job(UUID id, UUID tenant, UUID token, int attempts, String payload) {}

  private final JdbcTemplate db;
  private final Json json;
  private final NotificationService notifications;
  private final TransactionTemplate transactions;
  private final boolean enabled;

  public OutboxWorker(
      JdbcTemplate db,
      Json json,
      NotificationService notifications,
      PlatformTransactionManager manager,
      @Value("${helios.workers-enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.notifications = notifications;
    this.transactions = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  public List<Job> claim(int limit) {
    UUID token = UUID.randomUUID();
    return transactions.execute(
        status ->
            db.query(
                """
   UPDATE outbox_events SET status='PROCESSING',claimed_until=now()+interval '30 seconds',claim_token=?,attempts=attempts+1
   WHERE id IN (SELECT id FROM outbox_events WHERE ((status IN ('PENDING','RETRY') AND available_at<=now()) OR (status='PROCESSING' AND claimed_until<now())) AND attempts<8 ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT ?)
   RETURNING id,tenant_id,attempts,payload::text
   """,
                (r, n) ->
                    new Job(
                        r.getObject("id", UUID.class),
                        r.getObject("tenant_id", UUID.class),
                        token,
                        r.getInt("attempts"),
                        r.getString("payload")),
                token,
                limit));
  }

  public void process(Job job) {
    try {
      Payload p = json.read(job.payload(), Payload.class);
      notifications.deliver(
          job.id(), job.tenant(), p.ticketId(), p.type(), p.visibility(), p.version(), p.actorId());
      db.update(
          "UPDATE outbox_events SET status='PROCESSED',claimed_until=NULL,claim_token=NULL,last_error=NULL WHERE tenant_id=? AND id=? AND claim_token=?",
          job.tenant(),
          job.id(),
          job.token());
    } catch (RuntimeException e) {
      long delay =
          Math.min(3600, 1L << Math.min(job.attempts(), 11))
              + ThreadLocalRandom.current().nextLong(5);
      db.update(
          "UPDATE outbox_events SET status=?,available_at=now()+(? * interval '1 second'),claimed_until=NULL,claim_token=NULL,last_error=? WHERE tenant_id=? AND id=? AND claim_token=?",
          job.attempts() >= 8 ? "DEAD_LETTER" : "RETRY",
          delay,
          e.getClass().getSimpleName(),
          job.tenant(),
          job.id(),
          job.token());
    }
  }

  @Scheduled(fixedDelayString = "${helios.outbox-delay-ms:1000}")
  public void tick() {
    if (!enabled) return;
    db.update(
        "UPDATE outbox_events SET status='DEAD_LETTER',last_error='Lease expired at attempt limit',claimed_until=NULL WHERE status='PROCESSING' AND claimed_until<now() AND attempts>=8");
    for (Job job : claim(30)) process(job);
  }
}
