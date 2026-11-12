import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

async function login(page: Page, email: string) {
  await page.goto("/login");
  await page.getByLabel("Email address").fill(email);
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD ?? "HeliosDemo!2026");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/(app\/overview|portal\/tickets)/);
}

async function headers(request: APIRequestContext) {
  const response = await request.get("/api/v1/auth/csrf");
  const token = await response.json();
  return {
    [token.headerName]: token.token,
    "Idempotency-Key": crypto.randomUUID(),
  };
}

test("durable SSE replays public activity after reconnect and preserves draft", async ({
  browser,
}) => {
  test.setTimeout(90000);
  const customer = await browser.newContext(),
    staff = await browser.newContext();
  const c = await customer.newPage(),
    a = await staff.newPage();
  await login(c, "customer1@northstar.test");
  const created = await customer.request.post("/api/v1/tickets", {
    headers: await headers(customer.request),
    data: {
      subject: `Reconnect ${Date.now()}`,
      description: "Durable notification test.",
      priority: "NORMAL",
      source: "PORTAL",
    },
  });
  expect(created.ok()).toBeTruthy();
  let ticket = await created.json();
  await c.goto(`/portal/tickets/${ticket.id}`);
  const received: {
    id: string;
    sequence: number;
    ticketId: string;
    type: string;
  }[] = [];
  await c.exposeFunction("recordNotice", (notice: (typeof received)[number]) =>
    received.push(notice),
  );
  await c.evaluate(() => {
    window.addEventListener("helios-ticket-updated", (event) => {
      const notice = (event as CustomEvent).detail;
      (
        window as unknown as { recordNotice: (notice: unknown) => void }
      ).recordNotice(notice);
    });
  });
  const replayHeaders: string[] = [];
  c.on("request", (request) => {
    if (request.url().includes("/events/stream"))
      void request
        .allHeaders()
        .then((h) => replayHeaders.push(h["last-event-id"] ?? ""));
  });
  await c
    .getByLabel("Your reply", { exact: true })
    .fill("Draft survives live updates.");
  await login(a, "agent1@northstar.test");
  async function reply(body: string, visibility: "PUBLIC" | "PRIVATE") {
    const result = await staff.request.post(
      `/api/v1/tickets/${ticket.id}/messages`,
      {
        headers: {
          ...(await headers(staff.request)),
          "If-Match": `"v${ticket.version}"`,
        },
        data: { body, visibility, clientMessageId: crypto.randomUUID() },
      },
    );
    expect(result.ok(), await result.text()).toBeTruthy();
    ticket = await result.json();
  }
  await reply("First public notification.", "PUBLIC");
  await expect
    .poll(
      () =>
        received.filter(
          (n) => n.ticketId === ticket.id && n.type === "agent.replied",
        ).length,
    )
    .toBe(1);
  const first = received.find(
    (n) => n.ticketId === ticket.id && n.type === "agent.replied",
  )!;
  await customer.setOffline(true);
  const secret = `Private replay sentinel ${Date.now()}`;
  await reply(secret, "PRIVATE");
  await reply("Public message during disconnect.", "PUBLIC");
  await new Promise((resolve) => setTimeout(resolve, 30000));
  await customer.setOffline(false);
  await expect
    .poll(
      () =>
        received.filter(
          (n) => n.ticketId === ticket.id && n.type === "agent.replied",
        ).length,
      {
        timeout: 20000,
      },
    )
    .toBe(2);
  await expect(
    c.getByRole("status").filter({
      hasText: "New activity on this ticket. Your draft is preserved.",
    }),
  ).toBeVisible();
  await expect(c.getByLabel("Your reply", { exact: true })).toHaveValue(
    "Draft survives live updates.",
  );
  await expect
    .poll(() => replayHeaders.some((id) => Number(id) >= first.sequence), {
      timeout: 30000,
    })
    .toBeTruthy();
  expect(
    received
      .filter((n) => n.ticketId === ticket.id)
      .every((n) =>
        ["agent.replied", "ticket.status_changed"].includes(n.type),
      ),
  ).toBeTruthy();
  const own = received.filter(
    (n) => n.ticketId === ticket.id && n.type === "agent.replied",
  );
  expect(own.map((n) => n.type)).toEqual(["agent.replied", "agent.replied"]);
  expect(new Set(own.map((n) => n.id)).size).toBe(2);
  const feed = await customer.request.get(
    "/api/v1/notifications?after=0&limit=100",
  );
  expect(await feed.text()).not.toContain(secret);
  await c.getByRole("button", { name: /^Notifications,/ }).click();
  await expect(
    c.getByRole("heading", { name: "Your notifications" }),
  ).toBeVisible();
  const read = c
    .getByRole("button", { name: "Mark notification read" })
    .first();
  await expect(read).toBeVisible();
  const response = c.waitForResponse(
    (r) =>
      /\/notifications\/[^/]+\/read$/.test(r.url()) &&
      r.request().method() === "PATCH",
  );
  await read.click();
  expect((await response).ok()).toBeTruthy();
  await customer.close();
  await staff.close();
});

test("reports and versioned business calendar editor use persisted facts", async ({
  page,
  request,
}) => {
  await login(page, "manager@northstar.test");
  await page.getByRole("link", { name: "Reports", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "A clearer picture." }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Current workload" }),
  ).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
  await page.getByLabel("Report date range").selectOption("7");
  await expect(page.getByRole("img", { name: /^Created/ })).toBeVisible();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.screenshot({
    path: "../../docs/screenshots/reports.png",
    fullPage: true,
  });
  await page.getByRole("link", { name: "Service levels", exact: true }).click();
  await expect(page.getByLabel("Timezone", { exact: true })).toHaveValue(
    "Asia/Ho_Chi_Minh",
  );
  const policies = await page.request.get("/api/v1/sla/policies");
  const policy = (await policies.json()).items[0];
  await page.getByRole("button", { name: "Save policy", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Policy saved.");
  const stale = await page.request.patch(`/api/v1/sla/policies/${policy.id}`, {
    headers: await headers(page.request),
    data: { expectedVersion: policy.version, ...policy.policy },
  });
  expect(stale.status()).toBe(409);
  expect((await stale.json()).code).toBe("POLICY_VERSION_CONFLICT");
  expect((await request.get("/api/v1/reports/overview")).status()).toBe(401);
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
});

test("manager creates and deactivates a team with staff-only membership", async ({
  page,
}) => {
  await login(page, "manager@northstar.test");
  await page.getByRole("link", { name: "Team directory", exact: true }).click();
  await page.getByRole("button", { name: "New team", exact: true }).click();
  const name = `Escalations ${Date.now()}`;
  await page.getByLabel("Team name", { exact: true }).fill(name);
  await page.getByLabel("Sam Rivera", { exact: true }).check();
  await expect(
    page.getByRole("dialog").getByText("Customer 1", { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Save team", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: name });
  await expect(row).toContainText("1");
  await page.getByRole("button", { name: `Edit ${name}`, exact: true }).click();
  await page.getByLabel("Active team", { exact: true }).uncheck();
  await page.getByRole("button", { name: "Save team", exact: true }).click();
  await expect(row).toContainText("Inactive");
});

test("tenant switch clears another tab and logout revokes shared session", async ({
  browser,
}) => {
  const context = await browser.newContext(),
    first = await context.newPage(),
    second = await context.newPage();
  await login(first, "owner@northstar.test");
  if (
    (await first.getByLabel("Switch workspace").inputValue()) !== "northstar"
  ) {
    await first.getByLabel("Switch workspace").selectOption("northstar");
    await expect(first.getByLabel("Switch workspace")).toHaveValue("northstar");
  }
  await second.goto("/app/tickets");
  await expect(second.getByLabel("Switch workspace")).toHaveValue("northstar");
  const north = await first.request.get("/api/v1/tickets?limit=1");
  const ticket = (await north.json()).items[0];
  await first.getByLabel("Switch workspace").selectOption("bluepeak");
  await expect(second.getByLabel("Switch workspace")).toHaveValue("bluepeak");
  expect(
    (await second.request.get(`/api/v1/tickets/${ticket.id}`)).status(),
  ).toBe(404);
  await first.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(second).toHaveURL(/\/login$/);
  expect((await second.request.get("/api/v1/auth/me")).status()).toBe(401);
  await context.close();
});
