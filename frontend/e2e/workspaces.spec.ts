import { test, expect, Page, APIRequestContext } from "@playwright/test";
import { readFileSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
const keys: Record<string, string> = {
  manager: "CATALOG_MANAGER",
  device: "DEVICE",
  reader: "CATALOG_READER",
  production: "PRODUCTION",
  quality: "QUALITY",
  workforce: "WORKFORCE",
  operator: "OPERATOR",
  maintenance: "MAINTENANCE",
  technician: "TECHNICIAN",
  facilities: "FACILITIES",
  logistics: "LOGISTICS",
  security: "SECURITY",
  gate: "GATE",
  payroll: "PAYROLL",
  payrollapprover: "PAYROLL_APPROVER",
  accountant: "ACCOUNTANT",
  finance: "FINANCE",
  financeapprover: "FINANCE_APPROVER",
  integration: "INTEGRATION",
  reports: "REPORTS",
  customer: "CUSTOMER",
  customer2: "CUSTOMER2",
  supplier: "SUPPLIER",
};
const config = readFileSync(
  "../src/test/resources/application-test.yaml",
  "utf8",
);
const passwords = Object.fromEntries(
  [...config.matchAll(/^([A-Z0-9_]+)_PASSWORD:\s*(.+)$/gm)].map((m) => [
    m[1],
    m[2].trim(),
  ]),
);
const api = process.env.PLASTIFLOW_TEST_API ?? "http://127.0.0.1:8090";
let nonce = Date.now().toString();
let employee: string, product: string, expense: string;
async function call(
  request: APIRequestContext,
  user: string,
  path: string,
  body?: unknown,
) {
  const response = await request.fetch(api + path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      Authorization:
        "Basic " +
        Buffer.from(user + ":" + passwords[keys[user]]).toString("base64"),
    },
    data: body,
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page
    .getByRole("combobox", { name: "Account", exact: true })
    .selectOption(user);
  await page
    .getByLabel("Password", { exact: true })
    .fill(passwords[keys[user]]);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("button", { name: "Sign out / switch role" }),
  ).toBeVisible();
}
test.beforeAll(async ({ request }) => {
  const file = "../acceptance-results/browser-fixture.json";
  if (existsSync(file)) {
    const fixture = JSON.parse(readFileSync(file, "utf8"));
    ({ employee, product, expense, nonce } = fixture);
    return;
  }
  employee = (
    await call(request, "workforce", "/api/v1/manufacturing/employees", {
      code: "UI-EMP-" + nonce,
      name: "UI Employee",
      userSubject: "ui-worker-" + nonce,
    })
  ).id;
  product = (
    await call(request, "manager", "/api/v1/products", {
      sku: "UI-BOX-" + nonce,
      name: "UI Box",
      kind: "FINISHED_GOOD",
      unit: "PIECE",
    })
  ).id;
  const period = (
    await call(request, "accountant", "/api/v1/finance/periods", {
      code: "UI-P-" + nonce,
      startsOn: "2001-01-01",
      endsOn: "2001-02-01",
      currency: "USD",
    })
  ).id;
  const budget = (
    await call(request, "finance", "/api/v1/finance/budgets", {
      code: "UI-B-" + nonce,
      periodId: period,
      amount: 1000,
    })
  ).id;
  expense = (
    await call(request, "finance", "/api/v1/finance/expenses", {
      code: "UI-EXP-" + nonce,
      budgetId: budget,
      amount: 10,
      description: "UI approved expense",
    })
  ).id;
  await call(
    request,
    "integration",
    "/api/v1/integration/published-products/" + product,
    {},
  );
  for (const [user, kind] of [
    ["customer", "CUSTOMER"],
    ["customer2", "CUSTOMER"],
    ["supplier", "SUPPLIER"],
  ]) {
    const partner = (
      await call(request, "integration", "/api/v1/integration/partners", {
        code: "UI-" + user + "-" + nonce,
        name: "UI " + user,
        kind,
        partyReference: "UI-PARTY-" + user + "-" + nonce,
      })
    ).id;
    await call(
      request,
      "integration",
      "/api/v1/integration/partners/" + partner + "/users",
      { principal: user },
    );
  }
  writeFileSync(file, JSON.stringify({ employee, product, expense, nonce }));
});
const accounts = [
  "manager",
  "reader",
  "production",
  "quality",
  "workforce",
  "operator",
  "maintenance",
  "technician",
  "facilities",
  "logistics",
  "security",
  "gate",
  "payroll",
  "payrollapprover",
  "accountant",
  "finance",
  "financeapprover",
  "integration",
  "reports",
  "customer",
  "customer2",
  "supplier",
];
for (const user of accounts)
  test(user + " opens its permitted workspace", async ({ page }) => {
    await login(page, user);
    await expect(page.locator("main h1")).toBeVisible();
    await expect(page.getByRole("alert")).toHaveCount(0);
  });
test("catalog creation and optimistic edit work through the screen", async ({
  page,
}) => {
  await login(page, "manager");
  await page.getByLabel("SKU", { exact: true }).fill("UI-CREATE-" + nonce);
  await page
    .getByLabel("Product name", { exact: true })
    .fill("UI Created product");
  await page
    .getByRole("button", { name: "Create product", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Product created");
  await page.getByLabel("SKU or name").fill("UI-CREATE-" + nonce);
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page.getByRole("button", { name: "Edit UI-CREATE-" + nonce }).click();
  await page.getByLabel("Name", { exact: true }).fill("UI Renamed product");
  await page.getByRole("button", { name: "Save product" }).click();
  await expect(page.getByRole("status")).toContainText("Product updated");
  await expect(
    page.getByRole("cell", { name: "UI Renamed product", exact: true }),
  ).toBeVisible();
});
test("empty HTTP success body is accepted for expense approval", async ({
  page,
}) => {
  await login(page, "financeapprover");
  await page.getByRole("button", { name: "Expenses", exact: true }).click();
  const panel = page.locator(".panel").first();
  await panel
    .getByRole("combobox")
    .first()
    .selectOption({ label: "Approve expense" });
  await page
    .getByRole("combobox", { name: "Expense", exact: true })
    .selectOption(expense);
  await page
    .getByRole("button", { name: "Approve expense", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Approve expense completed",
  );
  await expect(page.locator(".error")).toHaveCount(0);
});
test("device identity binding handles empty success and reports a failed projection correctly", async ({
  page,
  request,
}) => {
  await login(page, "integration");
  await page.getByLabel("Unique code").fill("UI-DEVICE-" + nonce);
  await page.getByLabel("Name", { exact: true }).fill("UI Device");
  await page
    .getByRole("combobox", { name: "Device type", exact: true })
    .selectOption("ATTENDANCE");
  await page
    .getByLabel("Dedicated identity-provider subject (demo: device)")
    .fill("device");
  await page
    .getByRole("button", { name: "Register device", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Register device completed",
  );
  const overview = await call(
    request,
    "integration",
    "/api/v1/integration/overview",
  );
  const d = overview.device.find((v: any) => v.code === "UI-DEVICE-" + nonce);
  await page
    .locator(".panel")
    .first()
    .getByRole("combobox")
    .first()
    .selectOption({ label: "Bind attendance identity" });
  await page
    .getByRole("combobox", { name: "Attendance device", exact: true })
    .selectOption(d.id);
  await page
    .getByLabel("Device person identifier (no biometric template)")
    .fill("UI-BADGE");
  await page
    .getByRole("combobox", { name: "Employee", exact: true })
    .selectOption(employee);
  await page
    .getByRole("button", { name: "Bind attendance identity", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Bind attendance identity completed",
  );
  const event = (
    await call(request, "device", "/api/v1/integration/device-events", {
      eventId: crypto.randomUUID(),
      sequence: 1,
      occurredAt: new Date().toISOString(),
      data: { person: "unmapped-person", kind: "IN" },
    })
  ).id;
  await page.getByRole("button", { name: "Refresh records" }).click();
  await page
    .locator(".panel")
    .first()
    .getByRole("combobox")
    .first()
    .selectOption({ label: "Apply or retry device event" });
  await page.getByLabel("Inbox event").selectOption(event);
  await page
    .getByRole("button", { name: "Apply or retry device event", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "Event blocked: RECORD_NOT_FOUND",
  );
  await expect(page.getByRole("status")).toHaveCount(0);
});
test("portal request stays with its company and desktop/mobile layout stays usable", async ({
  page,
}) => {
  await login(page, "customer");
  await page.getByLabel("Your unique reference").fill("UI-RFQ-" + nonce);
  await page.getByLabel("Description / delivery evidence").fill("100 UI Boxes");
  await page
    .getByRole("combobox", { name: "Product", exact: true })
    .selectOption(product);
  await page.getByLabel("Quantity", { exact: true }).fill("100");
  await page.getByRole("button", { name: "Submit for review" }).click();
  await expect(page.getByRole("status")).toContainText("Request submitted");
  await expect(
    page.getByRole("cell", { name: "UI-RFQ-" + nonce, exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out / switch role" }).click();
  await login(page, "customer2");
  await expect(
    page.getByRole("cell", { name: "UI-RFQ-" + nonce, exact: true }),
  ).toHaveCount(0);
  const qa = process.env.PLASTIFLOW_VISUAL_QA_DIR;
  await expect(page.getByRole("button",{name:"Submit for review"})).toBeVisible();
  if (qa) {
    mkdirSync(qa, { recursive: true });
    await page.screenshot({ path: qa + "/portal-desktop.png", fullPage: true });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("button", { name: "Submit for review" }),
  ).toBeVisible();
  if (qa)
    await page.screenshot({ path: qa + "/portal-mobile.png", fullPage: true });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
test("invalid credentials show a useful error without opening a workspace", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Password", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(page.getByRole("alert")).toContainText("HTTP 401");
  await expect(
    page.getByRole("button", { name: "Sign out / switch role" }),
  ).toHaveCount(0);
});
