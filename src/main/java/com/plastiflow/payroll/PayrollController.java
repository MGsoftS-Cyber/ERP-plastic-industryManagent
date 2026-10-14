package com.plastiflow.payroll;

import static com.plastiflow.payroll.Requests.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payroll")
class PayrollController {
  private final PayrollStore s;
  private final AttendanceService time;
  private final PayrollService pay;

  PayrollController(PayrollStore s, AttendanceService time, PayrollService pay) {
    this.s = s;
    this.time = time;
    this.pay = pay;
  }

  @GetMapping("/attendance-overview")
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> attendanceOverview() {
    return Map.of(
        "employees",
        s.db.queryForList("select id,code,name from mfg_employee order by code limit 200"),
        "shifts",
        s.db.queryForList("select * from mfg_shift order by starts_at desc limit 200"),
        "clockEvents",
        s.db.queryForList("select * from wf_clock_event order by occurred_at desc limit 200"),
        "attendance",
        s.db.queryForList("select * from wf_attendance order by starts_at desc limit 200"),
        "absences",
        s.db.queryForList("select * from wf_absence order by starts_at desc limit 200"));
  }

  @GetMapping("/overview")
  @PreAuthorize("hasAnyRole('PAYROLL_MANAGER','PAYROLL_APPROVER')")
  Map<String, Object> overview() {
    return Map.of(
        "employees",
        s.db.queryForList("select id,code,name from mfg_employee order by code limit 200"),
        "wages",
        s.db.queryForList("select * from wf_wage order by effective_from desc limit 200"),
        "rules",
        s.db.queryForList("select * from wf_rule order by effective_from desc limit 200"),
        "periods",
        s.db.queryForList("select * from wf_period order by starts_on desc limit 200"),
        "adjustments",
        s.db.queryForList("select * from wf_adjustment limit 200"));
  }

  @PostMapping("/clock-events")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> clock(@Valid @RequestBody Clock r) {
    return time.clock(r);
  }

  @PostMapping("/attendance")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> attendance(@Valid @RequestBody Attendance r) {
    return time.attendance(r);
  }

  @PostMapping("/attendance/{id}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  void approve(@PathVariable UUID id, @Valid @RequestBody Approval r) {
    time.approve(id, r);
  }

  @PostMapping("/attendance/{id}/corrections")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> correct(@PathVariable UUID id, @Valid @RequestBody Attendance r) {
    return time.correct(id, r);
  }

  @PostMapping("/absences")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> absence(@Valid @RequestBody Absence r) {
    return time.absence(r);
  }

  @PostMapping("/absences/{id}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  void absenceApproval(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    time.approveAbsence(id, r);
  }

  @PostMapping("/attendance/{id}/void")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  void voidAttendance(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    time.voidAttendance(id, r);
  }

  @PostMapping("/absences/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  void cancelAbsence(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    time.cancelAbsence(id, r);
  }

  @PostMapping("/wages")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  Map<String, Object> wage(@Valid @RequestBody Wage r) {
    return pay.wage(r);
  }

  @PostMapping("/rules")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  Map<String, Object> rule(@Valid @RequestBody Rule r) {
    return pay.rule(r);
  }

  @PostMapping("/periods")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  Map<String, Object> period(@Valid @RequestBody Period r) {
    return pay.period(r);
  }

  @GetMapping("/periods/{id}")
  @PreAuthorize("hasAnyRole('PAYROLL_MANAGER','PAYROLL_APPROVER')")
  Map<String, Object> detail(@PathVariable UUID id) {
    return pay.detail(id);
  }

  @PostMapping("/periods/{id}/adjustments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  Map<String, Object> adjustment(@PathVariable UUID id, @Valid @RequestBody Adjustment r) {
    return pay.adjustment(id, r);
  }

  @PostMapping("/adjustments/{id}/decision")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PAYROLL_APPROVER')")
  void decision(@PathVariable UUID id, @Valid @RequestBody Decision r) {
    pay.decide(id, r);
  }

  @PostMapping("/periods/{id}/calculate")
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  Map<String, Object> calculate(@PathVariable UUID id) {
    return pay.calculate(id);
  }

  @PostMapping("/periods/{id}/review")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PAYROLL_APPROVER')")
  void review(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    pay.review(id, r);
  }

  @PostMapping("/periods/{id}/finalize")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PAYROLL_MANAGER')")
  void finalizePeriod(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    pay.finalizePeriod(id, r);
  }
}
