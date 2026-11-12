package com.heliosdesk;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.heliosdesk.identity.*;
import com.heliosdesk.platform.*;
import com.heliosdesk.tickets.*;
import com.heliosdesk.tickets.TicketDtos.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "helios.demo-password=HeliosDemo!2026",
      "server.servlet.session.cookie.secure=false",
      "management.server.port=0",
      "helios.workers-enabled=false"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Testcontainers
class TicketIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.7-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired TicketService service;
  @Autowired TicketQuery query;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired Json json;

  @Autowired com.heliosdesk.platform.OutboxWorker worker;
  @Autowired com.heliosdesk.servicelevels.SlaScheduler scheduler;
  @Autowired com.heliosdesk.notifications.NotificationService notifications;

  Actor actor(String account, String tenant, String role) {
    String email = account + "@" + tenant + ".test";
    return new Actor(
        DemoSeed.id(email),
        DemoSeed.id(tenant),
        DemoSeed.id(email + "member"),
        role,
        account,
        email,
        tenant,
        tenant,
        "UTC");
  }

  Actor customer() {
    return actor("customer1", "northstar", "REQUESTER");
  }

  Actor agent() {
    return actor("agent1", "northstar", "AGENT");
  }

  Actor manager() {
    return actor("manager", "northstar", "MANAGER");
  }

  Create input(String subject) {
    return new Create(subject, "Detailed customer description.", "NORMAL", null, "PORTAL", null);
  }

  View create() {
    return service.create(
        customer(), input("Integration ticket " + UUID.randomUUID()), UUID.randomUUID().toString());
  }

  MockHttpSession session(Actor a) {
    var s = new MockHttpSession();
    s.setAttribute("activeTenant", a.tenantId());
    return s;
  }

  long count(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void completeLifecycleAndPrivateProjection() throws Exception {
    var t = create();
    t = service.claim(agent(), t.id(), t.version(), UUID.randomUUID().toString());
    String secret = "private-sentinel-" + UUID.randomUUID();
    t =
        service.reply(
            agent(),
            t.id(),
            new Reply(secret, "PRIVATE", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    assertThat(query.messages(customer(), t.id(), null, 100).items())
        .noneMatch(m -> m.body().contains(secret));
    assertThat(query.timeline(customer(), t.id(), null, 100).items())
        .noneMatch(e -> e.eventType().equals("note.added"));
    assertThat(
            query
                .list(customer(), secret, null, null, null, null, null, null, null, 25)
                .totalCount())
        .isZero();
    mvc.perform(
            get("/api/v1/tickets/" + t.id())
                .with(user(customer().userId().toString()))
                .session(session(customer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").doesNotExist())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
    t =
        service.reply(
            agent(),
            t.id(),
            new Reply("Public support reply", "PUBLIC", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    t = service.transition(agent(), t.id(), new Transition("PENDING_CUSTOMER", null), t.version());
    assertThat(
            db.queryForObject(
                "SELECT paused_at IS NOT NULL FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=? AND cycle_number=1",
                Boolean.class,
                customer().tenantId(),
                t.id()))
        .isTrue();
    t =
        service.reply(
            customer(),
            t.id(),
            new Reply("Customer response", "PUBLIC", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    assertThat(t.status()).isEqualTo("OPEN");
    t =
        service.transition(
            agent(), t.id(), new Transition("RESOLVED", "Fixed and verified."), t.version());
    assertThat(t.status()).isEqualTo("RESOLVED");
    t =
        service.reply(
            customer(),
            t.id(),
            new Reply("Issue returned", "PUBLIC", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    assertThat(t.status()).isEqualTo("OPEN");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=?",
                Long.class,
                customer().tenantId(),
                t.id()))
        .isEqualTo(2);
    t =
        service.transition(
            agent(), t.id(), new Transition("RESOLVED", "Fixed once more."), t.version());
    t = service.transition(manager(), t.id(), new Transition("CLOSED", null), t.version());
    final View closed = t;
    assertThatThrownBy(
            () ->
                service.reply(
                    customer(),
                    closed.id(),
                    new Reply("Cannot reopen", "PUBLIC", UUID.randomUUID()),
                    closed.version(),
                    UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("TICKET_CLOSED"));
  }

  @Test
  void idempotencyAndMismatch() {
    var input = input("Retry-safe ticket");
    String key = UUID.randomUUID().toString();
    long before = count("tickets");
    var one = service.create(customer(), input, key);
    var two = service.create(customer(), input, key);
    assertThat(two).isEqualTo(one);
    assertThat(count("tickets")).isEqualTo(before + 1);
    assertThatThrownBy(() -> service.create(customer(), input("Altered payload"), key))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void concurrentDuplicateRequests() throws Exception {
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<UUID>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () ->
                    service.create(customer(), input("Concurrent duplicate creation"), key).id()));
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(30, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
  }

  @Test
  void fiftyConcurrentClaimsOneWinner() throws Exception {
    var t = create();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<Future<String>>();
      for (int i = 0; i < 50; i++) {
        Actor a = i % 2 == 0 ? agent() : actor("agent2", "northstar", "AGENT");
        results.add(
            pool.submit(
                () -> {
                  try {
                    service.claim(a, t.id(), t.version(), UUID.randomUUID().toString());
                    return "OK";
                  } catch (ApiException e) {
                    return e.code;
                  }
                }));
      }
      var codes = new ArrayList<String>();
      for (var f : results) codes.add(f.get(45, TimeUnit.SECONDS));
      assertThat(codes).filteredOn("OK"::equals).hasSize(1);
      assertThat(codes).filteredOn("TICKET_ALREADY_CLAIMED"::equals).hasSize(49);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ticket_events WHERE tenant_id=? AND ticket_id=? AND event_type='ticket.assigned'",
                Long.class,
                agent().tenantId(),
                t.id()))
        .isEqualTo(1);
  }

  @Test
  void uniqueNumbersUnderConcurrentCreate() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Long>>();
      for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> create().ticketNumber()));
      var ids = new HashSet<Long>();
      for (var f : futures) ids.add(f.get(30, TimeUnit.SECONDS));
      assertThat(ids).hasSize(20);
    }
  }

  @Test
  void staleEditsAndReplyReplay() {
    var t = create();
    var updated = service.edit(agent(), t.id(), new Edit(null, "HIGH", null), t.version());
    assertThat(updated.priority()).isEqualTo("HIGH");
    assertThatThrownBy(
            () -> service.edit(agent(), t.id(), new Edit(null, "URGENT", null), t.version()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("TICKET_VERSION_CONFLICT"));
    String key = UUID.randomUUID().toString();
    var reply = new Reply("Retry response", "PUBLIC", UUID.randomUUID());
    var first = service.reply(agent(), t.id(), reply, updated.version(), key);
    assertThat(service.reply(agent(), t.id(), reply, updated.version(), key)).isEqualTo(first);
  }

  @Test
  void crossTenantAndRequesterIsolation() throws Exception {
    var t = create();
    var other = actor("customer2", "northstar", "REQUESTER");
    var blue = actor("owner", "bluepeak", "OWNER");
    assertThatThrownBy(() -> query.detail(other, t.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    assertThatThrownBy(() -> query.detail(blue, t.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    mvc.perform(
            get("/api/v1/tickets/" + t.id())
                .with(user(blue.userId().toString()))
                .session(session(blue)))
        .andExpect(status().isNotFound());
    assertThatThrownBy(
            () ->
                service.assign(
                    manager(), t.id(), new Assign(null, blue.membershipId()), t.version()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void forbiddenRolesAndCsrf() throws Exception {
    var t = create();
    mvc.perform(
            post("/api/v1/tickets/" + t.id() + "/claim")
                .with(user(customer().userId().toString()))
                .session(session(customer()))
                .with(csrf())
                .header("If-Match", "\"v0\"")
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{\"email\":\"customer1@northstar.test\",\"password\":\"HeliosDemo!2026\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var auditor = actor("auditor", "northstar", "AUDITOR");
    assertThatThrownBy(
            () ->
                service.create(
                    auditor, input("Auditor cannot write"), UUID.randomUUID().toString()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rollbackIncludesCounterMessagesAndOutbox() {
    long tickets = count("tickets"),
        messages = count("ticket_messages"),
        outbox = count("outbox_events");
    long counter =
        db.queryForObject(
            "SELECT next_value FROM ticket_counters WHERE tenant_id=?",
            Long.class,
            customer().tenantId());
    db.execute(
        "CREATE FUNCTION fail_test_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected outbox failure'; END; $$");
    db.execute(
        "CREATE TRIGGER fail_test_outbox BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_test_outbox()");
    try {
      assertThatThrownBy(this::create).isInstanceOf(RuntimeException.class);
    } finally {
      db.execute("DROP TRIGGER fail_test_outbox ON outbox_events");
      db.execute("DROP FUNCTION fail_test_outbox()");
    }
    assertThat(count("tickets")).isEqualTo(tickets);
    assertThat(count("ticket_messages")).isEqualTo(messages);
    assertThat(count("outbox_events")).isEqualTo(outbox);
    assertThat(
            db.queryForObject(
                "SELECT next_value FROM ticket_counters WHERE tenant_id=?",
                Long.class,
                customer().tenantId()))
        .isEqualTo(counter);
  }

  @Test
  void compositeForeignKeyRejectsCrossTenant() {
    var t = create();
    UUID blue = actor("owner", "bluepeak", "OWNER").membershipId();
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE tickets SET assignee_membership_id=? WHERE tenant_id=? AND id=?",
                    blue,
                    customer().tenantId(),
                    t.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void historyIsAppendOnly() {
    var t = create();
    assertThatThrownBy(
            () ->
                db.update(
                    "DELETE FROM ticket_messages WHERE tenant_id=? AND ticket_id=?",
                    customer().tenantId(),
                    t.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void deactivationRevokesAnExistingSession() throws Exception {
    var a = customer();
    db.update(
        "UPDATE tenant_memberships SET status='DISABLED' WHERE tenant_id=? AND id=?",
        a.tenantId(),
        a.membershipId());
    try {
      mvc.perform(get("/api/v1/auth/me").with(user(a.userId().toString())).session(session(a)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("MEMBERSHIP_INACTIVE"));
    } finally {
      db.update(
          "UPDATE tenant_memberships SET status='ACTIVE' WHERE tenant_id=? AND id=?",
          a.tenantId(),
          a.membershipId());
    }
  }

  @Test
  void tenantSwitchChecksMembershipAndChangesReadScope() throws Exception {
    var owner = actor("owner", "northstar", "OWNER");
    var session = session(owner);
    var t = create();
    mvc.perform(
            post("/api/v1/tenants/switch")
                .with(user(owner.userId().toString()))
                .with(csrf())
                .session(session)
                .contentType("application/json")
                .content("{\"slug\":\"bluepeak\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actor.tenantSlug").value("bluepeak"));
    mvc.perform(
            get("/api/v1/tickets/" + t.id()).with(user(owner.userId().toString())).session(session))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/tenants/switch")
                .with(user(customer().userId().toString()))
                .with(csrf())
                .session(session(customer()))
                .contentType("application/json")
                .content("{\"slug\":\"bluepeak\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void lateResponseIsBreachedEvenWhenSchedulerWasDown() throws Exception {
    var t = create();
    var now = java.time.Instant.now();
    db.update(
        "UPDATE ticket_sla_cycles SET first_response_due_at=?,first_response_at=? WHERE tenant_id=? AND ticket_id=?",
        java.sql.Timestamp.from(now.minusSeconds(60)),
        java.sql.Timestamp.from(now),
        customer().tenantId(),
        t.id());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> scheduler.evaluate(now)));
      for (var f : futures) f.get(30, TimeUnit.SECONDS);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sla_breaches WHERE tenant_id=? AND ticket_id=? AND metric='FIRST_RESPONSE'",
                Long.class,
                customer().tenantId(),
                t.id()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id=? AND aggregate_id=? AND event_type='sla.breached'",
                Long.class,
                customer().tenantId(),
                t.id()))
        .isEqualTo(1);
  }

  @Test
  void warningIsDurableAndDeduplicated() {
    var t = create();
    var now = java.time.Instant.now();
    db.update(
        "UPDATE ticket_sla_cycles SET first_response_due_at=?,first_response_warning_at=? WHERE tenant_id=? AND ticket_id=?",
        java.sql.Timestamp.from(now.plusSeconds(600)),
        java.sql.Timestamp.from(now.minusSeconds(1)),
        customer().tenantId(),
        t.id());
    scheduler.evaluate(now);
    scheduler.evaluate(now);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sla_warnings w JOIN ticket_sla_cycles c ON c.tenant_id=w.tenant_id AND c.id=w.cycle_id WHERE c.tenant_id=? AND c.ticket_id=? AND w.metric='FIRST_RESPONSE'",
                Long.class,
                customer().tenantId(),
                t.id()))
        .isEqualTo(1);
  }

  @Test
  void pauseDoesNotEraseAnEarlierResolutionBreach() {
    var overdue = create();
    var pausedOnTime = create();
    var now = java.time.Instant.now();
    db.update(
        "UPDATE ticket_sla_cycles SET resolution_due_at=?,paused_at=? WHERE tenant_id=? AND ticket_id=?",
        java.sql.Timestamp.from(now.minusSeconds(10)),
        java.sql.Timestamp.from(now.minusSeconds(5)),
        customer().tenantId(),
        overdue.id());
    db.update(
        "UPDATE ticket_sla_cycles SET resolution_due_at=?,paused_at=? WHERE tenant_id=? AND ticket_id=?",
        java.sql.Timestamp.from(now.minusSeconds(5)),
        java.sql.Timestamp.from(now.minusSeconds(10)),
        customer().tenantId(),
        pausedOnTime.id());
    scheduler.evaluate(now);
    scheduler.evaluate(now);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sla_breaches WHERE tenant_id=? AND ticket_id=? AND metric='RESOLUTION'",
                Long.class,
                customer().tenantId(),
                overdue.id()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sla_breaches WHERE tenant_id=? AND ticket_id=? AND metric='RESOLUTION'",
                Long.class,
                customer().tenantId(),
                pausedOnTime.id()))
        .isZero();
  }

  @Test
  void expiredOutboxLeaseReplaysWithoutDuplicateNotifications() {
    var t = create();
    UUID event =
        db.queryForObject(
            "SELECT id FROM outbox_events WHERE tenant_id=? AND aggregate_id=? AND event_type='ticket.created'",
            UUID.class,
            customer().tenantId(),
            t.id());
    UUID oldToken = UUID.randomUUID();
    db.update(
        "UPDATE outbox_events SET status='PROCESSING',claimed_until=now()-interval '1 minute',claim_token=? WHERE id=?",
        oldToken,
        event);
    var job =
        worker.claim(10000).stream().filter(j -> j.id().equals(event)).findFirst().orElseThrow();
    assertThat(job.token()).isNotEqualTo(oldToken);
    worker.process(job);
    long count =
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE tenant_id=? AND outbox_event_id=?",
            Long.class,
            customer().tenantId(),
            event);
    assertThat(count).isGreaterThan(0);
    worker.process(job);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id=? AND outbox_event_id=?",
                Long.class,
                customer().tenantId(),
                event))
        .isEqualTo(count);
    assertThat(
            db.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, event))
        .isEqualTo("PROCESSED");
  }

  @Test
  void privateNoteCannotNotifyRequesterAndPoisonMessageStops() {
    var t = create();
    t =
        service.reply(
            agent(),
            t.id(),
            new Reply("Notification privacy sentinel", "PRIVATE", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    UUID event =
        db.queryForObject(
            "SELECT id FROM outbox_events WHERE tenant_id=? AND aggregate_id=? AND event_type='note.added'",
            UUID.class,
            customer().tenantId(),
            t.id());
    String payload =
        db.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE id=?", String.class, event);
    worker.process(
        new OutboxWorker.Job(event, customer().tenantId(), UUID.randomUUID(), 1, payload));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id=? AND recipient_membership_id=? AND outbox_event_id=?",
                Long.class,
                customer().tenantId(),
                customer().membershipId(),
                event))
        .isZero();
    UUID poison = UUID.randomUUID(), token = UUID.randomUUID();
    db.update(
        "INSERT INTO outbox_events(id,tenant_id,event_type,aggregate_id,payload,created_at,available_at,status,attempts,claim_token,dedup_key) VALUES (?,?,'poison',?,'{}',now(),now(),'PROCESSING',8,?,?)",
        poison,
        customer().tenantId(),
        t.id(),
        token,
        poison.toString());
    worker.process(new OutboxWorker.Job(poison, customer().tenantId(), token, 8, "{}"));
    assertThat(
            db.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, poison))
        .isEqualTo("DEAD_LETTER");
  }

  @Autowired com.heliosdesk.reporting.ReportingService reports;
  @Autowired com.heliosdesk.tickets.TicketMaintenance maintenance;
  @Autowired com.heliosdesk.servicelevels.ServiceLevelController.Policies policyService;
  @Autowired com.heliosdesk.servicelevels.SlaService sla;
  @Autowired com.heliosdesk.tenancy.TeamService teamService;

  @Test
  void teamManagementIsVersionedTenantScopedAndAudited() {
    var input =
        new com.heliosdesk.tenancy.TeamService.Input(
            "Escalation " + UUID.randomUUID(), true, 0, Set.of(agent().membershipId()));
    var team = teamService.save(manager(), null, input);
    assertThat(teamService.list(manager(), true).items()).anyMatch(t -> t.id().equals(team.id()));
    var changed =
        teamService.save(
            manager(),
            team.id(),
            new com.heliosdesk.tenancy.TeamService.Input(
                team.name(), false, team.version(), Set.of()));
    assertThat(changed.version()).isEqualTo(1);
    assertThat(teamService.list(agent(), false).items()).noneMatch(t -> t.id().equals(team.id()));
    assertThatThrownBy(() -> teamService.save(manager(), team.id(), input))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Reload");
    assertThatThrownBy(() -> teamService.save(agent(), null, input))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                teamService.save(
                    manager(),
                    null,
                    new com.heliosdesk.tenancy.TeamService.Input(
                        "Foreign member",
                        true,
                        0,
                        Set.of(actor("owner", "bluepeak", "OWNER").membershipId()))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                teamService.save(
                    actor("owner", "bluepeak", "OWNER"),
                    team.id(),
                    new com.heliosdesk.tenancy.TeamService.Input(
                        "Foreign team", true, 1, Set.of())))
        .isInstanceOf(ApiException.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id=? AND resource_id=? AND action='team.saved'",
                Long.class,
                manager().tenantId(),
                team.id()))
        .isEqualTo(2);
  }

  @Test
  void reportsCountPersistedFactsAndDenyCustomers() {
    var now = java.time.Instant.now();
    var before = reports.overview(manager(), now.minusSeconds(3600), now.plusSeconds(3600));
    var t = create();
    t =
        service.reply(
            agent(),
            t.id(),
            new Reply("Response for reporting", "PUBLIC", UUID.randomUUID()),
            t.version(),
            UUID.randomUUID().toString());
    t =
        service.transition(
            agent(), t.id(), new Transition("RESOLVED", "Reporting resolution."), t.version());
    var after = reports.overview(manager(), now.minusSeconds(3600), now.plusSeconds(3600));
    assertThat(
            after.trend().stream()
                .mapToLong(com.heliosdesk.reporting.ReportingService.Trend::created)
                .sum())
        .isEqualTo(
            before.trend().stream()
                    .mapToLong(com.heliosdesk.reporting.ReportingService.Trend::created)
                    .sum()
                + 1);
    assertThat(
            after.trend().stream()
                .mapToLong(com.heliosdesk.reporting.ReportingService.Trend::resolved)
                .sum())
        .isEqualTo(
            before.trend().stream()
                    .mapToLong(com.heliosdesk.reporting.ReportingService.Trend::resolved)
                    .sum()
                + 1);
    assertThatThrownBy(() -> reports.overview(customer(), null, null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> reports.overview(manager(), now, now.minusSeconds(1)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void calendarChangesPreserveCycleSnapshotAndRejectStalePolicy() {
    var t = create();
    String snapshot =
        db.queryForObject(
            "SELECT policy_snapshot::text FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=?",
            String.class,
            customer().tenantId(),
            t.id());
    var row =
        db.queryForMap(
            "SELECT id,version FROM sla_policies WHERE tenant_id=?", manager().tenantId());
    UUID id = (UUID) row.get("id");
    long version = ((Number) row.get("version")).longValue();
    var old = sla.policy(manager().tenantId());
    var update =
        new com.heliosdesk.servicelevels.ServiceLevelController.Update(
            version, old.calendar(), old.targets(), old.pauseOnPending(), 25);
    policyService.update(manager(), id, update);
    try {
      assertThat(
              db.queryForObject(
                  "SELECT policy_snapshot::text FROM ticket_sla_cycles WHERE tenant_id=? AND ticket_id=?",
                  String.class,
                  customer().tenantId(),
                  t.id()))
          .isEqualTo(snapshot);
      assertThatThrownBy(() -> policyService.update(manager(), id, update))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
      assertThatThrownBy(() -> policyService.update(agent(), id, update))
          .isInstanceOf(ApiException.class);
    } finally {
      policyService.update(
          manager(),
          id,
          new com.heliosdesk.servicelevels.ServiceLevelController.Update(
              version + 1,
              old.calendar(),
              old.targets(),
              old.pauseOnPending(),
              old.warningRatio()));
    }
  }

  @Test
  void automaticCloseIsAuditedWithoutImpersonatingAnOwner() {
    var t = create();
    t =
        service.transition(
            manager(),
            t.id(),
            new Transition("RESOLVED", "Close after the reopen window."),
            t.version());
    maintenance.closeDue(java.time.Instant.now().plus(java.time.Duration.ofDays(8)));
    var closed = query.detail(customer(), t.id());
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(query.timeline(customer(), t.id(), null, 100).items())
        .anyMatch(e -> e.actorName().equals("Helios System"));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id=? AND resource_id=? AND action='ticket.auto_closed' AND actor_membership_id IS NULL",
                Long.class,
                customer().tenantId(),
                t.id()))
        .isEqualTo(1);
  }

  @Test
  void trimmedSubjectValidationDoesNotBecomeDatabaseError() {
    assertThatThrownBy(
            () -> service.create(customer(), input("    a    "), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(400));
  }
}
