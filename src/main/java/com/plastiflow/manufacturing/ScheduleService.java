package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ScheduleService {
  private final Store s;

  ScheduleService(Store s) {
    this.s = s;
  }

  Map<String, Object> schedule(UUID id, Schedule r) {
    s.interval(r.start(), r.end());
    var o = s.one("select * from mfg_order where id=? for update", id);
    s.require(
        Set.of("PLANNED", "SCHEDULED").contains(o.get("state")),
        "ORDER_STATE",
        "Only planned or scheduled orders may be scheduled.");
    var m = s.one("select * from mfg_machine where id=? for update", r.machineId());
    s.require("AVAILABLE".equals(m.get("state")), "MACHINE_UNAVAILABLE", "Machine unavailable.");
    var recipe = s.one("select * from mfg_recipe where id=?", o.get("recipe_id"));
    s.require(
        recipe.get("process").equals(m.get("process")),
        "PROCESS_MISMATCH",
        "Machine process does not match recipe.");
    // capacity_kg is maximum total planned material per job, not throughput or clamp force.
    BigDecimal material = BigDecimal.ZERO;
    for (var c :
        s.db.queryForList("select * from mfg_component where recipe_id=?", o.get("recipe_id")))
      material =
          material.add(
              s.decimal(c, "kg_per_unit")
                  .multiply(s.decimal(o, "planned_qty"))
                  .divide(s.decimal(recipe, "yield_factor"), 6, RoundingMode.CEILING));
    s.require(
        material.compareTo(s.decimal(m, "capacity_kg")) <= 0,
        "CAPACITY_EXCEEDED",
        "Planned material exceeds machine job capacity.");
    boolean needsMold = Set.of("INJECTION", "BLOW_MOLDING").contains(recipe.get("process"));
    s.require(!needsMold || r.moldId() != null, "MOLD_REQUIRED", "This process requires a mold.");
    if (r.moldId() != null) {
      var mold = s.one("select * from mfg_mold where id=? for update", r.moldId());
      s.require("AVAILABLE".equals(mold.get("state")), "MOLD_UNAVAILABLE", "Mold unavailable.");
      s.require(
          s.exists(
              "select 1 from mfg_compatibility where machine_id=? and mold_id=?",
              r.machineId(),
              r.moldId()),
          "INCOMPATIBLE_MOLD",
          "Mold is not approved for this machine.");
    }
    var employee = s.one("select * from mfg_employee where id=? for update", r.employeeId());
    s.require(
        Boolean.TRUE.equals(employee.get("active")), "EMPLOYEE_INACTIVE", "Employee inactive.");
    var shift = s.one("select * from mfg_shift where id=?", r.shiftId());
    s.require(
        r.employeeId().equals(shift.get("employee_id"))
            && !r.start().isBefore(s.time(shift, "starts_at"))
            && !r.end().isAfter(s.time(shift, "ends_at")),
        "SHIFT_COVERAGE",
        "Assignment must fit the employee shift.");
    var qualification =
        s.one(
            "select * from mfg_qualification where employee_id=? and machine_id=?",
            r.employeeId(),
            r.machineId());
    LocalDate expiry = ((java.sql.Date) qualification.get("valid_until")).toLocalDate();
    s.require(
        !r.end().minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate().isAfter(expiry),
        "QUALIFICATION_EXPIRED",
        "Qualification expires before the job finishes.");
    BigDecimal minutes =
        s.db.queryForObject(
            "select sum(minutes_per_unit) from mfg_operation where recipe_id=?",
            BigDecimal.class,
            o.get("recipe_id"));
    BigDecimal neededSeconds =
        minutes.multiply(s.decimal(o, "planned_qty")).multiply(BigDecimal.valueOf(60));
    s.require(
        BigDecimal.valueOf(Duration.between(r.start(), r.end()).toSeconds())
                .compareTo(neededSeconds)
            >= 0,
        "WINDOW_TOO_SHORT",
        "Schedule is shorter than routing estimate.");
    s.require(
        !s.exists(
            "select 1 from mfg_schedule sc join mfg_order o on o.id=sc.order_id where"
                + " sc.order_id<>? and o.state in ('SCHEDULED','RUNNING') and sc.starts_at<? and"
                + " sc.ends_at>? and (sc.machine_id=? or sc.employee_id=? or sc.mold_id=?)",
            id,
            s.timestamp(r.end()),
            s.timestamp(r.start()),
            r.machineId(),
            r.employeeId(),
            r.moldId()),
        "SCHEDULE_OVERLAP",
        "Machine, mold or employee already assigned during this interval.");
    s.require(
        !s.exists(
            "select 1 from mfg_maintenance_block where machine_id=? and cancelled=false and"
                + " starts_at<? and ends_at>?",
            r.machineId(),
            s.timestamp(r.end()),
            s.timestamp(r.start())),
        "MAINTENANCE_BLOCK",
        "Schedule overlaps maintenance.");
    s.db.update("delete from mfg_schedule where order_id=?", id);
    s.db.update(
        "insert into mfg_schedule values (?,?,?,?,?,?,?)",
        id,
        r.machineId(),
        r.moldId(),
        r.employeeId(),
        r.shiftId(),
        s.timestamp(r.start()),
        s.timestamp(r.end()));
    s.db.update("update mfg_order set state='SCHEDULED' where id=?", id);
    s.audit(id, "JOB_SCHEDULED");
    return s.one("select * from mfg_schedule where order_id=?", id);
  }

  Map<String, Object> maintenance(Maintenance r) {
    s.interval(r.start(), r.end());
    s.one("select * from mfg_machine where id=? for update", r.machineId());
    s.require(
        !s.exists(
            "select 1 from mfg_schedule sc join mfg_order o on o.id=sc.order_id where"
                + " sc.machine_id=? and o.state in ('SCHEDULED','RUNNING') and sc.starts_at<? and"
                + " sc.ends_at>?",
            r.machineId(),
            s.timestamp(r.end()),
            s.timestamp(r.start())),
        "SCHEDULE_CONFLICT",
        "Reschedule affected jobs before adding maintenance.");
    UUID id = s.id();
    s.db.update(
        "insert into mfg_maintenance_block (id,machine_id,starts_at,ends_at,reason) values"
            + " (?,?,?,?,?)",
        id,
        r.machineId(),
        s.timestamp(r.start()),
        s.timestamp(r.end()),
        r.reason());
    s.audit(id, "MAINTENANCE_BLOCK_CREATED");
    return s.one("select * from mfg_maintenance_block where id=?", id);
  }

  void cancelMaintenance(UUID id, Abort r) {
    var existing = s.one("select * from mfg_maintenance_block where id=?", id);
    s.one("select * from mfg_machine where id=? for update", existing.get("machine_id"));
    var block = s.one("select * from mfg_maintenance_block where id=? for update", id);
    s.require(
        !Boolean.TRUE.equals(block.get("cancelled")),
        "WINDOW_CANCELLED",
        "Window already cancelled.");
    s.db.update("update mfg_maintenance_block set cancelled=true where id=?", id);
    s.audit(id, "MAINTENANCE_WINDOW_CANCELLED", r.reason());
  }
}
