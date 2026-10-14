package com.plastiflow.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class ManufacturingApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  private static final String B = "/api/v1/manufacturing";

  private record Fixture(
      String material,
      String product,
      String machine,
      String mold,
      String employee,
      String shift,
      String recipe,
      String plan,
      String order,
      String lot) {}

  private String code() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private JsonNode call(String method, String path, String role, Object body, int expected)
      throws Exception {
    MockHttpServletRequestBuilder request =
        switch (method) {
          case "POST" -> post(path);
          case "PUT" -> put(path);
          default -> get(path);
        };
    request.with(user(role.toLowerCase(Locale.ROOT)).roles(role));
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var response = api.perform(request).andReturn().getResponse();
    assertThat(response.getStatus())
        .withFailMessage(
            "%s %s: expected %s, got %s: %s",
            method, path, expected, response.getStatus(), response.getContentAsString())
        .isEqualTo(expected);
    return response.getContentAsString().isBlank()
        ? json.createObjectNode()
        : json.readTree(response.getContentAsString());
  }

  private JsonNode create(String path, String role, Object body) throws Exception {
    return call("POST", B + path, role, body, 201);
  }

  private String id(JsonNode node) {
    return node.get("id").asText();
  }

  private Map<String, Object> schedule(Fixture f, Instant start, Instant end) {
    return Map.of(
        "machineId",
        f.machine,
        "moldId",
        f.mold,
        "employeeId",
        f.employee,
        "shiftId",
        f.shift,
        "start",
        start.toString(),
        "end",
        end.toString());
  }

  private Fixture fixture() throws Exception {
    return fixture(null, null);
  }

  private Fixture fixture(String existingMaterial, String existingProduct) throws Exception {
    String material =
        existingMaterial != null
            ? existingMaterial
            : id(
                call(
                    "POST",
                    "/api/v1/products",
                    "CATALOG_MANAGER",
                    Map.of(
                        "sku",
                        "MAT-" + code(),
                        "name",
                        "PP resin",
                        "kind",
                        "RAW_MATERIAL",
                        "unit",
                        "KG"),
                    201));
    String product =
        existingProduct != null
            ? existingProduct
            : id(
                call(
                    "POST",
                    "/api/v1/products",
                    "CATALOG_MANAGER",
                    Map.of(
                        "sku",
                        "FG-" + code(),
                        "name",
                        "Container",
                        "kind",
                        "FINISHED_GOOD",
                        "unit",
                        "PIECE"),
                    201));
    String machine =
        id(
            create(
                "/machines",
                "PRODUCTION_MANAGER",
                Map.of(
                    "code",
                    "IM-" + code(),
                    "name",
                    "Injection machine",
                    "process",
                    "INJECTION",
                    "capacityKg",
                    1000)));
    String mold =
        id(
            create(
                "/molds",
                "PRODUCTION_MANAGER",
                Map.of("code", "M-" + code(), "name", "Container mold", "cavities", 4)));
    call("PUT", B + "/machines/" + machine + "/molds/" + mold, "PRODUCTION_MANAGER", null, 204);
    String employee =
        id(
            create(
                "/employees",
                "WORKFORCE_MANAGER",
                Map.of(
                    "code", "EMP-" + code(), "name", "Operator", "userSubject", "op-" + code())));
    call(
        "PUT",
        B + "/employees/" + employee + "/qualifications",
        "WORKFORCE_MANAGER",
        Map.of(
            "machineId",
            machine,
            "validUntil",
            LocalDate.now(ZoneOffset.UTC).plusDays(30).toString()),
        204);
    Instant start = Instant.now().minusSeconds(300), end = Instant.now().plusSeconds(14400);
    String shift =
        id(
            create(
                "/shifts",
                "WORKFORCE_MANAGER",
                Map.of("employeeId", employee, "start", start.toString(), "end", end.toString())));
    String recipe = id(create("/recipes", "PRODUCTION_MANAGER", recipe(product, material)));
    String plan =
        id(
            create(
                "/inspection-plans",
                "QUALITY_MANAGER",
                Map.of(
                    "productId",
                    product,
                    "metrics",
                    List.of(Map.of("name", "weight", "unit", "g", "minimum", 9, "maximum", 11)))));
    String order =
        id(
            create(
                "/orders",
                "PRODUCTION_MANAGER",
                Map.of(
                    "code",
                    "PO-" + code(),
                    "salesReference",
                    "SO-EXTERNAL",
                    "recipeId",
                    recipe,
                    "inspectionPlanId",
                    plan,
                    "plannedQty",
                    100)));
    String lot =
        id(
            create(
                "/lots",
                "PRODUCTION_MANAGER",
                Map.of(
                    "code",
                    "LOT-" + code(),
                    "materialId",
                    material,
                    "supplierReference",
                    "SUPPLIER-LOT-ORIGINAL",
                    "location",
                    "RAW-A",
                    "receivedKg",
                    2)));
    return new Fixture(material, product, machine, mold, employee, shift, recipe, plan, order, lot);
  }

  private Map<String, Object> recipe(String product, String material) {
    return Map.of(
        "productId",
        product,
        "process",
        "INJECTION",
        "yieldFactor",
        0.9,
        "settings",
        Map.of("temperatureC", "210"),
        "packaging",
        "100 pieces per carton",
        "components",
        List.of(Map.of("materialId", material, "kgPerUnit", 0.01)),
        "operations",
        List.of(Map.of("name", "Molding", "minutesPerUnit", 0.01)));
  }

  private void start(Fixture f) throws Exception {
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600)),
        200);
    call("POST", B + "/orders/" + f.order + "/start", "PRODUCTION_MANAGER", null, 204);
    call(
        "PUT",
        B + "/lots/" + f.lot + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Supplier certificate and incoming check reviewed"),
        204);
  }

  private String batch(Fixture f) throws Exception {
    start(f);
    create(
        "/orders/" + f.order + "/consumptions",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 1.12));
    create(
        "/orders/" + f.order + "/outputs",
        "PRODUCTION_MANAGER",
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "goodQty",
            100,
            "rejectQty",
            3,
            "reason",
            "Startup scrap"));
    return id(
        call("POST", B + "/orders/" + f.order + "/complete", "PRODUCTION_MANAGER", null, 200));
  }

  @Test
  void completeTraceableManufacturingFlow() throws Exception {
    var f = fixture();
    var requirements =
        call("GET", B + "/orders/" + f.order, "PRODUCTION_MANAGER", null, 200).get("requirements");
    assertThat(requirements.get(0).get("required_kg").decimalValue())
        .isEqualByComparingTo("1.111112");
    String b = batch(f);
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Release"),
        409);
    create(
        "/batches/" + b + "/inspections",
        "QUALITY_MANAGER",
        Map.of("values", Map.of("weight", 10)));
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Inspection passed"),
        204);
    String pallet =
        id(
            create(
                "/pallets",
                "PRODUCTION_MANAGER",
                Map.of("code", "PAL-" + code(), "batchId", b, "qty", 100)));
    create(
        "/dispatch-references",
        "PRODUCTION_MANAGER",
        Map.of(
            "palletId",
            pallet,
            "shipmentReference",
            "SHIP-EXTERNAL",
            "customerReference",
            "CUSTOMER-EXTERNAL"));
    JsonNode trace = call("GET", B + "/batches/" + b + "/trace", "PRODUCTION_MANAGER", null, 200);
    assertThat(trace.get("source_lots").get(0).get("supplier_reference").asText())
        .isEqualTo("SUPPLIER-LOT-ORIGINAL");
    assertThat(trace.get("dispatch_references").get(0).get("shipment_reference").asText())
        .isEqualTo("SHIP-EXTERNAL");
    assertThat(
            call("GET", B + "/lots/" + f.lot + "/trace", "PRODUCTION_MANAGER", null, 200)
                .get("downstream_batches")
                .size())
        .isEqualTo(1);
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "QUARANTINED", "reason", "Need recall workflow"),
        409);
  }

  @Test
  void repeatedConsumptionAndOutputDoNotDuplicate() throws Exception {
    var f = fixture();
    start(f);
    var consumption = Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 1);
    create("/orders/" + f.order + "/consumptions", "PRODUCTION_MANAGER", consumption);
    create("/orders/" + f.order + "/consumptions", "PRODUCTION_MANAGER", consumption);
    var output =
        Map.of("eventId", UUID.randomUUID(), "goodQty", 90, "rejectQty", 2, "reason", "Run");
    create("/orders/" + f.order + "/outputs", "PRODUCTION_MANAGER", output);
    create("/orders/" + f.order + "/outputs", "PRODUCTION_MANAGER", output);
    JsonNode detail = call("GET", B + "/orders/" + f.order, "PRODUCTION_MANAGER", null, 200);
    assertThat(detail.get("consumptions").size()).isEqualTo(1);
    assertThat(detail.get("requirements").get(0).get("remaining_required_kg").decimalValue())
        .isEqualByComparingTo("0.111112");
    assertThat(detail.get("requirements").get(0).get("shortage_kg").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(detail.get("good_qty").decimalValue()).isEqualByComparingTo("90");
    call(
        "POST",
        B + "/orders/" + f.order + "/outputs",
        "PRODUCTION_MANAGER",
        Map.of("eventId", output.get("eventId"), "goodQty", 89, "rejectQty", 2, "reason", "Run"),
        409);
  }

  @Test
  void pendingLotsAndInsufficientStockAreBlocked() throws Exception {
    var f = fixture();
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, Instant.now().minusSeconds(120), Instant.now().plusSeconds(3600)),
        200);
    call("POST", B + "/orders/" + f.order + "/start", "PRODUCTION_MANAGER", null, 204);
    call(
        "POST",
        B + "/orders/" + f.order + "/consumptions",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 1),
        409);
    call(
        "PUT",
        B + "/lots/" + f.lot + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Checked"),
        204);
    call(
        "POST",
        B + "/orders/" + f.order + "/consumptions",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 3),
        409);
  }

  @Test
  void failedInspectionNeedsCorrectionAndReinspection() throws Exception {
    var f = fixture();
    String b = batch(f);
    var failed =
        create(
            "/batches/" + b + "/inspections",
            "QUALITY_MANAGER",
            Map.of("values", Map.of("weight", 12)));
    assertThat(failed.get("passed").asBoolean()).isFalse();
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Try release"),
        409);
    create(
        "/batches/" + b + "/inspections",
        "QUALITY_MANAGER",
        Map.of("values", Map.of("weight", 10)));
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Try release"),
        409);
    JsonNode all = call("GET", B + "/overview", "QUALITY_MANAGER", null, 200);
    String nc = "";
    for (var n : all.get("nonconformities")) if (n.get("batch_id").asText().equals(b)) nc = id(n);
    call(
        "PUT",
        B + "/nonconformities/" + nc + "/resolve",
        "QUALITY_MANAGER",
        Map.of("reason", "Segregated defective output and verified accepted quantity"),
        204);
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Corrected and retested"),
        204);
  }

  @Test
  void recipeVersionsDoNotChangeExistingOrders() throws Exception {
    var f = fixture();
    var next = create("/recipes", "PRODUCTION_MANAGER", recipe(f.product, f.material));
    assertThat(next.get("revision").asInt()).isEqualTo(2);
    assertThat(
            call("GET", B + "/orders/" + f.order, "PRODUCTION_MANAGER", null, 200)
                .get("recipe_id")
                .asText())
        .isEqualTo(f.recipe);
  }

  @Test
  void overlappingScheduleAndMaintenanceAreBlocked() throws Exception {
    var f = fixture();
    Instant start = Instant.now().minusSeconds(60), end = Instant.now().plusSeconds(3600);
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, start, end),
        200);
    String other =
        id(
            create(
                "/orders",
                "PRODUCTION_MANAGER",
                Map.of(
                    "code",
                    "PO-" + code(),
                    "salesReference",
                    "SO-OTHER",
                    "recipeId",
                    f.recipe,
                    "inspectionPlanId",
                    f.plan,
                    "plannedQty",
                    100)));
    call(
        "PUT",
        B + "/orders/" + other + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, start, end),
        409);
    call(
        "POST",
        B + "/maintenance-blocks",
        "PRODUCTION_MANAGER",
        Map.of(
            "machineId",
            f.machine,
            "start",
            start.toString(),
            "end",
            end.toString(),
            "reason",
            "Service"),
        409);
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, end.plusSeconds(60), end.plusSeconds(3660)),
        200);
    create(
        "/maintenance-blocks",
        "PRODUCTION_MANAGER",
        Map.of(
            "machineId",
            f.machine,
            "start",
            start.toString(),
            "end",
            end.toString(),
            "reason",
            "Service"));
    call(
        "PUT",
        B + "/orders/" + other + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, start, end),
        409);
  }

  @Test
  void missingCompatibilityAndExpiredQualificationBlockScheduling() throws Exception {
    var f = fixture();
    String wrong =
        id(
            create(
                "/molds",
                "PRODUCTION_MANAGER",
                Map.of("code", "BAD-" + code(), "name", "Other mold", "cavities", 2)));
    var request =
        new HashMap<>(schedule(f, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)));
    request.put("moldId", wrong);
    call("PUT", B + "/orders/" + f.order + "/schedule", "PRODUCTION_MANAGER", request, 409);
    call(
        "PUT",
        B + "/employees/" + f.employee + "/qualifications",
        "WORKFORCE_MANAGER",
        Map.of(
            "machineId",
            f.machine,
            "validUntil",
            LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()),
        204);
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
        409);
  }

  @Test
  void qualityHoldAndPalletOverAllocationBlockDispatch() throws Exception {
    var f = fixture();
    String b = batch(f);
    call(
        "POST",
        B + "/pallets",
        "PRODUCTION_MANAGER",
        Map.of("code", "PAL-" + code(), "batchId", b, "qty", 100),
        409);
    create(
        "/batches/" + b + "/inspections",
        "QUALITY_MANAGER",
        Map.of("values", Map.of("weight", 10)));
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "APPROVED", "reason", "Passed"),
        204);
    String pallet =
        id(
            create(
                "/pallets",
                "PRODUCTION_MANAGER",
                Map.of("code", "PAL-" + code(), "batchId", b, "qty", 100)));
    call(
        "POST",
        B + "/pallets",
        "PRODUCTION_MANAGER",
        Map.of("code", "PAL-" + code(), "batchId", b, "qty", 1),
        409);
    call(
        "PUT",
        B + "/batches/" + b + "/quality",
        "QUALITY_MANAGER",
        Map.of("quality", "QUARANTINED", "reason", "Investigation"),
        204);
    call(
        "POST",
        B + "/dispatch-references",
        "PRODUCTION_MANAGER",
        Map.of("palletId", pallet, "shipmentReference", "SHIP", "customerReference", "CUSTOMER"),
        409);
  }

  @Test
  void fractionalPiecesAndInvalidExecutionStateAreRejected() throws Exception {
    var f = fixture();
    call(
        "POST",
        B + "/orders",
        "PRODUCTION_MANAGER",
        Map.of(
            "code",
            "PO-" + code(),
            "salesReference",
            "SO",
            "recipeId",
            f.recipe,
            "inspectionPlanId",
            f.plan,
            "plannedQty",
            1.5),
        409);
    call(
        "POST",
        B + "/orders/" + f.order + "/outputs",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "goodQty", 1, "rejectQty", 0, "reason", "Too early"),
        409);
    start(f);
    call(
        "POST",
        B + "/orders/" + f.order + "/outputs",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "goodQty", 1.5, "rejectQty", 0, "reason", "Fraction"),
        409);
    call("POST", B + "/orders/" + f.order + "/complete", "PRODUCTION_MANAGER", null, 409);
  }

  @Test
  void rolesAndOperatorAssignmentsAreEnforced() throws Exception {
    assertThat(api.perform(get(B + "/overview")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    call("GET", B + "/overview", "CATALOG_READER", null, 403);
    call(
        "POST",
        B + "/machines",
        "WORKFORCE_MANAGER",
        Map.of("code", "MACHINE", "name", "Machine", "process", "INJECTION", "capacityKg", 100),
        403);
    var f = fixture();
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
        200);
    call("POST", B + "/orders/" + f.order + "/start", "OPERATOR", null, 403);
  }

  @Test
  void concurrentConsumptionCannotOverdrawSharedLot() throws Exception {
    var a = fixture();
    var b = fixture(a.material, a.product);
    start(a);
    start(b);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      var first = executor.submit(() -> consumeStatus(a.order, a.lot, gate));
      var second = executor.submit(() -> consumeStatus(b.order, a.lot, gate));
      gate.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    } finally {
      executor.shutdownNow();
    }
    assertThat(
            call("GET", B + "/lots/" + a.lot + "/trace", "PRODUCTION_MANAGER", null, 200)
                .get("lot")
                .get("remaining_kg")
                .decimalValue())
        .isEqualByComparingTo("0.5");
  }

  private int consumeStatus(String order, String lot, CountDownLatch gate) throws Exception {
    gate.await();
    return api.perform(
            post(B + "/orders/" + order + "/consumptions")
                .with(user("production").roles("PRODUCTION_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("eventId", UUID.randomUUID(), "lotId", lot, "kg", 1.5))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void inactiveEmployeeCannotStartAlreadyScheduledOrder() throws Exception {
    Fixture f = fixture();
    Instant now = Instant.now();
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, now.minusSeconds(60), now.plusSeconds(3600)),
        200);
    db.update("update mfg_employee set active=false where id=?", UUID.fromString(f.employee));
    var result = call("POST", B + "/orders/" + f.order + "/start", "PRODUCTION_MANAGER", null, 409);
    assertThat(result.get("code").asText()).isEqualTo("EMPLOYEE_INACTIVE");
  }

  @Test
  void inactiveEmployeeCannotReceiveNewShift() throws Exception {
    Fixture f = fixture();
    db.update("update mfg_employee set active=false where id=?", UUID.fromString(f.employee));
    call(
        "POST",
        B + "/shifts",
        "WORKFORCE_MANAGER",
        Map.of(
            "employeeId",
            f.employee,
            "start",
            Instant.now().plusSeconds(86400).toString(),
            "end",
            Instant.now().plusSeconds(90000).toString()),
        409);
  }

  @Test
  void abortRetainsConsumptionAndAllowsStoppedMachineRepair() throws Exception {
    var f = fixture();
    start(f);
    create(
        "/orders/" + f.order + "/consumptions",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 1));
    var remaining =
        db.queryForObject(
            "select remaining_kg from mfg_lot where id=?",
            java.math.BigDecimal.class,
            UUID.fromString(f.lot));
    String ops = "/api/v1/operations";
    String work =
        id(
            call(
                "POST",
                ops + "/work-orders",
                "MAINTENANCE_MANAGER",
                Map.of(
                    "code",
                    "WO-" + code(),
                    "machineId",
                    f.machine,
                    "kind",
                    "BREAKDOWN",
                    "description",
                    "Machine failed without good output",
                    "currency",
                    "DZD"),
                201));
    String tech =
        id(
            call(
                "POST",
                ops + "/technicians",
                "MAINTENANCE_MANAGER",
                Map.of(
                    "code",
                    "TECH-" + code(),
                    "name",
                    "Technician",
                    "userSubject",
                    "tech-" + code()),
                201));
    call(
        "PUT",
        ops + "/work-orders/" + work + "/assignment",
        "MAINTENANCE_MANAGER",
        Map.of("technicianId", tech),
        200);
    assertThat(
            call("POST", ops + "/work-orders/" + work + "/start", "MAINTENANCE_MANAGER", null, 409)
                .get("code")
                .asText())
        .isEqualTo("PRODUCTION_RUNNING");
    call(
        "POST",
        B + "/orders/" + f.order + "/abort",
        "OPERATOR",
        Map.of("reason", "Breakdown"),
        403);
    call(
        "POST",
        B + "/orders/" + f.order + "/abort",
        "PRODUCTION_MANAGER",
        Map.of("reason", "Breakdown; retain consumed stock for reconciliation"),
        204);
    var order = call("GET", B + "/orders/" + f.order, "PRODUCTION_MANAGER", null, 200);
    assertThat(order.get("state").asText()).isEqualTo("ABORTED");
    assertThat(order.get("requirements").get(0).get("remaining_required_kg").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(
            db.queryForObject(
                "select remaining_kg from mfg_lot where id=?",
                java.math.BigDecimal.class,
                UUID.fromString(f.lot)))
        .isEqualByComparingTo(remaining);
    var trace = call("GET", B + "/lots/" + f.lot + "/trace", "PRODUCTION_MANAGER", null, 200);
    assertThat(trace.get("consumption_orders").get(0).get("state").asText()).isEqualTo("ABORTED");
    call(
        "POST",
        B + "/orders/" + f.order + "/consumptions",
        "PRODUCTION_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "lotId", f.lot, "kg", 0.1),
        409);
    call("POST", ops + "/work-orders/" + work + "/start", "MAINTENANCE_MANAGER", null, 204);
  }

  @Test
  void abortFreesScheduleAndCancelledWindowKeepsHistoryWithoutBlocking() throws Exception {
    var f = fixture();
    Instant begin = Instant.now().minusSeconds(60), end = Instant.now().plusSeconds(3600);
    call(
        "PUT",
        B + "/orders/" + f.order + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, begin, end),
        200);
    call(
        "POST",
        B + "/orders/" + f.order + "/abort",
        "PRODUCTION_MANAGER",
        Map.of("reason", "Demand cancelled"),
        204);
    String other =
        id(
            create(
                "/orders",
                "PRODUCTION_MANAGER",
                Map.of(
                    "code",
                    "PO-" + code(),
                    "salesReference",
                    "SO-OTHER",
                    "recipeId",
                    f.recipe,
                    "inspectionPlanId",
                    f.plan,
                    "plannedQty",
                    100)));
    String window =
        id(
            create(
                "/maintenance-blocks",
                "PRODUCTION_MANAGER",
                Map.of(
                    "machineId",
                    f.machine,
                    "start",
                    begin.toString(),
                    "end",
                    end.toString(),
                    "reason",
                    "Planned service")));
    call(
        "PUT",
        B + "/orders/" + other + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, begin, end),
        409);
    call(
        "POST",
        B + "/maintenance-blocks/" + window + "/cancel",
        "PRODUCTION_MANAGER",
        Map.of("reason", "Service postponed"),
        204);
    assertThat(
            db.queryForObject(
                "select cancelled from mfg_maintenance_block where id=?",
                Boolean.class,
                UUID.fromString(window)))
        .isTrue();
    call(
        "PUT",
        B + "/orders/" + other + "/schedule",
        "PRODUCTION_MANAGER",
        schedule(f, begin, end),
        200);
  }

  @Test
  void goodOutputCannotBeDiscardedThroughAbort() throws Exception {
    var f = fixture();
    start(f);
    create(
        "/orders/" + f.order + "/outputs",
        "PRODUCTION_MANAGER",
        Map.of(
            "eventId", UUID.randomUUID(), "goodQty", 1, "rejectQty", 0, "reason", "Trial output"));
    assertThat(
            call(
                    "POST",
                    B + "/orders/" + f.order + "/abort",
                    "PRODUCTION_MANAGER",
                    Map.of("reason", "Discard output"),
                    409)
                .get("code")
                .asText())
        .isEqualTo("GOOD_OUTPUT_PRESENT");
  }
}
