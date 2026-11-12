package com.heliosdesk.identity;

import com.heliosdesk.platform.Json;
import com.heliosdesk.servicelevels.*;
import com.heliosdesk.tickets.*;
import com.heliosdesk.tickets.TicketDtos.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DemoSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final TicketService tickets;
  private final Json json;
  private final String password;

  public DemoSeed(
      JdbcTemplate db,
      PasswordEncoder encoder,
      TicketService tickets,
      Json json,
      @Value("${helios.demo-password}") String password) {
    this.db = db;
    this.encoder = encoder;
    this.tickets = tickets;
    this.json = json;
    this.password = password;
  }

  public static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (password.length() < 12)
      throw new IllegalStateException(
          "Dev seed requires DEMO_PASSWORD with at least 12 characters");
    if (db.queryForObject("SELECT count(*) FROM tenants", Long.class) > 0) {
      sharedOwner();
      return;
    }
    String hash = encoder.encode(password);
    seed("northstar", "Northstar Support", "Asia/Ho_Chi_Minh", 40, hash);
    seed("bluepeak", "Bluepeak Labs", "America/New_York", 10, hash);
    sharedOwner();
  }

  private void sharedOwner() {
    db.update(
        "INSERT INTO tenant_memberships(id,tenant_id,user_id,role) SELECT ?,t.id,u.id,'OWNER' FROM tenants t CROSS JOIN users u WHERE t.slug='bluepeak' AND u.email='owner@northstar.test' ON CONFLICT(tenant_id,user_id) DO NOTHING",
        id("northstar-owner-bluepeak"));
  }

  private void seed(String slug, String name, String zone, int count, String hash) {
    UUID tenant = id(slug);
    db.update(
        "INSERT INTO tenants(id,slug,name,timezone) VALUES (?,?,?,?)", tenant, slug, name, zone);
    db.update("INSERT INTO ticket_counters(tenant_id) VALUES (?)", tenant);
    db.update(
        "INSERT INTO sla_policies(id,tenant_id,name,calendar,targets) VALUES (?,?,?,?::jsonb,?::jsonb)",
        id(slug + "policy"),
        tenant,
        "Standard support",
        json.write(BusinessCalendar.standard(zone)),
        json.write(SlaService.defaults()));
    var accounts = new LinkedHashMap<String, String>();
    accounts.put("owner", "OWNER");
    accounts.put("manager", "MANAGER");
    accounts.put("agent1", "AGENT");
    accounts.put("agent2", "AGENT");
    accounts.put("auditor", "AUDITOR");
    for (int i = 1; i <= (slug.equals("northstar") ? 4 : 2); i++)
      accounts.put("customer" + i, "REQUESTER");
    for (var account : accounts.entrySet()) {
      String email = account.getKey() + "@" + slug + ".test";
      String display =
          switch (account.getKey()) {
            case "owner" -> "Alex Morgan";
            case "manager" -> "Jordan Lee";
            case "agent1" -> "Sam Rivera";
            case "agent2" -> "Taylor Chen";
            case "auditor" -> "Casey Brooks";
            default -> "Customer " + account.getKey().substring(8);
          };
      db.update(
          "INSERT INTO users(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,?,?,now())",
          id(email),
          email,
          hash,
          display);
      db.update(
          "INSERT INTO tenant_memberships(id,tenant_id,user_id,role) VALUES (?,?,?,?)",
          id(email + "member"),
          tenant,
          id(email),
          account.getValue());
    }
    for (String team : List.of("Technical", "Billing")) {
      UUID teamId = id(slug + team);
      db.update("INSERT INTO teams(id,tenant_id,name) VALUES (?,?,?)", teamId, tenant, team);
      for (String agent : List.of("manager", "agent1", "agent2"))
        db.update(
            "INSERT INTO team_members(tenant_id,team_id,membership_id) VALUES (?,?,?)",
            tenant,
            teamId,
            id(agent + "@" + slug + ".testmember"));
      db.update(
          "INSERT INTO ticket_categories(id,tenant_id,name) VALUES (?,?,?)",
          id(slug + team + "category"),
          tenant,
          team);
    }
    Actor customer = actor(slug, name, zone, "customer1", "REQUESTER", "Avery Parker");
    Actor agent = actor(slug, name, zone, "agent1", "AGENT", "Sam Rivera");
    Actor manager = actor(slug, name, zone, "manager", "MANAGER", "Jordan Lee");
    String[] subjects = {
      "Cannot download my latest invoice",
      "API authentication returns an error",
      "Update billing contact for our workspace",
      "Team invitations are not arriving",
      "Dashboard takes too long to load",
      "Need help setting up SSO",
      "Export report is missing recent records",
      "Change workspace notification preferences",
      "Payment confirmation not received",
      "Webhook delivery needs investigation",
      "How do I add a new team member?",
      "Account access after password change"
    };
    for (int i = 0; i < count; i++) {
      var t =
          tickets.create(
              customer,
              new Create(
                  subjects[i % subjects.length],
                  "Hello support team, we need help with this issue in our workspace. Please let us know the next steps. Reference: demo-"
                      + (i + 1),
                  "NORMAL",
                  id(slug + (i % 2 == 0 ? "Technical" : "Billing") + "category"),
                  "PORTAL",
                  null),
              id(slug + "ticket" + i).toString());
      if (i % 4 == 0)
        t =
            tickets.edit(
                manager, t.id(), new Edit(null, i % 8 == 0 ? "URGENT" : "HIGH", null), t.version());
      if (i % 6 == 0) continue;
      t = tickets.claim(agent, t.id(), t.version(), UUID.randomUUID().toString());
      t =
          tickets.reply(
              agent,
              t.id(),
              new Reply(
                  "Internal investigation: checking logs and reproducing the reported issue. Keep diagnostics within the support team.",
                  "PRIVATE",
                  UUID.randomUUID()),
              t.version(),
              UUID.randomUUID().toString());
      if (i % 6 == 1) continue;
      t =
          tickets.reply(
              agent,
              t.id(),
              new Reply(
                  "Thanks for contacting us. We are looking into this and will keep you updated. Could you share the time when this first happened?",
                  "PUBLIC",
                  UUID.randomUUID()),
              t.version(),
              UUID.randomUUID().toString());
      if (i % 6 == 2)
        t =
            tickets.transition(
                agent, t.id(), new Transition("PENDING_CUSTOMER", null), t.version());
      if (i % 6 == 3)
        t =
            tickets.transition(
                agent,
                t.id(),
                new Transition("ON_HOLD", "Investigating upstream service behavior."),
                t.version());
      if (i % 6 >= 4) {
        t =
            tickets.transition(
                agent,
                t.id(),
                new Transition(
                    "RESOLVED",
                    "The issue has been fixed. Please reply here if you need further help."),
                t.version());
        if (i % 12 == 5)
          tickets.transition(manager, t.id(), new Transition("CLOSED", null), t.version());
        if (i % 12 == 11)
          tickets.reply(
              customer,
              t.id(),
              new Reply(
                  "The issue has returned. Can you take another look?",
                  "PUBLIC",
                  UUID.randomUUID()),
              t.version(),
              UUID.randomUUID().toString());
      }
    }
  }

  private Actor actor(
      String slug, String name, String zone, String account, String role, String display) {
    String email = account + "@" + slug + ".test";
    return new Actor(
        id(email), id(slug), id(email + "member"), role, display, email, name, slug, zone);
  }
}
