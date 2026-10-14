package com.plastiflow.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class FinanceApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  final String B = "/api/v1/finance";

  @BeforeEach
  @AfterEach
  void clean() {
    for (String t :
        List.of(
            "allocation",
            "cost",
            "payment",
            "invoice",
            "expense",
            "budget",
            "entry",
            "journal",
            "period",
            "account",
            "lot_value",
            "recovery",
            "scrap",
            "audit")) db.update("delete from fin_" + t);
  }

  String code() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  JsonNode call(String method, String path, String role, Object body, int expected)
      throws Exception {
    var req =
        (method.equals("POST") ? post(path) : get(path))
            .with(user(role.toLowerCase(Locale.ROOT)).roles(role));
    if (body != null)
      req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var response = api.perform(req).andReturn().getResponse();
    assertThat(response.getStatus())
        .withFailMessage(
            "%s %s expected %s got %s: %s",
            method, path, expected, response.getStatus(), response.getContentAsString())
        .isEqualTo(expected);
    return response.getContentAsString().isBlank()
        ? json.createObjectNode()
        : json.readTree(response.getContentAsString());
  }

  String create(String path, String role, Object body) throws Exception {
    return call("POST", B + path, role, body, 201).get("id").asText();
  }

  String account(String kind) throws Exception {
    return create("/accounts", "ACCOUNTANT", Map.of("code", code(), "name", kind, "kind", kind));
  }

  String period() throws Exception {
    return create(
        "/periods",
        "ACCOUNTANT",
        Map.of(
            "code", code(), "startsOn", "2026-01-01", "endsOn", "2026-02-01", "currency", "USD"));
  }

  Map<String, Object> posting(String p) {
    return Map.of("periodId", p, "bookedOn", "2026-01-15", "reference", code());
  }

  Map<String, Object> line(String account, int debit, int credit) {
    return Map.of("accountId", account, "debit", debit, "credit", credit);
  }

  Map<String, Object> journal(String p, String a, String b, int debit, int credit) {
    return Map.of(
        "eventId",
        UUID.randomUUID(),
        "periodId",
        p,
        "bookedOn",
        "2026-01-15",
        "reference",
        code(),
        "description",
        "Test evidence",
        "lines",
        List.of(line(a, debit, 0), line(b, 0, credit)));
  }

  String budget(String p, int amount) throws Exception {
    return create(
        "/budgets", "FINANCE_MANAGER", Map.of("code", code(), "periodId", p, "amount", amount));
  }

  String expense(String b, int amount) throws Exception {
    return create(
        "/expenses",
        "FINANCE_MANAGER",
        Map.of(
            "code",
            code(),
            "budgetId",
            b,
            "amount",
            amount,
            "description",
            "External utility invoice evidence"));
  }

  void postExpense(String id, String p) throws Exception {
    call("POST", B + "/expenses/" + id + "/approve", "FINANCE_APPROVER", null, 200);
    var body = new HashMap<>(posting(p));
    body.put("expenseAccount", account("EXPENSE"));
    body.put("payableAccount", account("LIABILITY"));
    call("POST", B + "/expenses/" + id + "/post", "ACCOUNTANT", body, 200);
  }

  String invoice(String kind, int net, int tax) throws Exception {
    var b = new HashMap<String, Object>();
    b.put("code", code());
    b.put("kind", kind);
    b.put("partyReference", "party");
    b.put("documentReference", code());
    b.put("currency", "USD");
    b.put("net", net);
    b.put("tax", tax);
    b.put("dueOn", "2026-01-31");
    b.put("controlAccount", account(kind.equals("SALE") ? "ASSET" : "LIABILITY"));
    b.put("netAccount", account(kind.equals("SALE") ? "REVENUE" : "EXPENSE"));
    if (tax > 0) b.put("taxAccount", account(kind.equals("SALE") ? "LIABILITY" : "ASSET"));
    return create("/invoices", "FINANCE_MANAGER", b);
  }

  Map<String, Object> payment(String p, int amount) {
    var b = new HashMap<>(posting(p));
    b.put("eventId", UUID.randomUUID());
    b.put("bankAccount", uncheckedBank());
    b.put("amount", amount);
    b.put("externalReference", code());
    return b;
  }

  String uncheckedBank() {
    try {
      return account("ASSET");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  String lot(int kg) throws Exception {
    String product =
        call(
                "POST",
                "/api/v1/products",
                "CATALOG_MANAGER",
                Map.of("sku", code(), "name", "Resin", "kind", "RAW_MATERIAL", "unit", "KG"),
                201)
            .get("id")
            .asText();
    return call(
            "POST",
            "/api/v1/manufacturing/lots",
            "PRODUCTION_MANAGER",
            Map.of(
                "code",
                code(),
                "materialId",
                product,
                "supplierReference",
                "supplier",
                "location",
                "warehouse",
                "receivedKg",
                kg),
            201)
        .get("id")
        .asText();
  }

  String scrap(String lot, int kg) throws Exception {
    return create(
        "/scrap",
        "PRODUCTION_MANAGER",
        Map.of(
            "code",
            code(),
            "lotId",
            lot,
            "kg",
            kg,
            "classification",
            "clean segregated resin",
            "reason",
            "Measured damaged stock"));
  }

  Map<String, Object> recovery(int input, int output) {
    return Map.of(
        "eventId",
        UUID.randomUUID(),
        "lotCode",
        code(),
        "location",
        "quarantine",
        "inputKg",
        input,
        "recoveredKg",
        output,
        "dispositionReference",
        "weighing and disposal evidence");
  }

  @Test
  void balancedJournalReplayAndReversal() throws Exception {
    String p = period(), a = account("ASSET"), b = account("EQUITY");
    var body = journal(p, a, b, 100, 100);
    String id =
        call("POST", B + "/journals", "ACCOUNTANT", body, 201).get("journal").get("id").asText();
    call("POST", B + "/journals", "ACCOUNTANT", body, 409);
    call("POST", B + "/journals/" + id + "/reverse", "ACCOUNTANT", posting(p), 200);
    call("POST", B + "/journals/" + id + "/reverse", "ACCOUNTANT", posting(p), 409);
    var trial = call("GET", B + "/periods/" + p + "/trial-balance", "ACCOUNTANT", null, 200);
    for (var row : trial) assertThat(row.get("balance").decimalValue()).isEqualByComparingTo("0");
    assertThat(db.queryForObject("select count(*) from fin_journal", Integer.class)).isEqualTo(2);
  }

  @Test
  void unbalancedAndInvalidLinesRollback() throws Exception {
    String p = period(), a = account("ASSET"), b = account("EQUITY");
    call("POST", B + "/journals", "ACCOUNTANT", journal(p, a, b, 100, 90), 409);
    var req = new HashMap<>(journal(p, a, b, 100, 100));
    req.put("lines", List.of(line(a, 100, 1), line(b, 0, 99)));
    call("POST", B + "/journals", "ACCOUNTANT", req, 409);
    assertThat(db.queryForObject("select count(*) from fin_journal", Integer.class)).isZero();
  }

  @Test
  void periodBoundsAndClosedPeriodBlockPosting() throws Exception {
    String p = period(), a = account("ASSET"), b = account("EQUITY");
    var req = new HashMap<>(journal(p, a, b, 10, 10));
    req.put("bookedOn", "2026-02-01");
    call("POST", B + "/journals", "ACCOUNTANT", req, 409);
    call("POST", B + "/periods/" + p + "/close", "ACCOUNTANT", null, 200);
    call("POST", B + "/journals", "ACCOUNTANT", journal(p, a, b, 10, 10), 409);
  }

  @Test
  void overlappingAndUnsupportedCurrencyPeriodsFail() throws Exception {
    period();
    call(
        "POST",
        B + "/periods",
        "ACCOUNTANT",
        Map.of("code", code(), "startsOn", "2026-01-15", "endsOn", "2026-02-15", "currency", "USD"),
        409);
    call(
        "POST",
        B + "/periods",
        "ACCOUNTANT",
        Map.of("code", code(), "startsOn", "2026-01-01", "endsOn", "2026-02-01", "currency", "JPY"),
        409);
  }

  @Test
  void salePostingSettlementAndOverpayment() throws Exception {
    String p = period(), i = invoice("SALE", 100, 20);
    call("POST", B + "/invoices/" + i + "/post", "ACCOUNTANT", posting(p), 200);
    call("POST", B + "/invoices/" + i + "/post", "ACCOUNTANT", posting(p), 409);
    var pay = payment(p, 50);
    call("POST", B + "/invoices/" + i + "/payments", "ACCOUNTANT", pay, 201);
    call("POST", B + "/invoices/" + i + "/payments", "ACCOUNTANT", pay, 409);
    call("POST", B + "/invoices/" + i + "/payments", "ACCOUNTANT", payment(p, 71), 409);
    var inv = call("GET", B + "/overview", "FINANCE_MANAGER", null, 200).get("invoice").get(0);
    assertThat(inv.get("outstanding").decimalValue()).isEqualByComparingTo("70");
  }

  @Test
  void purchasePostsCorrectDebitCredit() throws Exception {
    String p = period(), i = invoice("PURCHASE", 100, 20);
    var j = call("POST", B + "/invoices/" + i + "/post", "ACCOUNTANT", posting(p), 200);
    assertThat(j.get("lines").get(0).get("credit").decimalValue()).isEqualByComparingTo("120");
    call("POST", B + "/invoices/" + i + "/payments", "ACCOUNTANT", payment(p, 120), 201);
  }

  @Test
  void currencyMismatchPreventsInvoiceJournal() throws Exception {
    String p = period(), i = invoice("SALE", 10, 0);
    db.update("update fin_invoice set currency='EUR' where id=?", UUID.fromString(i));
    call("POST", B + "/invoices/" + i + "/post", "ACCOUNTANT", posting(p), 409);
    assertThat(db.queryForObject("select count(*) from fin_journal", Integer.class)).isZero();
  }

  @Test
  void budgetApprovalAndCancellationReleaseCommitment() throws Exception {
    String p = period(), b = budget(p, 100), e = expense(b, 80), e2 = expense(b, 30);
    call("POST", B + "/expenses/" + e + "/approve", "FINANCE_APPROVER", null, 200);
    call("POST", B + "/expenses/" + e2 + "/approve", "FINANCE_APPROVER", null, 409);
    call("POST", B + "/expenses/" + e + "/cancel", "FINANCE_MANAGER", null, 200);
    call("POST", B + "/expenses/" + e2 + "/approve", "FINANCE_APPROVER", null, 200);
    call("POST", B + "/periods/" + p + "/close", "ACCOUNTANT", null, 409);
  }

  @Test
  void samePersonCannotApproveExpenseEvenWithBothRoles() throws Exception {
    String e = expense(budget(period(), 100), 20);
    var result =
        api.perform(
                post(B + "/expenses/" + e + "/approve")
                    .with(user("finance_manager").roles("FINANCE_MANAGER", "FINANCE_APPROVER")))
            .andReturn()
            .getResponse();
    assertThat(result.getStatus()).isEqualTo(409);
    assertThat(result.getContentAsString()).contains("SEPARATE_APPROVER");
  }

  @Test
  void postedExpenseCannotBePostedAgainOrCancelled() throws Exception {
    String p = period(), e = expense(budget(p, 100), 20);
    postExpense(e, p);
    var body = new HashMap<>(posting(p));
    body.put("expenseAccount", account("EXPENSE"));
    body.put("payableAccount", account("LIABILITY"));
    call("POST", B + "/expenses/" + e + "/post", "ACCOUNTANT", body, 409);
    call("POST", B + "/expenses/" + e + "/cancel", "FINANCE_MANAGER", null, 409);
    call("POST", B + "/periods/" + p + "/close", "ACCOUNTANT", null, 200);
  }

  @Test
  void recyclingMassStockReplayAndQualityGate() throws Exception {
    String l = lot(100), w = scrap(l, 40);
    assertThat(
            db.queryForObject(
                "select remaining_kg from mfg_lot where id=?",
                BigDecimal.class,
                UUID.fromString(l)))
        .isEqualByComparingTo("60");
    call("POST", B + "/scrap/" + w + "/recover", "PRODUCTION_MANAGER", recovery(20, 21), 409);
    var r = recovery(20, 18);
    var output = call("POST", B + "/scrap/" + w + "/recover", "PRODUCTION_MANAGER", r, 201);
    call("POST", B + "/scrap/" + w + "/recover", "PRODUCTION_MANAGER", r, 409);
    assertThat(
            db.queryForObject(
                "select remaining_kg from fin_scrap where id=?",
                BigDecimal.class,
                UUID.fromString(w)))
        .isEqualByComparingTo("20");
    var trace =
        call(
            "GET",
            B + "/recovery-trace/" + output.get("outputLotId").asText(),
            "QUALITY_MANAGER",
            null,
            200);
    assertThat(trace.get("outputLot").get("quality").asText()).isEqualTo("PENDING");
    assertThat(trace.get("recovery").get("loss_kg").decimalValue()).isEqualByComparingTo("2");
    assertThat(trace.get("recovery").get("source_lot_id").asText()).isEqualTo(l);
  }

  @Test
  void duplicateScrapTransferRollsBackStock() throws Exception {
    String l = lot(10);
    var r =
        Map.of("code", code(), "lotId", l, "kg", 5, "classification", "clean", "reason", "damage");
    create("/scrap", "PRODUCTION_MANAGER", r);
    call("POST", B + "/scrap", "PRODUCTION_MANAGER", r, 409);
    assertThat(
            db.queryForObject(
                "select remaining_kg from mfg_lot where id=?",
                BigDecimal.class,
                UUID.fromString(l)))
        .isEqualByComparingTo("5");
    call(
        "POST",
        B + "/scrap",
        "PRODUCTION_MANAGER",
        Map.of("code", code(), "lotId", l, "kg", 6, "classification", "clean", "reason", "damage"),
        409);
  }

  @Test
  void financialAndPayrollDataAreRoleRestricted() throws Exception {
    call("GET", B + "/overview", "OPERATOR", null, 403);
    call("GET", B + "/overview", "PRODUCTION_MANAGER", null, 403);
    call(
        "POST",
        B + "/accounts",
        "FINANCE_MANAGER",
        Map.of("code", code(), "name", "x", "kind", "ASSET"),
        403);
    call("GET", B + "/payroll/" + UUID.randomUUID() + "/lines", "FINANCE_MANAGER", null, 403);
    call("GET", B + "/recycling-overview", "PRODUCTION_MANAGER", null, 200);
  }

  // Database fixtures represent immutable upstream approved/finalized records; finance behavior is
  // exercised through HTTP.
  UUID finalizedPay() {
    UUID employee = UUID.randomUUID(), period = UUID.randomUUID(), line = UUID.randomUUID();
    db.update(
        "insert into mfg_employee values(?,?,?,?,true)", employee, code(), "Cost employee", code());
    db.update(
        "insert into wf_period values(?,?,?,?,?,?,?,'FINALIZED',?,?,?)",
        period,
        code(),
        java.sql.Date.valueOf("2026-01-01"),
        java.sql.Date.valueOf("2026-02-01"),
        "UTC",
        "USD",
        2,
        "calculator",
        "reviewer",
        "finalizer");
    db.update(
        "insert into wf_pay_line values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        line,
        period,
        employee,
        60,
        0,
        0,
        0,
        100,
        0,
        0,
        0,
        10,
        100,
        90,
        "{}");
    return line;
  }

  @Test
  void finalizedPayrollPostsOnceAndBalancesDeductions() throws Exception {
    UUID l = finalizedPay();
    UUID source = db.queryForObject("select period_id from wf_pay_line where id=?", UUID.class, l);
    String p = period();
    var body = new HashMap<>(posting(p));
    body.put("wagesAccount", account("EXPENSE"));
    body.put("payableAccount", account("LIABILITY"));
    body.put("deductionsAccount", account("LIABILITY"));
    var j = call("POST", B + "/payroll/" + source + "/post", "ACCOUNTANT", body, 200);
    assertThat(j.get("lines").get(0).get("debit").decimalValue()).isEqualByComparingTo("100");
    call("POST", B + "/payroll/" + source + "/post", "ACCOUNTANT", body, 409);
    db.update("update wf_period set state='REVIEWED' where id=?", source);
    call("POST", B + "/payroll/" + source + "/post", "ACCOUNTANT", body, 404);
  }

  UUID completedOrder(String lot, String quality) {
    UUID
        product =
            db.queryForObject(
                "select material_id from mfg_lot where id=?", UUID.class, UUID.fromString(lot)),
        recipe = UUID.randomUUID(),
        plan = UUID.randomUUID(),
        order = UUID.randomUUID();
    db.update(
        "insert into mfg_recipe values(?,?,?,'INJECTION',1,'{}','bag',CURRENT_TIMESTAMP)",
        recipe,
        product,
        1);
    db.update("insert into mfg_inspection_plan values(?,?,1,'[]')", plan, product);
    db.update(
        "insert into mfg_order"
            + " (id,code,sales_reference,recipe_id,inspection_plan_id,planned_qty,good_qty,reject_qty,state)"
            + " values(?,?,?,?,?,10,10,1,'COMPLETED')",
        order,
        code(),
        "demand",
        recipe,
        plan);
    db.update(
        "insert into mfg_consumption values(?,?,?,5,CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        order,
        UUID.fromString(lot));
    db.update("insert into mfg_batch values(?,?,10,?)", UUID.randomUUID(), order, quality);
    return order;
  }

  void value(String lot) throws Exception {
    create(
        "/lot-values",
        "ACCOUNTANT",
        Map.of(
            "lotId",
            lot,
            "currency",
            "USD",
            "unitCost",
            2,
            "evidence",
            "Supplier invoice costing"));
  }

  @Test
  void actualCostIncludesRejectedConsumptionAndCapsExpenseAllocation() throws Exception {
    String p = period(), e = expense(budget(p, 100), 20), lot = lot(100);
    postExpense(e, p);
    value(lot);
    UUID order = completedOrder(lot, "APPROVED");
    var req =
        Map.of(
            "orderId",
            order,
            "currency",
            "USD",
            "allocations",
            List.of(Map.of("kind", "EXPENSE", "sourceId", e, "amount", 15)));
    var cost = call("POST", B + "/costs", "ACCOUNTANT", req, 201);
    assertThat(cost.get("material_cost").decimalValue()).isEqualByComparingTo("10");
    assertThat(cost.get("total").decimalValue()).isEqualByComparingTo("25");
    assertThat(cost.get("unit_cost").decimalValue()).isEqualByComparingTo("2.5");
    call("POST", B + "/costs", "ACCOUNTANT", req, 409);
    String l2 = lot(100);
    value(l2);
    UUID order2 = completedOrder(l2, "APPROVED");
    call(
        "POST",
        B + "/costs",
        "ACCOUNTANT",
        Map.of(
            "orderId",
            order2,
            "currency",
            "USD",
            "allocations",
            List.of(Map.of("kind", "EXPENSE", "sourceId", e, "amount", 6))),
        409);
    assertThat(db.queryForObject("select count(*) from fin_cost", Integer.class)).isEqualTo(1);
  }

  @Test
  void repeatedPayrollAllocationsInOneRequestCannotBypassCap() throws Exception {
    UUID line = finalizedPay();
    String lot = lot(100);
    value(lot);
    UUID order = completedOrder(lot, "APPROVED");
    call(
        "POST",
        B + "/costs",
        "ACCOUNTANT",
        Map.of(
            "orderId",
            order,
            "currency",
            "USD",
            "allocations",
            List.of(
                Map.of("kind", "PAYROLL", "sourceId", line, "amount", 60),
                Map.of("kind", "PAYROLL", "sourceId", line, "amount", 60))),
        409);
    assertThat(db.queryForObject("select count(*) from fin_allocation", Integer.class)).isZero();
    call(
        "POST",
        B + "/costs",
        "ACCOUNTANT",
        Map.of(
            "orderId",
            order,
            "currency",
            "USD",
            "allocations",
            List.of(Map.of("kind", "PAYROLL", "sourceId", line, "amount", 100))),
        201);
  }

  @Test
  void qualityAndMissingValuationPreventCostCapture() throws Exception {
    String lot = lot(100);
    UUID order = completedOrder(lot, "PENDING");
    var req = Map.of("orderId", order, "currency", "USD", "allocations", List.of());
    call("POST", B + "/costs", "ACCOUNTANT", req, 404);
    db.update("update mfg_batch set quality='APPROVED' where order_id=?", order);
    call("POST", B + "/costs", "ACCOUNTANT", req, 404);
    value(lot);
    call("POST", B + "/costs", "ACCOUNTANT", req, 201);
  }

  @Test
  void concurrentApprovalsRespectBudget() throws Exception {
    String b = budget(period(), 100), e1 = expense(b, 70), e2 = expense(b, 70);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results =
          pool.invokeAll(
              List.<Callable<Integer>>of(() -> approveStatus(e1), () -> approveStatus(e2)));
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from fin_expense where state='APPROVED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentSettlementsCannotOverpay() throws Exception {
    String p = period(), i = invoice("SALE", 100, 0);
    call("POST", B + "/invoices/" + i + "/post", "ACCOUNTANT", posting(p), 200);
    var b1 = payment(p, 70);
    var b2 = payment(p, 70);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results =
          pool.invokeAll(
              List.<Callable<Integer>>of(() -> paymentStatus(i, b1), () -> paymentStatus(i, b2)));
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(db.queryForObject("select sum(amount) from fin_payment", BigDecimal.class))
        .isEqualByComparingTo("70");
  }

  int paymentStatus(String id, Object body) throws Exception {
    return api.perform(
            post(B + "/invoices/" + id + "/payments")
                .with(user("accountant").roles("ACCOUNTANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void trialBalanceCarriesEarlierPeriodsAndExcludesOtherCurrencies() throws Exception {
    String january = period(), bank = account("ASSET"), capital = account("EQUITY");
    call("POST", B + "/journals", "ACCOUNTANT", journal(january, bank, capital, 100, 100), 201);
    String feb =
        create(
            "/periods",
            "ACCOUNTANT",
            Map.of(
                "code",
                code(),
                "startsOn",
                "2026-02-01",
                "endsOn",
                "2026-03-01",
                "currency",
                "USD"));
    String eur =
        create(
            "/periods",
            "ACCOUNTANT",
            Map.of(
                "code",
                code(),
                "startsOn",
                "2026-01-01",
                "endsOn",
                "2026-02-01",
                "currency",
                "EUR"));
    call("POST", B + "/journals", "ACCOUNTANT", journal(eur, bank, capital, 999, 999), 201);
    var body = new HashMap<>(journal(feb, bank, capital, 20, 20));
    body.put("bookedOn", "2026-02-15");
    call("POST", B + "/journals", "ACCOUNTANT", body, 201);
    var balances = call("GET", B + "/periods/" + feb + "/trial-balance", "ACCOUNTANT", null, 200);
    for (var row : balances)
      if (row.get("id").asText().equals(bank))
        assertThat(row.get("balance").decimalValue()).isEqualByComparingTo("120");
    var old = call("GET", B + "/periods/" + january + "/trial-balance", "ACCOUNTANT", null, 200);
    for (var row : old)
      if (row.get("id").asText().equals(bank))
        assertThat(row.get("balance").decimalValue()).isEqualByComparingTo("100");
  }

  int approveStatus(String id) throws Exception {
    return api.perform(
            post(B + "/expenses/" + id + "/approve")
                .with(user("reviewer").roles("FINANCE_APPROVER")))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void reversalCannotPrecedeOriginalPosting() throws Exception {
    String p = period();
    var j =
        call(
            "POST",
            B + "/journals",
            "ACCOUNTANT",
            journal(p, account("ASSET"), account("EQUITY"), 100, 100),
            201);
    var reverse = new HashMap<>(posting(p));
    reverse.put("bookedOn", "2026-01-14");
    assertThat(
            call(
                    "POST",
                    B + "/journals/" + j.get("journal").get("id").asText() + "/reverse",
                    "ACCOUNTANT",
                    reverse,
                    409)
                .get("code")
                .asText())
        .isEqualTo("REVERSAL_DATE");
  }

  @Test
  void settlementCannotPrecedeInvoicePosting() throws Exception {
    String p = period(), invoice = invoice("SALE", 100, 0);
    call("POST", B + "/invoices/" + invoice + "/post", "ACCOUNTANT", posting(p), 200);
    var payment = new HashMap<>(payment(p, 100));
    payment.put("bookedOn", "2026-01-14");
    assertThat(
            call("POST", B + "/invoices/" + invoice + "/payments", "ACCOUNTANT", payment, 409)
                .get("code")
                .asText())
        .isEqualTo("PAYMENT_DATE");
    assertThat(db.queryForObject("select count(*) from fin_payment", Integer.class)).isZero();
  }
}
