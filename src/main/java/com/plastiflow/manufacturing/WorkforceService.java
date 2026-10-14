package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class WorkforceService {
  private final Store s;

  WorkforceService(Store s) {
    this.s = s;
  }

  Map<String, Object> employee(Employee r) {
    UUID id = s.id();
    s.db.update(
        "insert into mfg_employee values (?,?,?,?,?)",
        id,
        r.code().strip(),
        r.name().strip(),
        r.userSubject(),
        true);
    s.audit(id, "EMPLOYEE_CREATED");
    return s.one("select * from mfg_employee where id=?", id);
  }

  void qualification(UUID employee, Qualification r) {
    s.one("select * from mfg_employee where id=? for update", employee);
    s.one("select * from mfg_machine where id=?", r.machineId());
    s.require(
        !s.exists(
            "select 1 from mfg_schedule sc join mfg_order o on o.id=sc.order_id where"
                + " sc.employee_id=? and sc.machine_id=? and o.state in ('SCHEDULED','RUNNING') and"
                + " sc.ends_at>?",
            employee,
            r.machineId(),
            s.timestamp(
                r.validUntil().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())),
        "ASSIGNMENT_CONFLICT",
        "Qualification would expire before an existing assignment finishes.");
    s.db.update(
        "delete from mfg_qualification where employee_id=? and machine_id=?",
        employee,
        r.machineId());
    s.db.update(
        "insert into mfg_qualification values (?,?,?)",
        employee,
        r.machineId(),
        java.sql.Date.valueOf(r.validUntil()));
    s.audit(employee, "QUALIFICATION_SET");
  }

  Map<String, Object> shift(Shift r) {
    s.interval(r.start(), r.end());
    var employee = s.one("select * from mfg_employee where id=? for update", r.employeeId());
    s.require(
        Boolean.TRUE.equals(employee.get("active")),
        "EMPLOYEE_INACTIVE",
        "Cannot create a shift for an inactive employee.");
    s.require(
        !s.exists(
            "select 1 from mfg_shift where employee_id=? and starts_at<? and ends_at>?",
            r.employeeId(),
            s.timestamp(r.end()),
            s.timestamp(r.start())),
        "SHIFT_OVERLAP",
        "Employee already has an overlapping shift.");
    UUID id = s.id();
    s.db.update(
        "insert into mfg_shift values (?,?,?,?)",
        id,
        r.employeeId(),
        s.timestamp(r.start()),
        s.timestamp(r.end()));
    s.audit(id, "SHIFT_CREATED");
    return s.one("select * from mfg_shift where id=?", id);
  }
}
