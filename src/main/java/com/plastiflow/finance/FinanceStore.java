package com.plastiflow.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plastiflow.shared.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class FinanceStore {
  final JdbcTemplate db;
  final ObjectMapper json;

  FinanceStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  void lock() {
    one("select * from fin_lock where id=1 for update");
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

  void require(boolean yes, String code, String detail) {
    if (!yes) throw new BusinessException(HttpStatus.CONFLICT, code, detail);
  }

  BigDecimal amount(Map<String, Object> row, String key) {
    return (BigDecimal) row.get(key);
  }

  BigDecimal sum(String sql, Object... args) {
    BigDecimal v = db.queryForObject(sql, BigDecimal.class, args);
    return v == null ? BigDecimal.ZERO : v;
  }

  String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void audit(UUID id, String action, Object detail) {
    db.update(
        "insert into fin_audit values(?,?,?,?,?,?)",
        this.id(),
        id,
        action,
        actor(),
        encode(detail),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  void currency(String code) {
    try {
      Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "Use an ISO currency code.");
    }
    require(
        Currency.getInstance(code).getDefaultFractionDigits() == 2,
        "CURRENCY_SCALE",
        "Financial release currently supports currencies with two decimal minor units.");
  }

  void account(UUID id, String... kinds) {
    var a = one("select * from fin_account where id=?", id);
    require(
        Arrays.asList(kinds).contains(a.get("kind")),
        "ACCOUNT_KIND",
        "Choose an account of the correct type.");
  }

  Map<String, Object> period(UUID id, LocalDate date) {
    var p = one("select * from fin_period where id=? for update", id);
    require("OPEN".equals(p.get("state")), "PERIOD_CLOSED", "Accounting period is closed.");
    require(
        !date.isBefore(((java.sql.Date) p.get("starts_on")).toLocalDate())
            && date.isBefore(((java.sql.Date) p.get("ends_on")).toLocalDate()),
        "POSTING_DATE",
        "Posting date must fall in the period; end date is exclusive.");
    return p;
  }
}
