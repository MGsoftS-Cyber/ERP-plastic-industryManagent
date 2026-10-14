package com.plastiflow.payroll;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plastiflow.shared.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class PayrollStore {
  final JdbcTemplate db;
  final ObjectMapper json;

  PayrollStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  void lock() {
    one("select * from wf_lock where id=1 for update");
  }

  UUID id() {
    return UUID.randomUUID();
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty())
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND", "Referenced record not found.");
    return rows.get(0);
  }

  boolean exists(String sql, Object... args) {
    return !db.queryForList(sql, args).isEmpty();
  }

  void require(boolean yes, String code, String detail) {
    if (!yes) throw new BusinessException(HttpStatus.CONFLICT, code, detail);
  }

  Instant instant(Object v) {
    return v instanceof OffsetDateTime t ? t.toInstant() : ((java.sql.Timestamp) v).toInstant();
  }

  Object time(Instant v) {
    return v.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  <T> T decode(String v, TypeReference<T> t) {
    try {
      return json.readValue(v, t);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void audit(UUID id, String action, String notes) {
    db.update(
        "insert into wf_audit values (?,?,?,?,?,?)",
        this.id(),
        id,
        action,
        actor(),
        notes,
        time(Instant.now()));
  }

  void employee(UUID id) {
    var e = one("select * from mfg_employee where id=?", id);
    require(Boolean.TRUE.equals(e.get("active")), "EMPLOYEE_INACTIVE", "Employee must be active.");
  }

  void interval(Instant a, Instant b) {
    require(
        b.isAfter(a) && Duration.between(a, b).compareTo(Duration.ofHours(24)) <= 0,
        "INVALID_INTERVAL",
        "Provide a positive interval of at most 24 hours.");
    require(
        a.equals(a.truncatedTo(java.time.temporal.ChronoUnit.MINUTES))
            && b.equals(b.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),
        "MINUTE_PRECISION",
        "Validated intervals must use whole-minute timestamps.");
  }

  void changed(UUID employee, Instant a, Instant b) {
    for (var p :
        db.queryForList(
            "select p.* from wf_period p join wf_period_employee e on e.period_id=p.id where"
                + " e.employee_id=?",
            employee)) {
      ZoneId zone = ZoneId.of((String) p.get("zone_id"));
      Instant
          from = ((java.sql.Date) p.get("starts_on")).toLocalDate().atStartOfDay(zone).toInstant(),
          to = ((java.sql.Date) p.get("ends_on")).toLocalDate().atStartOfDay(zone).toInstant();
      if (a.isBefore(to) && b.isAfter(from)) {
        require(
            !"FINALIZED".equals(p.get("state")),
            "PAYROLL_FROZEN",
            "Finalized payroll inputs cannot be changed. Use a separate later correction"
                + " adjustment.");
        db.update(
            "update wf_period set state='DIRTY',reviewed_by=null where id=? and state<>'DRAFT'",
            p.get("id"));
      }
    }
  }

  void datedChange(UUID employee, LocalDate date) {
    for (var p :
        employee == null
            ? db.queryForList(
                "select * from wf_period where ends_on>?", java.sql.Date.valueOf(date))
            : db.queryForList(
                "select p.* from wf_period p join wf_period_employee e on e.period_id=p.id where"
                    + " e.employee_id=? and p.ends_on>?",
                employee,
                java.sql.Date.valueOf(date))) {
      require(
          !"FINALIZED".equals(p.get("state")),
          "PAYROLL_FROZEN",
          "Cannot introduce a backdated rule affecting finalized payroll.");
      db.update(
          "update wf_period set state='DIRTY',reviewed_by=null where id=? and state<>'DRAFT'",
          p.get("id"));
    }
  }
}
