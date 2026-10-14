package com.plastiflow.manufacturing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plastiflow.shared.BusinessException;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class Store {
  final JdbcTemplate db;
  final ObjectMapper json;

  Store(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
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

  BigDecimal decimal(Map<String, Object> row, String key) {
    return (BigDecimal) row.get(key);
  }

  Instant time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof OffsetDateTime t) return t.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("Unsupported timestamp representation");
  }

  Object timestamp(Instant t) {
    return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
  }

  void interval(Instant start, Instant end) {
    require(end.isAfter(start), "INVALID_INTERVAL", "End must be after start.");
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON data", e);
    }
  }

  <T> T decode(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is invalid", e);
    }
  }

  Map<String, Object> product(UUID id, String kind) {
    var p = one("select * from catalog_product where id=?", id);
    require(
        Boolean.TRUE.equals(p.get("active")) && kind.equals(p.get("kind")),
        "INVALID_PRODUCT",
        "Product must be active and have kind " + kind + ".");
    return p;
  }

  void audit(UUID entity, String action) {
    audit(entity, action, "");
  }

  void audit(UUID entity, String action, String details) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    db.update(
        "insert into mfg_audit values (?,?,?,?,?,?)",
        id(),
        entity,
        action,
        details,
        actor,
        timestamp(Instant.now()));
  }
}
