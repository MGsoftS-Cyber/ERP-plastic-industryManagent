package com.plastiflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class ProductApiTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;
  private static final String PATH = "/api/v1/products";

  @Test
  void anonymousCannotReadOrCreate() throws Exception {
    api.perform(get(PATH)).andExpect(status().isUnauthorized());
    api.perform(
            post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("AUTH-TEST", "Resin")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void readerCannotWrite() throws Exception {
    api.perform(get(PATH).with(httpBasic("reader", "integration-reader-password")))
        .andExpect(status().isOk());
    api.perform(
            post(PATH)
                .with(httpBasic("reader", "integration-reader-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("READER-TEST", "Resin")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createGetUpdateAndDeactivate() throws Exception {
    JsonNode product = create("PP-" + suffix(), "PP Resin");
    String path = PATH + "/" + product.get("id").asText();
    api.perform(get(path).with(manager()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unit").value("KG"));
    api.perform(
            put(path)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Updated Resin\",\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.active").value(false));
    api.perform(
            get(PATH)
                .with(manager())
                .param("q", product.get("sku").asText())
                .param("active", "true"))
        .andExpect(jsonPath("$.totalItems").value(0));
    api.perform(
            get(PATH)
                .with(manager())
                .param("q", product.get("sku").asText())
                .param("active", "false"))
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void duplicateSkuReturnsConflict() throws Exception {
    String sku = "DUP-" + suffix();
    create(sku, "Resin");
    api.perform(
            post(PATH)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(sku, "Other")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CATALOG_CONFLICT"));
  }

  @Test
  void staleUpdateDoesNotOverwriteNewData() throws Exception {
    JsonNode product = create("STALE-" + suffix(), "Original");
    String path = PATH + "/" + product.get("id").asText();
    api.perform(
            put(path)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"New name\",\"active\":true}"))
        .andExpect(status().isOk());
    api.perform(
            put(path)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Old overwrite\",\"active\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    api.perform(get(path).with(manager())).andExpect(jsonPath("$.name").value("New name"));
  }

  @Test
  void invalidBodyAndPagingReturnBadRequest() throws Exception {
    for (String value :
        new String[] {
          "{\"sku\":\"bad sku\",\"name\":\" \",\"kind\":\"RAW_MATERIAL\",\"unit\":\"KG\"}",
          "{\"sku\":\"OK\",\"name\":\"Resin\",\"kind\":\"UNKNOWN\",\"unit\":\"KG\"}",
          "{\"sku\":\"OK\",\"name\":\"Resin\",\"kind\":\"RAW_MATERIAL\"}"
        }) {
      api.perform(post(PATH).with(manager()).contentType(MediaType.APPLICATION_JSON).content(value))
          .andExpect(status().isBadRequest());
    }
    api.perform(get(PATH).with(manager()).param("page", "-1")).andExpect(status().isBadRequest());
    api.perform(get(PATH).with(manager()).param("size", "101")).andExpect(status().isBadRequest());
    api.perform(get(PATH).with(manager()).param("q", "a".repeat(101)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void literalSearchDoesNotTreatPercentOrUnderscoreAsWildcards() throws Exception {
    create("SEARCH-" + suffix(), "Special 100%_resin");
    create("SEARCH-" + suffix(), "Special 100ABresin");
    api.perform(get(PATH).with(manager()).param("q", "100%_resin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void notFoundAndInvalidIdentifierAreDistinct() throws Exception {
    api.perform(get(PATH + "/" + UUID.randomUUID()).with(manager()))
        .andExpect(status().isNotFound());
    api.perform(get(PATH + "/bad-id").with(manager())).andExpect(status().isBadRequest());
  }

  @Test
  void managerRequiresValidPassword() throws Exception {
    api.perform(get(PATH).with(httpBasic("manager", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }

  private String suffix() {
    return UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT);
  }

  private JsonNode create(String sku, String name) throws Exception {
    var result =
        api.perform(
                post(PATH)
                    .with(manager())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(sku, name)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode created = json.readTree(result.getResponse().getContentAsString());
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo(PATH + "/" + created.get("id").asText());
    return created;
  }

  private String body(String sku, String name) throws Exception {
    return json.writeValueAsString(
        java.util.Map.of("sku", sku, "name", name, "kind", "RAW_MATERIAL", "unit", "KG"));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
    return httpBasic("manager", "integration-manager-password");
  }

  @Test
  void auditTracksCommittedChangesAndRejectsStaleWrites() throws Exception {
    var product = create("AUDIT-" + suffix(), "Original");
    UUID id = UUID.fromString(product.get("id").asText());
    String path = PATH + "/" + id;
    api.perform(
            put(path)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Changed\",\"active\":false}"))
        .andExpect(status().isOk());
    api.perform(
            put(path)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Stale\",\"active\":true}"))
        .andExpect(status().isConflict());
    assertThat(
            db.queryForObject(
                "select count(*) from catalog_audit where product_id=?", Integer.class, id))
        .isEqualTo(2);
    var detail =
        json.readTree(
            db.queryForObject(
                "select details from catalog_audit where product_id=? and action='PRODUCT_UPDATED'",
                String.class,
                id));
    assertThat(detail.get("before").get("name").asText()).isEqualTo("Original");
    assertThat(detail.get("after").get("name").asText()).isEqualTo("Changed");
    assertThat(detail.get("after").get("active").asBoolean()).isFalse();
  }
}
