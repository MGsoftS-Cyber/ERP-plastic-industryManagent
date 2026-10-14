package com.plastiflow.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
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
class OperationsApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  static final String B = "/api/v1/operations";

  String code() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  JsonNode call(String method, String path, String role, Object body, int expected)
      throws Exception {
    MockHttpServletRequestBuilder r =
        switch (method) {
          case "POST" -> post(path);
          case "PUT" -> put(path);
          default -> get(path);
        };
    r.with(user(role.toLowerCase(Locale.ROOT)).roles(role));
    if (body != null)
      r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var response = api.perform(r).andReturn().getResponse();
    assertThat(response.getStatus())
        .withFailMessage(
            "%s %s expected %s got %s: %s",
            method, path, expected, response.getStatus(), response.getContentAsString())
        .isEqualTo(expected);
    return response.getContentAsString().isBlank()
        ? json.createObjectNode()
        : json.readTree(response.getContentAsString());
  }

  String create(String resource, String role, Object body) throws Exception {
    return call("POST", B + resource, role, body, 201).get("id").asText();
  }

  String machine() throws Exception {
    return call(
            "POST",
            "/api/v1/manufacturing/machines",
            "PRODUCTION_MANAGER",
            Map.of(
                "code",
                "IM-" + code(),
                "name",
                "Machine",
                "process",
                "INJECTION",
                "capacityKg",
                1000),
            201)
        .get("id")
        .asText();
  }

  String work(String machine) throws Exception {
    return create(
        "/work-orders",
        "MAINTENANCE_MANAGER",
        Map.of(
            "code",
            "WO-" + code(),
            "machineId",
            machine,
            "kind",
            "BREAKDOWN",
            "description",
            "Hydraulic fault",
            "currency",
            "DZD"));
  }

  String assign(String work, String subject) throws Exception {
    String tech =
        create(
            "/technicians",
            "MAINTENANCE_MANAGER",
            Map.of("code", "TECH-" + code(), "name", "Technician", "userSubject", subject));
    call(
        "PUT",
        B + "/work-orders/" + work + "/assignment",
        "MAINTENANCE_MANAGER",
        Map.of("technicianId", tech),
        200);
    return tech;
  }

  void close(String work) throws Exception {
    assign(work, "tech-" + code());
    call("POST", B + "/work-orders/" + work + "/start", "MAINTENANCE_MANAGER", null, 204);
    call(
        "POST",
        B + "/work-orders/" + work + "/submit",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Repaired and tested"),
        204);
    call(
        "POST",
        B + "/work-orders/" + work + "/release",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Return to service approved"),
        204);
  }

  String truck() throws Exception {
    return create(
        "/truck-visits",
        "LOGISTICS_MANAGER",
        Map.of(
            "reference",
            "TRUCK-" + code(),
            "carrier",
            "Carrier",
            "driver",
            "Driver",
            "tractorPlate",
            code(),
            "trailerPlate",
            code(),
            "purpose",
            "COLLECTION",
            "documentReference",
            "EXTERNAL-SHIPMENT",
            "plannedStart",
            Instant.now().minusSeconds(60).toString(),
            "plannedEnd",
            Instant.now().plusSeconds(3600).toString()));
  }

  void arrive(String id) throws Exception {
    call(
        "POST",
        B + "/truck-visits/" + id + "/arrive",
        "GATE_OFFICER",
        Map.of("notes", "Arrived"),
        204);
    call(
        "POST",
        B + "/truck-visits/" + id + "/gate-check",
        "GATE_OFFICER",
        Map.of("accepted", true, "notes", "Documents and driver checked"),
        204);
  }

  String space(String kind) throws Exception {
    return create(
        "/yard/spaces", "LOGISTICS_MANAGER", Map.of("code", kind + "-" + code(), "kind", kind));
  }

  String zone() throws Exception {
    return create(
        "/zones", "SECURITY_MANAGER", Map.of("code", "ZONE-" + code(), "name", "Authorized zone"));
  }

  String visitor(String zone, Instant from, Instant to) throws Exception {
    return create(
        "/visitors",
        "SECURITY_MANAGER",
        Map.of(
            "reference",
            "VIS-" + code(),
            "name",
            "Visitor",
            "company",
            "Contractor",
            "hostReference",
            "Factory host",
            "validFrom",
            from.toString(),
            "validTo",
            to.toString(),
            "zoneIds",
            List.of(zone)));
  }

  String badge() throws Exception {
    String badge = "BADGE-" + code();
    call("POST", B + "/badges", "SECURITY_MANAGER", Map.of("code", badge), 204);
    return badge;
  }

  @Test
  void machineHoldNeedsApprovalAndCannotBeOverridden() throws Exception {
    String m = machine(), w = work(m);
    call(
        "PUT",
        "/api/v1/manufacturing/machines/" + m + "/state",
        "PRODUCTION_MANAGER",
        Map.of("state", "AVAILABLE"),
        409);
    call(
        "POST",
        B + "/work-orders/" + w + "/release",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Too early"),
        409);
    close(w);
    call(
        "PUT",
        "/api/v1/manufacturing/machines/" + m + "/state",
        "PRODUCTION_MANAGER",
        Map.of("state", "AVAILABLE"),
        204);
    assertThat(
            call("GET", B + "/work-orders/" + w, "MAINTENANCE_MANAGER", null, 200)
                .get("state")
                .asText())
        .isEqualTo("CLOSED");
  }

  @Test
  void multipleHoldsRequireAllWorkToClose() throws Exception {
    String m = machine(), a = work(m), b = work(m);
    close(a);
    call(
        "PUT",
        "/api/v1/manufacturing/machines/" + m + "/state",
        "PRODUCTION_MANAGER",
        Map.of("state", "AVAILABLE"),
        409);
    close(b);
    call(
        "PUT",
        "/api/v1/manufacturing/machines/" + m + "/state",
        "PRODUCTION_MANAGER",
        Map.of("state", "AVAILABLE"),
        204);
  }

  @Test
  void spareUsageAndLaborAreIdempotentAndCosted() throws Exception {
    String m = machine(), w = work(m);
    assign(w, "assigned-tech-" + code());
    call("POST", B + "/work-orders/" + w + "/start", "MAINTENANCE_MANAGER", null, 204);
    String part =
        call(
                "POST",
                "/api/v1/products",
                "CATALOG_MANAGER",
                Map.of(
                    "sku", "PART-" + code(), "name", "Seal", "kind", "SPARE_PART", "unit", "PIECE"),
                201)
            .get("id")
            .asText();
    var receipt =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "productId",
            part,
            "quantity",
            2,
            "unitCost",
            50,
            "currency",
            "DZD");
    call("POST", B + "/parts/receipts", "MAINTENANCE_MANAGER", receipt, 201);
    call("POST", B + "/parts/receipts", "MAINTENANCE_MANAGER", receipt, 201);
    var use =
        Map.of("eventId", UUID.randomUUID(), "productId", part, "quantity", 1, "unitCost", 50);
    call("POST", B + "/work-orders/" + w + "/parts", "MAINTENANCE_MANAGER", use, 201);
    call("POST", B + "/work-orders/" + w + "/parts", "MAINTENANCE_MANAGER", use, 201);
    var labor = Map.of("eventId", UUID.randomUUID(), "hours", 2, "hourlyRate", 100);
    call("POST", B + "/work-orders/" + w + "/labor", "MAINTENANCE_MANAGER", labor, 201);
    call("POST", B + "/work-orders/" + w + "/labor", "MAINTENANCE_MANAGER", labor, 201);
    assertThat(
            call("GET", B + "/work-orders/" + w, "MAINTENANCE_MANAGER", null, 200)
                .get("total_cost")
                .decimalValue())
        .isEqualByComparingTo("250");
    call(
        "POST",
        B + "/work-orders/" + w + "/parts",
        "MAINTENANCE_MANAGER",
        Map.of("eventId", UUID.randomUUID(), "productId", part, "quantity", 2, "unitCost", 50),
        409);
  }

  @Test
  void technicianCanExecuteOwnAssignmentOnly() throws Exception {
    String w = work(machine());
    assign(w, "maintenance_technician");
    call("POST", B + "/work-orders/" + w + "/start", "MAINTENANCE_TECHNICIAN", null, 204);
    String other = work(machine());
    assign(other, "different-tech-" + code());
    call("GET", B + "/work-orders/" + other, "MAINTENANCE_TECHNICIAN", null, 403);
    call("POST", B + "/work-orders/" + other + "/start", "MAINTENANCE_TECHNICIAN", null, 403);
    call(
        "POST",
        B + "/work-orders/" + w + "/release",
        "MAINTENANCE_TECHNICIAN",
        Map.of("notes", "Not permitted"),
        403);
  }

  @Test
  void preventivePlanGeneratesOnlyDueWork() throws Exception {
    String p =
        create(
            "/maintenance-plans",
            "MAINTENANCE_MANAGER",
            Map.of(
                "machineId",
                machine(),
                "intervalDays",
                30,
                "nextDue",
                LocalDate.now(ZoneOffset.UTC).toString(),
                "instructions",
                "Inspect seals",
                "currency",
                "DZD"));
    call("POST", B + "/maintenance-plans/" + p + "/generate", "MAINTENANCE_MANAGER", null, 200);
    call("POST", B + "/maintenance-plans/" + p + "/generate", "MAINTENANCE_MANAGER", null, 409);
  }

  @Test
  void facilityInspectionAndContractWorkflow() throws Exception {
    String
        area =
            create(
                "/areas",
                "FACILITIES_MANAGER",
                Map.of("code", "AREA-" + code(), "name", "Utilities")),
        asset =
            create(
                "/assets",
                "FACILITIES_MANAGER",
                Map.of(
                    "code",
                    "ASSET-" + code(),
                    "areaId",
                    area,
                    "name",
                    "Compressor",
                    "kind",
                    "COMPRESSED_AIR"));
    call(
        "POST",
        B + "/assets/" + asset + "/inspections",
        "FACILITIES_MANAGER",
        Map.of("passed", false, "notes", "Leak found"),
        201);
    call(
        "POST",
        B + "/assets/" + asset + "/inspections",
        "FACILITIES_MANAGER",
        Map.of("passed", true, "notes", "Inspection before repair, not a return-to-service check"),
        201);
    String work =
        create(
            "/work-orders",
            "MAINTENANCE_MANAGER",
            Map.of(
                "code",
                "WO-" + code(),
                "assetId",
                asset,
                "kind",
                "FACILITY",
                "description",
                "Repair compressor",
                "currency",
                "DZD"));
    assign(work, "facility-tech-" + code());
    call("POST", B + "/work-orders/" + work + "/start", "MAINTENANCE_MANAGER", null, 204);
    call(
        "POST",
        B + "/work-orders/" + work + "/submit",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Leak repaired"),
        204);
    call(
        "POST",
        B + "/work-orders/" + work + "/release",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Approve"),
        409);
    call(
        "POST",
        B + "/assets/" + asset + "/inspections",
        "FACILITIES_MANAGER",
        Map.of("passed", true, "notes", "Leak test passed"),
        201);
    call(
        "POST",
        B + "/work-orders/" + work + "/release",
        "MAINTENANCE_MANAGER",
        Map.of("notes", "Return approved"),
        204);
    create(
        "/service-contracts",
        "FACILITIES_MANAGER",
        Map.of(
            "assetId",
            asset,
            "supplierReference",
            "EXTERNAL-CONTRACT",
            "startsOn",
            LocalDate.now().toString(),
            "endsOn",
            LocalDate.now().plusYears(1).toString(),
            "amount",
            1000,
            "currency",
            "DZD"));
  }

  @Test
  void completeTruckFlowChecksWeightsDocumentsAndDeparture() throws Exception {
    String v = truck(), dock = space("DOCK");
    call(
        "PUT",
        B + "/truck-visits/" + v + "/booking",
        "LOGISTICS_MANAGER",
        Map.of(
            "spaceId",
            dock,
            "start",
            Instant.now().minusSeconds(60).toString(),
            "end",
            Instant.now().plusSeconds(3600).toString()),
        200);
    arrive(v);
    call(
        "PUT",
        B + "/truck-visits/" + v + "/space",
        "LOGISTICS_MANAGER",
        Map.of("spaceId", dock),
        204);
    call(
        "POST",
        B + "/truck-visits/" + v + "/handling",
        "LOGISTICS_MANAGER",
        Map.of("notes", "Loading"),
        204);
    call(
        "POST",
        B + "/truck-visits/" + v + "/depart",
        "GATE_OFFICER",
        Map.of("notes", "Too soon"),
        409);
    var tare =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "kind",
            "TARE",
            "kg",
            10000,
            "measuredAt",
            Instant.now().toString());
    call("POST", B + "/truck-visits/" + v + "/weights", "GATE_OFFICER", tare, 201);
    call("POST", B + "/truck-visits/" + v + "/weights", "GATE_OFFICER", tare, 201);
    call(
        "POST",
        B + "/truck-visits/" + v + "/finish",
        "LOGISTICS_MANAGER",
        Map.of("documentsChecked", true, "notes", "Checked"),
        409);
    call(
        "POST",
        B + "/truck-visits/" + v + "/weights",
        "GATE_OFFICER",
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "kind",
            "GROSS",
            "kg",
            9000,
            "measuredAt",
            Instant.now().toString()),
        409);
    call(
        "POST",
        B + "/truck-visits/" + v + "/weights",
        "GATE_OFFICER",
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "kind",
            "GROSS",
            "kg",
            12000,
            "measuredAt",
            Instant.now().toString()),
        201);
    call(
        "POST",
        B + "/truck-visits/" + v + "/finish",
        "LOGISTICS_MANAGER",
        Map.of("documentsChecked", false, "notes", "Missing docs"),
        409);
    call(
        "POST",
        B + "/truck-visits/" + v + "/finish",
        "LOGISTICS_MANAGER",
        Map.of("documentsChecked", true, "notes", "Checked"),
        204);
    call(
        "POST",
        B + "/truck-visits/" + v + "/depart",
        "GATE_OFFICER",
        Map.of("notes", "Departed"),
        204);
    var result = call("GET", B + "/truck-visits/" + v, "LOGISTICS_MANAGER", null, 200);
    assertThat(result.get("net_kg").decimalValue()).isEqualByComparingTo("2000");
    assertThat(result.get("state").asText()).isEqualTo("DEPARTED");
  }

  @Test
  void overlappingDockBookingsAreRejected() throws Exception {
    String a = truck(), b = truck(), dock = space("DOCK");
    var booking =
        Map.of(
            "spaceId",
            dock,
            "start",
            Instant.now().toString(),
            "end",
            Instant.now().plusSeconds(3600).toString());
    call("PUT", B + "/truck-visits/" + a + "/booking", "LOGISTICS_MANAGER", booking, 200);
    call("PUT", B + "/truck-visits/" + b + "/booking", "LOGISTICS_MANAGER", booking, 409);
    call(
        "POST",
        B + "/truck-visits/" + a + "/cancel",
        "LOGISTICS_MANAGER",
        Map.of("notes", "Cancelled"),
        204);
    call("PUT", B + "/truck-visits/" + b + "/booking", "LOGISTICS_MANAGER", booking, 200);
  }

  @Test
  void concurrentParkingAssignmentsHaveOneWinner() throws Exception {
    String a = truck(), b = truck(), bay = space("PARKING");
    arrive(a);
    arrive(b);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      var first = pool.submit(() -> assignStatus(a, bay, gate));
      var second = pool.submit(() -> assignStatus(b, bay, gate));
      gate.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(204, 409);
    } finally {
      pool.shutdownNow();
    }
  }

  int assignStatus(String visit, String bay, CountDownLatch gate) throws Exception {
    gate.await();
    return api.perform(
            put(B + "/truck-visits/" + visit + "/space")
                .with(user("logistics").roles("LOGISTICS_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("spaceId", bay))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void visitorBadgeZonesAndExitAreControlled() throws Exception {
    String zone = zone(),
        otherZone = zone(),
        badge = badge(),
        v = visitor(zone, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    call(
        "POST",
        B + "/visitors/" + v + "/check-in",
        "GATE_OFFICER",
        Map.of("badge", badge, "safetyBriefed", false),
        409);
    call(
        "POST",
        B + "/visitors/" + v + "/check-in",
        "GATE_OFFICER",
        Map.of("badge", badge, "safetyBriefed", true),
        204);
    String other = visitor(zone, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    call(
        "POST",
        B + "/visitors/" + other + "/check-in",
        "GATE_OFFICER",
        Map.of("badge", badge, "safetyBriefed", true),
        409);
    call(
        "POST",
        B + "/visitors/" + v + "/access",
        "GATE_OFFICER",
        Map.of("eventId", UUID.randomUUID(), "zoneId", otherZone, "direction", "ENTER"),
        409);
    var enter = Map.of("eventId", UUID.randomUUID(), "zoneId", zone, "direction", "ENTER");
    call("POST", B + "/visitors/" + v + "/access", "GATE_OFFICER", enter, 201);
    call("POST", B + "/visitors/" + v + "/access", "GATE_OFFICER", enter, 201);
    call(
        "POST",
        B + "/visitors/" + v + "/check-out",
        "GATE_OFFICER",
        Map.of("badgeReturned", true),
        409);
    call(
        "POST",
        B + "/visitors/" + v + "/access",
        "GATE_OFFICER",
        Map.of("eventId", UUID.randomUUID(), "zoneId", zone, "direction", "EXIT"),
        201);
    call(
        "POST",
        B + "/visitors/" + v + "/check-out",
        "GATE_OFFICER",
        Map.of("badgeReturned", true),
        204);
    call(
        "POST",
        B + "/visitors/" + other + "/check-in",
        "GATE_OFFICER",
        Map.of("badge", badge, "safetyBriefed", true),
        204);
  }

  @Test
  void expiredVisitsAndCrossDepartmentReadsAreBlocked() throws Exception {
    String v = visitor(zone(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));
    call(
        "POST",
        B + "/visitors/" + v + "/check-in",
        "GATE_OFFICER",
        Map.of("badge", badge(), "safetyBriefed", true),
        409);
    call("GET", B + "/security/overview", "LOGISTICS_MANAGER", null, 403);
    call("GET", B + "/yard/overview", "FACILITIES_MANAGER", null, 403);
    assertThat(api.perform(get(B + "/yard/overview")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  void camerasAndIncidentsKeepHistory() throws Exception {
    String zone = zone(),
        camera =
            create(
                "/cameras",
                "SECURITY_MANAGER",
                Map.of(
                    "code",
                    "CAM-" + code(),
                    "zoneId",
                    zone,
                    "name",
                    "Gate camera",
                    "vmsReference",
                    "VMS-CAMERA-01"));
    call(
        "PUT",
        B + "/cameras/" + camera + "/state",
        "SECURITY_MANAGER",
        Map.of("state", "ONLINE"),
        204);
    String incident =
        create(
            "/incidents",
            "GATE_OFFICER",
            Map.of(
                "zoneId", zone, "severity", "HIGH", "description", "Unauthorized access attempt"));
    call(
        "POST",
        B + "/incidents/" + incident + "/resolve",
        "GATE_OFFICER",
        Map.of("notes", "Not authorized"),
        403);
    call(
        "POST",
        B + "/incidents/" + incident + "/resolve",
        "SECURITY_MANAGER",
        Map.of("notes", "Investigated and resolved"),
        204);
    call(
        "POST",
        B + "/incidents/" + incident + "/resolve",
        "SECURITY_MANAGER",
        Map.of("notes", "Repeated"),
        409);
  }

  @Test
  void historicalSpareReceiptReplaySurvivesProductDeactivation() throws Exception {
    String part =
        call(
                "POST",
                "/api/v1/products",
                "CATALOG_MANAGER",
                Map.of("sku", code(), "name", "Spare", "kind", "SPARE_PART", "unit", "PIECE"),
                201)
            .get("id")
            .asText();
    var event =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "productId",
            part,
            "quantity",
            1,
            "unitCost",
            10,
            "currency",
            "USD");
    call("POST", B + "/parts/receipts", "MAINTENANCE_MANAGER", event, 201);
    db.update("update catalog_product set active=false where id=?", UUID.fromString(part));
    call("POST", B + "/parts/receipts", "MAINTENANCE_MANAGER", event, 201);
    var other = new HashMap<>(event);
    other.put("eventId", UUID.randomUUID());
    call("POST", B + "/parts/receipts", "MAINTENANCE_MANAGER", other, 409);
  }
}
