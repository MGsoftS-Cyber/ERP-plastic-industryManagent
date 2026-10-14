package com.plastiflow.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Shares the product transaction: stale or failed writes cannot leave successful audit entries. */
@Component
class CatalogAudit {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  CatalogAudit(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  void record(UUID id, String action, Object before, Object after) {
    try {
      var detail = json.createObjectNode();
      detail.set("before", json.valueToTree(before));
      detail.set("after", json.valueToTree(after));
      db.update(
          "insert into catalog_audit values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          id,
          action,
          SecurityContextHolder.getContext().getAuthentication().getName(),
          json.writeValueAsString(detail),
          OffsetDateTime.now());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Catalog audit serialization failed", e);
    }
  }
}
