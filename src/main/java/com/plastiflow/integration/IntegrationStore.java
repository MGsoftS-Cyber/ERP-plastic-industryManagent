package com.plastiflow.integration;

import com.fasterxml.jackson.databind.*;
import com.plastiflow.shared.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class IntegrationStore {
  final JdbcTemplate db;
  final ObjectMapper json;

  IntegrationStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  UUID id() {
    return UUID.randomUUID();
  }

  void lock() {
    one("select * from int_lock where id=1 for update");
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty())
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND", "Referenced record not found.");
    return rows.get(0);
  }

  void require(boolean ok, String code, String detail) {
    if (!ok) throw new BusinessException(HttpStatus.CONFLICT, code, detail);
  }

  String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  boolean role(String role) {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
  }

  Instant instant(Object value) {
    return value instanceof OffsetDateTime t
        ? t.toInstant()
        : ((java.sql.Timestamp) value).toInstant();
  }

  OffsetDateTime time(Instant instant) {
    return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  JsonNode decode(String value) {
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void audit(UUID id, String action, Object details) {
    db.update(
        "insert into int_audit values(?,?,?,?,?,?)",
        this.id(),
        id,
        action,
        actor(),
        encode(details),
        time(Instant.now()));
  }
}
