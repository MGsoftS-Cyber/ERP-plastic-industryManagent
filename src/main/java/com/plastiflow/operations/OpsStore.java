package com.plastiflow.operations;

import com.plastiflow.shared.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class OpsStore {
  final JdbcTemplate db;

  OpsStore(JdbcTemplate db) {
    this.db = db;
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

  void require(boolean condition, String code, String detail) {
    if (!condition) throw new BusinessException(HttpStatus.CONFLICT, code, detail);
  }

  UUID id() {
    return UUID.randomUUID();
  }

  Object time(Instant t) {
    return t.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  Instant instant(Object v) {
    return v instanceof OffsetDateTime t ? t.toInstant() : ((java.sql.Timestamp) v).toInstant();
  }

  void interval(Instant start, Instant end) {
    require(end.isAfter(start), "INVALID_INTERVAL", "End must be after start.");
  }

  String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  boolean role(String role) {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
  }

  void audit(UUID id, String action, String notes) {
    db.update(
        "insert into ops_audit values (?,?,?,?,?,?)",
        this.id(),
        id,
        action,
        notes,
        actor(),
        time(Instant.now()));
  }
}
