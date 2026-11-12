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
async function csrf(request: APIRequestContext) {
  const r = await request.get("/api/v1/auth/csrf");
  return (await r.json()) as { token: string; headerName: string };
}
test("customer → agent → customer lifecycle; private notes remain private", async ({
  browser,
}) => {
  const customer = await browser.newContext(),
    staff = await browser.newContext();
  const c = await customer.newPage(),
    a = await staff.newPage();
  await login(c, "customer1@northstar.test");
  await c.getByRole("link", { name: "New ticket", exact: true }).click();
  const subject = `Invoice download ${Date.now()}`;
  await c.getByLabel("Subject", { exact: true }).fill(subject);
  await c
    .getByLabel("Description", { exact: true })
    .fill("Cannot download our invoice after checkout.");
  await c.getByRole("button", { name: "Create ticket", exact: true }).click();
  await expect(c.getByRole("heading", { name: subject })).toBeVisible();
  const path = new URL(c.url()).pathname;
  await login(a, "agent1@northstar.test");
  await a.goto(path.replace("/portal/", "/app/"));
  await a.getByRole("button", { name: "Assign to me", exact: true }).click();
  await expect(
    a.getByRole("status").filter({ hasText: "Ticket updated." }),
  ).toBeVisible();
  await a.getByLabel("Message visibility").selectOption("PRIVATE");
  const secret = `Private sentinel ${Date.now()}`;
  await a.getByLabel("Internal note", { exact: true }).fill(secret);
  await a
    .getByRole("button", { name: "Add internal note", exact: true })
    .click();
  await expect(
    a.getByRole("article").getByText(secret, { exact: true }),
  ).toBeVisible();
  await c.reload();
  await expect(c.getByText(secret, { exact: true })).toHaveCount(0);
  const id = path.split("/").at(-1);
  const messages = await customer.request.get(`/api/v1/tickets/${id}/messages`);
  expect(await messages.text()).not.toContain(secret);
  await a.getByLabel("Message visibility").selectOption("PUBLIC");
  await a
    .getByLabel("Your reply", { exact: true })
    .fill("Invoice download has been restored.");
  await a.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(
    a
      .getByRole("article")
      .getByText("Invoice download has been restored.", { exact: true }),
  ).toBeVisible();
  await c.reload();
  await expect(
    c
      .getByRole("article")
      .getByText("Invoice download has been restored.", { exact: true }),
  ).toBeVisible();
  await c
    .getByLabel("Your reply", { exact: true })
    .fill("Thanks, confirmed working.");
  await c.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(
    c
      .getByRole("article")
      .getByText("Thanks, confirmed working.", { exact: true }),
  ).toBeVisible();
  await a.reload();
  await a.getByRole("button", { name: "Resolve ticket", exact: true }).click();
  await a
    .getByLabel("Reason / resolution message")
    .fill("Resolved: invoice access restored and confirmed.");
  await a.getByRole("button", { name: "Confirm change" }).click();
  await expect(a.getByText("Resolved", { exact: true }).first()).toBeVisible();
  await c.reload();
  await expect(c.getByText("Resolved", { exact: true }).first()).toBeVisible();
  await a.screenshot({
    path: "../../docs/screenshots/ticket-detail.png",
    fullPage: true,
  });
  await customer.close();
  await staff.close();
});
test("tenant isolation and customer role restrictions", async ({ browser }) => {
  const north = await browser.newContext(),
    blue = await browser.newContext();
  const n = await north.newPage(),
    b = await blue.newPage();
  await login(n, "customer1@northstar.test");
  const list = await north.request.get("/api/v1/tickets?limit=1");
  const data = await list.json();
  const id = data.items[0].id;
  await login(b, "owner@bluepeak.test");
  expect((await blue.request.get(`/api/v1/tickets/${id}`)).status()).toBe(404);
  await b.goto(`/app/tickets/${id}`);
  await expect(
    b.getByRole("alert").filter({ hasText: "Resource not found." }),
  ).toContainText("Resource not found.");
  expect((await north.request.get("/api/v1/audit")).status()).toBe(403);
  const token = await csrf(north.request);
  expect(
    (
      await north.request.post(`/api/v1/tickets/${id}/claim`, {
        headers: {
          [token.headerName]: token.token,
          "If-Match": '"v0"',
          "Idempotency-Key": crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(403);
  await north.close();
  await blue.close();
});
test("stale edit preserves response draft", async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await login(page, "agent1@northstar.test");
  const token = await csrf(context.request);
  const created = await context.request.post("/api/v1/tickets", {
    headers: {
      [token.headerName]: token.token,
      "Idempotency-Key": crypto.randomUUID(),
    },
    data: {
      subject: `Concurrency ${Date.now()}`,
      description: "Check stale edit behavior.",
      priority: "NORMAL",
      source: "AGENT",
    },
  });
  expect(created.ok()).toBeTruthy();
  const t = await created.json();
  await page.goto(`/app/tickets/${t.id}`);
  await page
    .getByLabel("Your reply", { exact: true })
    .fill("Keep this draft through conflict.");
  const update = await context.request.patch(`/api/v1/tickets/${t.id}`, {
    headers: { [token.headerName]: token.token, "If-Match": `"v${t.version}"` },
    data: { priority: "HIGH" },
  });
  expect(update.ok()).toBeTruthy();
  await page.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(
    page.getByText("This ticket changed while you were editing.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByLabel("Your reply", { exact: true })).toHaveValue(
    "Keep this draft through conflict.",
  );
  await page.getByRole("button", { name: "Reload latest version" }).click();
  await expect(page.getByLabel("Your reply", { exact: true })).toHaveValue(
    "Keep this draft through conflict.",
  );
  await page.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(
    page.getByText("Keep this draft through conflict.", { exact: true }),
  ).toBeVisible();
  await context.close();
});
test("responsive workspace and accessible core controls", async ({ page }) => {
  await page.goto("/login");
  const loginAxe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag22aa"])
    .analyze();
  expect(loginAxe.violations).toEqual([]);
  await login(page, "manager@northstar.test");
  await expect(
    page.getByRole("heading", { name: /A clearer day/ }),
  ).toBeVisible();
  await page.screenshot({
    path: "../../docs/screenshots/overview.png",
    fullPage: true,
  });
  const axe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag22aa"])
    .analyze();
  expect(axe.violations).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(
    page.getByRole("navigation", { name: "Main navigation" }),
  ).toBeVisible();
  await page.getByRole("link", { name: /All tickets/ }).click();
  await expect(
    page.getByRole("heading", { name: "All tickets", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../../docs/screenshots/mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
