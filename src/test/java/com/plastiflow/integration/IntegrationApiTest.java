package com.plastiflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
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

@SpringBootTest(properties = "PLASTIFLOW_AI_ENABLED=false")
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class IntegrationApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  final String B = "/api/v1/integration";

  @BeforeEach
  @AfterEach
  void clean() {
    for (String table :
        List.of(
            "audit",
            "ai_job",
            "portal_request",
            "partner_user",
            "partner",
            "public_product",
            "event",
            "identity",
            "scale_visit",
            "device")) db.update("delete from int_" + table);
  }

  String code() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  JsonNode call(
      String method, String path, String principal, String role, Object body, int expected)
      throws Exception {
    var req = (method.equals("POST") ? post(path) : get(path)).with(user(principal).roles(role));
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

  JsonNode admin(String path, Object body, int status) throws Exception {
    return call("POST", B + path, "integration", "INTEGRATION_MANAGER", body, status);
  }

  String create(String path, Object body) throws Exception {
    return admin(path, body, 201).get("id").asText();
  }

  String device(String kind, String principal, String target) throws Exception {
    var r =
        new HashMap<String, Object>(
            Map.of("code", code(), "name", "Device", "kind", kind, "principal", principal));
    if (target != null) r.put("targetId", target);
    if (kind.equals("SENSOR")) {
      r.put("metric", "temperature");
      r.put("unit", "C");
    }
    return create("/devices", r);
  }

  Map<String, Object> event(long seq, Instant at, Map<String, String> data) {
    return Map.of(
        "eventId", UUID.randomUUID(), "sequence", seq, "occurredAt", at.toString(), "data", data);
  }

  JsonNode receive(String principal, Object body, int status) throws Exception {
    return call("POST", B + "/device-events", principal, "DEVICE_AGENT", body, status);
  }

  String employee() throws Exception {
    return call(
            "POST",
            "/api/v1/manufacturing/employees",
            "workforce",
            "WORKFORCE_MANAGER",
            Map.of("code", code(), "name", "Operator", "userSubject", code()),
            201)
        .get("id")
        .asText();
  }

  String product(String name, String unit) throws Exception {
    return call(
            "POST",
            "/api/v1/products",
            "manager",
            "CATALOG_MANAGER",
            Map.of("sku", code(), "name", name, "kind", "FINISHED_GOOD", "unit", unit),
            201)
        .get("id")
        .asText();
  }

  String machine() throws Exception {
    return call(
            "POST",
            "/api/v1/manufacturing/machines",
            "production",
            "PRODUCTION_MANAGER",
            Map.of("code", code(), "name", "Machine", "process", "INJECTION", "capacityKg", 100),
            201)
        .get("id")
        .asText();
  }

  String partner(String kind, String principal, String reference) throws Exception {
    String p =
        create(
            "/partners",
            Map.of("code", code(), "name", principal, "kind", kind, "partyReference", reference));
    admin("/partners/" + p + "/users", Map.of("principal", principal), 200);
    return p;
  }

  JsonNode portal(String method, String path, String principal, Object body, int status)
      throws Exception {
    return call(method, "/api/v1/portal" + path, principal, "PORTAL_USER", body, status);
  }

  Map<String, Object> portalRequest(String product, String kind, int qty) {
    return Map.of(
        "eventId",
        UUID.randomUUID(),
        "kind",
        kind,
        "reference",
        code(),
        "description",
        "Commercial request evidence",
        "lines",
        List.of(Map.of("productId", product, "quantity", qty)));
  }

  @Test
  void clockCaptureIsReplaySafeAndDoesNotApproveAttendance() throws Exception {
    String d = device("ATTENDANCE", "device", null), e = employee();
    admin(
        "/devices/" + d + "/identities", Map.of("externalPerson", "badge-1", "employeeId", e), 200);
    var r = event(1, Instant.now().minusSeconds(20), Map.of("person", "badge-1", "kind", "IN"));
    String id = receive("device", r, 201).get("id").asText();
    receive("device", r, 201);
    var result = admin("/events/" + id + "/apply", null, 200);
    assertThat(result.get("state").asText()).isEqualTo("APPLIED");
    admin("/events/" + id + "/apply", null, 200);
    assertThat(
            db.queryForObject(
                "select count(*) from wf_clock_event where event_id=?",
                Integer.class,
                UUID.fromString(id)))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from wf_attendance where employee_id=?",
                Integer.class,
                UUID.fromString(e)))
        .isZero();
  }

  @Test
  void failedMappingCanBeFixedThenRetried() throws Exception {
    String d = device("ATTENDANCE", "device", null);
    String id =
        receive("device", event(1, Instant.now(), Map.of("person", "x", "kind", "OUT")), 201)
            .get("id")
            .asText();
    assertThat(admin("/events/" + id + "/apply", null, 200).get("state").asText())
        .isEqualTo("FAILED");
    admin(
        "/devices/" + d + "/identities",
        Map.of("externalPerson", "x", "employeeId", employee()),
        200);
    var applied = admin("/events/" + id + "/apply", null, 200);
    assertThat(applied.get("state").asText()).isEqualTo("APPLIED");
    assertThat(applied.get("attempts").asInt()).isEqualTo(2);
  }

  @Test
  void eventPayloadSequenceAndPrincipalAreBound() throws Exception {
    device("ATTENDANCE", "device", null);
    device("ATTENDANCE", "other-device", null);
    var r = event(1, Instant.now(), Map.of("person", "x", "kind", "IN"));
    receive("device", r, 201);
    var changed = new HashMap<>(r);
    changed.put("data", Map.of("person", "x", "kind", "OUT"));
    receive("device", changed, 409);
    receive("other-device", r, 409);
    receive("device", event(1, Instant.now(), Map.of("person", "x", "kind", "OUT")), 409);
    receive("unbound", event(2, Instant.now(), Map.of("person", "x", "kind", "IN")), 404);
  }

  @Test
  void revocationBlocksNewAndQueuedEvents() throws Exception {
    String d = device("ATTENDANCE", "device", null);
    String id =
        receive("device", event(1, Instant.now(), Map.of("person", "x", "kind", "IN")), 201)
            .get("id")
            .asText();
    admin("/devices/" + d + "/revoke", null, 200);
    receive("device", event(2, Instant.now(), Map.of("person", "x", "kind", "IN")), 409);
    var result = admin("/events/" + id + "/apply", null, 200);
    assertThat(result.get("error_code").asText()).isEqualTo("DEVICE_REVOKED");
  }

  @Test
  void invalidSchemaAndFutureEvidenceRejected() throws Exception {
    device("ATTENDANCE", "device", null);
    receive("device", event(1, Instant.now(), Map.of("state", "ONLINE")), 409);
    receive(
        "device",
        event(2, Instant.now().plusSeconds(600), Map.of("person", "x", "kind", "IN")),
        409);
    receive(
        "device",
        event(3, Instant.now().minus(Duration.ofDays(31)), Map.of("person", "x", "kind", "IN")),
        409);
    assertThat(db.queryForObject("select count(*) from int_event", Integer.class)).isZero();
  }

  @Test
  void cameraOutOfOrderEventCannotRestoreStaleState() throws Exception {
    String zone =
        call(
                "POST",
                "/api/v1/operations/zones",
                "security",
                "SECURITY_MANAGER",
                Map.of("code", code(), "name", "Gate"),
                201)
            .get("id")
            .asText();
    String camera =
        call(
                "POST",
                "/api/v1/operations/cameras",
                "security",
                "SECURITY_MANAGER",
                Map.of(
                    "code", code(), "name", "Camera", "zoneId", zone, "vmsReference", "vms-camera"),
                201)
            .get("id")
            .asText();
    device("CAMERA", "device", camera);
    String newer =
        receive("device", event(2, Instant.now().minusSeconds(5), Map.of("state", "OFFLINE")), 201)
            .get("id")
            .asText();
    String older =
        receive("device", event(1, Instant.now().minusSeconds(60), Map.of("state", "ONLINE")), 201)
            .get("id")
            .asText();
    admin("/events/" + newer + "/apply", null, 200);
    assertThat(admin("/events/" + older + "/apply", null, 200).get("state").asText())
        .isEqualTo("IGNORED");
    assertThat(
            db.queryForObject(
                "select state from ops_camera where id=?", String.class, UUID.fromString(camera)))
        .isEqualTo("OFFLINE");
    String oldDevice =
        db.queryForObject("select id from int_device where principal='device'", UUID.class)
            .toString();
    admin("/devices/" + oldDevice + "/revoke", null, 200);
    device("CAMERA", "replacement-device", camera);
    String stale =
        receive(
                "replacement-device",
                event(1, Instant.now().minusSeconds(120), Map.of("state", "ONLINE")),
                201)
            .get("id")
            .asText();
    assertThat(admin("/events/" + stale + "/apply", null, 200).get("state").asText())
        .isEqualTo("IGNORED");
    assertThat(
            db.queryForObject(
                "select state from ops_camera where id=?", String.class, UUID.fromString(camera)))
        .isEqualTo("OFFLINE");
  }

  @Test
  void sensorOnlyCapturesBoundedTelemetry() throws Exception {
    String machine = machine();
    device("SENSOR", "device", machine);
    String id =
        receive("device", event(1, Instant.now(), Map.of("value", "210.5")), 201)
            .get("id")
            .asText();
    admin("/events/" + id + "/apply", null, 200);
    receive("device", event(2, Instant.now(), Map.of("value", "1E100")), 409);
    assertThat(
            db.queryForObject(
                "select state from mfg_machine where id=?", String.class, UUID.fromString(machine)))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void scaleRequiresVisitBindingAndExistingWeightRules() throws Exception {
    String d = device("SCALE", "device", null);
    Instant now = Instant.now();
    String v =
        call(
                "POST",
                "/api/v1/operations/truck-visits",
                "logistics",
                "LOGISTICS_MANAGER",
                Map.of(
                    "reference",
                    code(),
                    "carrier",
                    "Carrier",
                    "driver",
                    "Driver",
                    "tractorPlate",
                    code(),
                    "trailerPlate",
                    code(),
                    "purpose",
                    "DELIVERY",
                    "documentReference",
                    "DO-1",
                    "plannedStart",
                    now.toString(),
                    "plannedEnd",
                    now.plusSeconds(7200).toString()),
                201)
            .get("id")
            .asText();
    call(
        "POST",
        "/api/v1/operations/truck-visits/" + v + "/arrive",
        "gate",
        "GATE_OFFICER",
        Map.of("notes", "Arrived"),
        204);
    call(
        "POST",
        "/api/v1/operations/truck-visits/" + v + "/gate-check",
        "gate",
        "GATE_OFFICER",
        Map.of("accepted", true, "notes", "Checked"),
        204);
    String id =
        receive(
                "device",
                event(1, Instant.now(), Map.of("visitId", v, "kind", "TARE", "kg", "1000")),
                201)
            .get("id")
            .asText();
    assertThat(admin("/events/" + id + "/apply", null, 200).get("state").asText())
        .isEqualTo("FAILED");
    admin("/devices/" + d + "/visits", Map.of("visitId", v), 200);
    assertThat(admin("/events/" + id + "/apply", null, 200).get("state").asText())
        .isEqualTo("APPLIED");
    String duplicateTare =
        receive(
                "device",
                event(3, Instant.now(), Map.of("visitId", v, "kind", "TARE", "kg", "1000")),
                201)
            .get("id")
            .asText();
    var duplicateResult = admin("/events/" + duplicateTare + "/apply", null, 200);
    assertThat(duplicateResult.get("state").asText()).isEqualTo("FAILED");
    assertThat(duplicateResult.get("error_code").asText()).isEqualTo("DOMAIN_DATA_CONFLICT");
    String gross =
        receive(
                "device",
                event(2, Instant.now(), Map.of("visitId", v, "kind", "GROSS", "kg", "900")),
                201)
            .get("id")
            .asText();
    assertThat(admin("/events/" + gross + "/apply", null, 200).get("state").asText())
        .isEqualTo("FAILED");
    assertThat(
            db.queryForObject(
                "select count(*) from ops_weight_ticket where visit_id=?",
                Integer.class,
                UUID.fromString(v)))
        .isEqualTo(1);
  }

  @Test
  void portalRequestsAreScopedReplaySafeAndRequirePublication() throws Exception {
    partner("CUSTOMER", "customer", "C-1");
    partner("CUSTOMER", "customer2", "C-2");
    String p = product("Box", "PIECE");
    var r = portalRequest(p, "SALES_REQUEST", 5);
    portal("POST", "/requests", "customer", r, 404);
    admin("/published-products/" + p, null, 200);
    String id = portal("POST", "/requests", "customer", r, 201).get("id").asText();
    portal("POST", "/requests", "customer", r, 201);
    portal("GET", "/requests/" + id, "customer2", null, 404);
    portal("POST", "/requests", "customer2", r, 409);
    assertThat(portal("GET", "/overview", "customer2", null, 200).get("requests").size()).isZero();
    admin(
        "/portal-requests/" + id + "/decision",
        Map.of("accepted", true, "notes", "Factory will confirm price separately"),
        200);
    assertThat(portal("GET", "/requests/" + id, "customer", null, 200).get("state").asText())
        .isEqualTo("ACKNOWLEDGED");
    admin(
        "/portal-requests/" + id + "/decision",
        Map.of("accepted", false, "notes", "second decision"),
        409);
  }

  @Test
  void supplierCannotCreateCustomerSalesRequestAndRevocationIsImmediate() throws Exception {
    String partner = partner("SUPPLIER", "supplier", "S-1"), p = product("Box", "PIECE");
    admin("/published-products/" + p, null, 200);
    portal("POST", "/requests", "supplier", portalRequest(p, "SALES_REQUEST", 1), 409);
    portal("POST", "/requests", "supplier", portalRequest(p, "SUPPLY_OFFER", 1), 201);
    admin("/partners/" + partner + "/revoke", null, 200);
    portal("GET", "/overview", "supplier", null, 403);
  }

  @Test
  void portalInvoicesExcludeOtherPartiesDraftsAndOppositeKind() throws Exception {
    partner("CUSTOMER", "customer", "PARTY-1");
    partner("CUSTOMER", "customer2", "PARTY-2");
    UUID asset = UUID.randomUUID(),
        revenue = UUID.randomUUID(),
        period = UUID.randomUUID(),
        journal = UUID.randomUUID();
    db.update("insert into fin_account values(?,?,?,'ASSET')", asset, code(), "Control");
    db.update("insert into fin_account values(?,?,?,'REVENUE')", revenue, code(), "Sales");
    db.update(
        "insert into fin_period values(?,?,?,?,?,'OPEN')",
        period,
        code(),
        java.sql.Date.valueOf("2027-01-01"),
        java.sql.Date.valueOf("2027-02-01"),
        "USD");
    db.update(
        "insert into fin_journal values(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        journal,
        period,
        java.sql.Date.valueOf("2027-01-01"),
        code(),
        "MANUAL",
        UUID.randomUUID(),
        "Fixture",
        "accountant");
    for (String party : List.of("PARTY-1", "PARTY-2"))
      db.update(
          "insert into fin_invoice values(?,?,'SALE',?,?,'USD',100,0,100,?,?,?,null,?,'POSTED')",
          UUID.randomUUID(),
          code(),
          party,
          code(),
          java.sql.Date.valueOf("2027-01-31"),
          asset,
          revenue,
          journal);
    var view = portal("GET", "/overview", "customer", null, 200);
    assertThat(view.get("invoices").size()).isEqualTo(1);
    assertThat(view.get("invoices").get(0).has("control_account")).isFalse();
    call("GET", "/api/v1/finance/overview", "customer", "PORTAL_USER", null, 403);
  }

  @Test
  void reportBoundsAndSensitiveReportRolesAreEnforced() throws Exception {
    call(
        "GET",
        B + "/reports/production?from=2026-01-01&to=2026-06-01",
        "reports",
        "REPORT_VIEWER",
        null,
        409);
    call(
        "GET",
        B + "/reports/production?from=2026-01-01&to=2026-01-02",
        "customer",
        "PORTAL_USER",
        null,
        403);
    call(
        "GET",
        B + "/reports/financial/" + UUID.randomUUID(),
        "reports",
        "REPORT_VIEWER",
        null,
        403);
    call("GET", B + "/overview", "device", "DEVICE_AGENT", null, 403);
  }

  @Test
  void csvQuotesAndFormulaCellsAreEscaped() {
    var service = new ReportService(new IntegrationStore(db, json));
    String csv =
        service.csv(
            List.of(Map.of("name", "=HYPERLINK(\"http://bad\")", "value", "line\nnext")),
            List.of("name", "value"));
    assertThat(csv).contains("\"'=HYPERLINK(\"\"http://bad\"\")\"").contains("\"line\nnext\"");
  }

  @Test
  void disabledAiKeepsDraftLocalAndDoesNotClaimSuccess() throws Exception {
    String id =
        call(
                "POST",
                B + "/ai/jobs",
                "reports",
                "AI_ANALYST",
                Map.of("eventId", UUID.randomUUID(), "from", "2026-01-01", "to", "2026-01-02"),
                201)
            .get("id")
            .asText();
    call("POST", B + "/ai/jobs/" + id + "/run", "reports", "AI_ANALYST", null, 503);
    assertThat(
            db.queryForObject(
                "select state from int_ai_job where id=?", String.class, UUID.fromString(id)))
        .isEqualTo("QUEUED");
  }

  @Test
  void concurrentDuplicateDeviceReceiptHasOneRecord() throws Exception {
    device("ATTENDANCE", "device", null);
    var body = event(1, Instant.now(), Map.of("person", "x", "kind", "IN"));
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results =
          pool.invokeAll(
              List.<Callable<Integer>>of(() -> receiptStatus(body), () -> receiptStatus(body)));
      assertThat(results.get(0).get()).isEqualTo(201);
      assertThat(results.get(1).get()).isEqualTo(201);
    } finally {
      pool.shutdownNow();
    }
    assertThat(db.queryForObject("select count(*) from int_event", Integer.class)).isEqualTo(1);
  }

  @Test
  void outputReportKeepsDifferentProductsAndUnitsSeparate() throws Exception {
    for (String unit : List.of("PIECE", "KG")) {
      String p = product("Output " + unit, unit);
      UUID recipe = UUID.randomUUID(), plan = UUID.randomUUID(), order = UUID.randomUUID();
      db.update(
          "insert into mfg_recipe values(?,?,1,'INJECTION',1,'{}','bag',CURRENT_TIMESTAMP)",
          recipe,
          UUID.fromString(p));
      db.update("insert into mfg_inspection_plan values(?,?,1,'[]')", plan, UUID.fromString(p));
      db.update(
          "insert into mfg_order"
              + " (id,code,sales_reference,recipe_id,inspection_plan_id,planned_qty,good_qty,reject_qty,state)"
              + " values(?,?,?,?,?,10,10,1,'COMPLETED')",
          order,
          code(),
          "Demand",
          recipe,
          plan);
      db.update(
          "insert into mfg_output values(?,?,10,1,'Fixture',?)",
          UUID.randomUUID(),
          order,
          OffsetDateTime.parse("2026-10-01T10:00:00Z"));
    }
    var result =
        call(
            "GET",
            B + "/reports/production?from=2026-10-01&to=2026-10-02",
            "reports",
            "REPORT_VIEWER",
            null,
            200);
    assertThat(result.get("products").size()).isEqualTo(2);
    assertThat(result.get("output_events").asInt()).isEqualTo(2);
    assertThat(result.has("total_good_qty")).isFalse();
  }

  int receiptStatus(Object body) throws Exception {
    return api.perform(
            post(B + "/device-events")
                .with(user("device").roles("DEVICE_AGENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }
}
