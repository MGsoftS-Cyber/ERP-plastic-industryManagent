package com.plastiflow.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class PayrollApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  static final String B = "/api/v1/payroll";

  @BeforeEach
  void clean() {
    for (String t :
        List.of(
            "wf_audit",
            "wf_pay_line",
            "wf_adjustment",
            "wf_period_employee",
            "wf_period",
            "wf_clock_event",
            "wf_attendance",
            "wf_absence",
            "wf_wage",
            "wf_rule")) db.update("delete from " + t);
  }

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

  String create(String path, String role, Object body) throws Exception {
    return call("POST", B + path, role, body, 201).get("id").asText();
  }

  String employee() throws Exception {
    return call(
            "POST",
            "/api/v1/manufacturing/employees",
            "WORKFORCE_MANAGER",
            Map.of(
                "code",
                "EMP-" + code(),
                "name",
                "Payroll employee",
                "userSubject",
                "worker-" + code()),
            201)
        .get("id")
        .asText();
  }

  void wage(String employee, String date, String basis, int amount, int monthly) throws Exception {
    create(
        "/wages",
        "PAYROLL_MANAGER",
        Map.of(
            "employeeId",
            employee,
            "effectiveFrom",
            date,
            "basis",
            basis,
            "amount",
            amount,
            "currency",
            "DZD",
            "monthlyMinutes",
            monthly));
  }

  void rule(String date, String type, int value, String stacking) throws Exception {
    create(
        "/rules",
        "PAYROLL_MANAGER",
        Map.of(
            "effectiveFrom",
            date,
            "nightStart",
            "22:00",
            "nightEnd",
            "06:00",
            "nightType",
            type,
            "nightValue",
            value,
            "overtimePercent",
            50,
            "stacking",
            stacking));
  }

  Map<String, Object> attendance(String employee, String a, String b, List<?> breaks) {
    return Map.of(
        "employeeId",
        employee,
        "start",
        a,
        "end",
        b,
        "unpaidBreaks",
        breaks,
        "rawEventIds",
        List.of(),
        "notes",
        "Validated clock records");
  }

  String approved(String employee, String a, String b, List<?> breaks, int ot) throws Exception {
    String id = create("/attendance", "WORKFORCE_MANAGER", attendance(employee, a, b, breaks));
    call(
        "POST",
        B + "/attendance/" + id + "/approve",
        "WORKFORCE_MANAGER",
        Map.of("overtimeMinutes", ot, "notes", "Hours approved"),
        204);
    return id;
  }

  String period(String employee, String from, String to, String zone) throws Exception {
    return create(
        "/periods",
        "PAYROLL_MANAGER",
        Map.of(
            "code",
            "PAY-" + code(),
            "start",
            from,
            "end",
            to,
            "zoneId",
            zone,
            "currency",
            "DZD",
            "moneyScale",
            2,
            "employeeIds",
            List.of(employee)));
  }

  JsonNode calculate(String period) throws Exception {
    return call("POST", B + "/periods/" + period + "/calculate", "PAYROLL_MANAGER", null, 200)
        .get("lines")
        .get(0);
  }

  @Test
  void mixedDayNightOvertimeAndUnpaidBreakAreCalculated() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    approved(
        e,
        "2026-01-05T20:00:00Z",
        "2026-01-06T04:00:00Z",
        List.of(Map.of("start", "2026-01-05T23:00:00Z", "end", "2026-01-05T23:30:00Z")),
        60);
    JsonNode line = calculate(period(e, "2026-01-05", "2026-01-07", "UTC"));
    assertThat(line.get("worked_minutes").asInt()).isEqualTo(450);
    assertThat(line.get("night_minutes").asInt()).isEqualTo(330);
    assertThat(line.get("base_pay").decimalValue()).isEqualByComparingTo("450");
    assertThat(line.get("night_pay").decimalValue()).isEqualByComparingTo("82.5");
    assertThat(line.get("overtime_pay").decimalValue()).isEqualByComparingTo("30");
    assertThat(line.get("gross").decimalValue()).isEqualByComparingTo("562.5");
  }

  @Test
  void multiplicativePremiumAndFixedNightRateWork() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "FIXED", 15, "MULTIPLICATIVE");
    approved(e, "2026-01-05T22:00:00Z", "2026-01-05T23:00:00Z", List.of(), 60);
    JsonNode line = calculate(period(e, "2026-01-05", "2026-01-06", "UTC"));
    assertThat(line.get("gross").decimalValue()).isEqualByComparingTo("112.5");
  }

  @Test
  void rulesAndWagesChangeAtLocalEffectiveDate() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    wage(e, "2026-01-06", "HOURLY", 120, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    rule("2026-01-06", "PERCENT", 50, "ADDITIVE");
    approved(e, "2026-01-05T23:00:00Z", "2026-01-06T01:00:00Z", List.of(), 0);
    JsonNode line = calculate(period(e, "2026-01-05", "2026-01-07", "UTC"));
    assertThat(line.get("gross").decimalValue()).isEqualByComparingTo("255");
    assertThat(json.readTree(line.get("snapshot").asText()).get("segments").size()).isEqualTo(2);
  }

  @Test
  void monthlySalaryIsProratedAndPaidLeaveHasNoPremium() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "MONTHLY", 9600, 9600);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    String absence =
        create(
            "/absences",
            "WORKFORCE_MANAGER",
            Map.of(
                "employeeId",
                e,
                "start",
                "2026-01-05T22:00:00Z",
                "end",
                "2026-01-06T06:00:00Z",
                "kind",
                "PAID_LEAVE",
                "reason",
                "Approved leave"));
    call(
        "POST",
        B + "/absences/" + absence + "/approve",
        "WORKFORCE_MANAGER",
        Map.of("notes", "Approved"),
        204);
    JsonNode line = calculate(period(e, "2026-01-05", "2026-01-07", "UTC"));
    assertThat(line.get("paid_leave_minutes").asInt()).isEqualTo(480);
    assertThat(line.get("gross").decimalValue()).isEqualByComparingTo("480");
    assertThat(line.get("night_pay").decimalValue()).isEqualByComparingTo("0");
  }

  @Test
  void attendanceOverlapAndBadBreaksAreRejected() throws Exception {
    String e = employee();
    create(
        "/attendance",
        "WORKFORCE_MANAGER",
        attendance(e, "2026-01-05T08:00:00Z", "2026-01-05T16:00:00Z", List.of()));
    call(
        "POST",
        B + "/attendance",
        "WORKFORCE_MANAGER",
        attendance(e, "2026-01-05T15:00:00Z", "2026-01-05T17:00:00Z", List.of()),
        409);
    call(
        "POST",
        B + "/attendance",
        "WORKFORCE_MANAGER",
        attendance(
            e,
            "2026-01-06T08:00:00Z",
            "2026-01-06T16:00:00Z",
            List.of(Map.of("start", "2026-01-06T07:00:00Z", "end", "2026-01-06T09:00:00Z"))),
        409);
  }

  @Test
  void correctionsPreserveOriginalAndInvalidatePayrollReview() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    String a = approved(e, "2026-01-05T08:00:00Z", "2026-01-05T16:00:00Z", List.of(), 0),
        p = period(e, "2026-01-05", "2026-01-06", "UTC");
    calculate(p);
    call(
        "POST",
        B + "/periods/" + p + "/review",
        "PAYROLL_APPROVER",
        Map.of("notes", "Reviewed"),
        204);
    create(
        "/attendance/" + a + "/corrections",
        "WORKFORCE_MANAGER",
        attendance(e, "2026-01-05T08:00:00Z", "2026-01-05T15:00:00Z", List.of()));
    assertThat(
            db.queryForObject(
                "select state from wf_attendance where id=?", String.class, UUID.fromString(a)))
        .isEqualTo("SUPERSEDED");
    assertThat(call("GET", B + "/periods/" + p, "PAYROLL_MANAGER", null, 200).get("state").asText())
        .isEqualTo("DIRTY");
    call(
        "POST",
        B + "/periods/" + p + "/finalize",
        "PAYROLL_MANAGER",
        Map.of("notes", "Must recalculate"),
        409);
  }

  @Test
  void finalizedPeriodBlocksInputsAndRuleBackdating() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    String a = approved(e, "2026-01-05T08:00:00Z", "2026-01-05T16:00:00Z", List.of(), 0),
        p = period(e, "2026-01-05", "2026-01-06", "UTC");
    calculate(p);
    call(
        "POST",
        B + "/periods/" + p + "/review",
        "PAYROLL_APPROVER",
        Map.of("notes", "Approved"),
        204);
    call(
        "POST",
        B + "/periods/" + p + "/finalize",
        "PAYROLL_MANAGER",
        Map.of("notes", "Finalized"),
        204);
    call(
        "POST",
        B + "/attendance/" + a + "/corrections",
        "WORKFORCE_MANAGER",
        attendance(e, "2026-01-05T08:00:00Z", "2026-01-05T15:00:00Z", List.of()),
        409);
    call(
        "POST",
        B + "/rules",
        "PAYROLL_MANAGER",
        Map.of(
            "effectiveFrom",
            "2026-01-04",
            "nightStart",
            "22:00",
            "nightEnd",
            "06:00",
            "nightType",
            "PERCENT",
            "nightValue",
            30,
            "overtimePercent",
            50,
            "stacking",
            "ADDITIVE"),
        409);
    call("POST", B + "/periods/" + p + "/calculate", "PAYROLL_MANAGER", null, 409);
  }

  @Test
  void approvedBonusesAndDeductionsRequireReview() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    approved(e, "2026-01-05T08:00:00Z", "2026-01-05T09:00:00Z", List.of(), 0);
    String p = period(e, "2026-01-05", "2026-01-06", "UTC"),
        bonus =
            create(
                "/periods/" + p + "/adjustments",
                "PAYROLL_MANAGER",
                Map.of(
                    "employeeId", e, "kind", "BONUS", "amount", 20, "reason", "Performance bonus")),
        deduct =
            create(
                "/periods/" + p + "/adjustments",
                "PAYROLL_MANAGER",
                Map.of(
                    "employeeId",
                    e,
                    "kind",
                    "DEDUCTION",
                    "amount",
                    5,
                    "reason",
                    "Entered deduction"));
    call("POST", B + "/periods/" + p + "/calculate", "PAYROLL_MANAGER", null, 409);
    for (String id : List.of(bonus, deduct))
      call(
          "POST",
          B + "/adjustments/" + id + "/decision",
          "PAYROLL_APPROVER",
          Map.of("approved", true, "notes", "Reviewed"),
          204);
    assertThat(calculate(p).get("payable").decimalValue()).isEqualByComparingTo("75");
  }

  @Test
  void rawEventsReplayAndSensitiveDataIsRestricted() throws Exception {
    String e = employee();
    var clock =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "employeeId",
            e,
            "kind",
            "IN",
            "occurredAt",
            "2026-01-05T08:00:00Z",
            "source",
            "Manual terminal import");
    call("POST", B + "/clock-events", "WORKFORCE_MANAGER", clock, 201);
    call("POST", B + "/clock-events", "WORKFORCE_MANAGER", clock, 201);
    assertThat(db.queryForObject("select count(*) from wf_clock_event", Integer.class))
        .isEqualTo(1);
    call("GET", B + "/overview", "WORKFORCE_MANAGER", null, 403);
    call("GET", B + "/attendance-overview", "PAYROLL_MANAGER", null, 403);
    assertThat(api.perform(get(B + "/overview")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  void daylightSavingUsesActualElapsedMinutes() throws Exception {
    String e = employee();
    wage(e, "2026-01-01", "HOURLY", 60, 0);
    rule("2026-01-01", "PERCENT", 25, "ADDITIVE");
    approved(e, "2026-03-29T00:00:00Z", "2026-03-29T04:00:00Z", List.of(), 0);
    JsonNode line = calculate(period(e, "2026-03-29", "2026-03-30", "Europe/Paris"));
    assertThat(line.get("worked_minutes").asInt()).isEqualTo(240);
    assertThat(line.get("night_minutes").asInt()).isEqualTo(240);
  }

  @Test
  void historicalClockReplaySurvivesEmployeeDeactivation() throws Exception {
    String employee = employee();
    var event =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "employeeId",
            employee,
            "kind",
            "IN",
            "occurredAt",
            "2026-01-01T08:00:00Z",
            "source",
            "reader");
    call("POST", B + "/clock-events", "WORKFORCE_MANAGER", event, 201);
    db.update("update mfg_employee set active=false where id=?", UUID.fromString(employee));
    call("POST", B + "/clock-events", "WORKFORCE_MANAGER", event, 201);
    var other = new HashMap<>(event);
    other.put("eventId", UUID.randomUUID());
    call("POST", B + "/clock-events", "WORKFORCE_MANAGER", other, 409);
  }

  @Test
  void combinedPayrollRolesCannotApproveOwnAdjustmentsAndUnknownAuthorsCannotBeApproved()
      throws Exception {
    String employee = employee(), period = period(employee, "2026-01-05", "2026-01-06", "UTC");
    for (String kind : List.of("BONUS", "DEDUCTION")) {
      String adjustment =
          create(
              "/periods/" + period + "/adjustments",
              "PAYROLL_MANAGER",
              Map.of(
                  "employeeId", employee, "kind", kind, "amount", 10, "reason", "Manager entry"));
      var response =
          api.perform(
                  post(B + "/adjustments/" + adjustment + "/decision")
                      .with(user("payroll_manager").roles("PAYROLL_MANAGER", "PAYROLL_APPROVER"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(
                              Map.of("approved", true, "notes", "Self review"))))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(json.readTree(response.getContentAsString()).get("code").asText())
          .isEqualTo("SEPARATE_APPROVER");
      call(
          "POST",
          B + "/adjustments/" + adjustment + "/decision",
          "PAYROLL_APPROVER",
          Map.of("approved", true, "notes", "Independent review"),
          204);
    }
    String legacy =
        create(
            "/periods/" + period + "/adjustments",
            "PAYROLL_MANAGER",
            Map.of(
                "employeeId",
                employee,
                "kind",
                "BONUS",
                "amount",
                10,
                "reason",
                "Historical entry"));
    db.update("update wf_adjustment set created_by=null where id=?", UUID.fromString(legacy));
    call(
        "POST",
        B + "/adjustments/" + legacy + "/decision",
        "PAYROLL_APPROVER",
        Map.of("approved", true, "notes", "Unknown author"),
        409);
    call(
        "POST",
        B + "/adjustments/" + legacy + "/decision",
        "PAYROLL_APPROVER",
        Map.of("approved", false, "notes", "Reenter with identified author"),
        204);
  }
}
