package com.plastiflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class AiFlowTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @MockitoBean AiProvider provider;
  final String B = "/api/v1/integration/ai/jobs";

  @BeforeEach
  void setup() {
    db.update("delete from int_ai_job");
    when(provider.enabled()).thenReturn(true);
    when(provider.model()).thenReturn("test-fixture-provider");
  }

  @AfterEach
  void cleanup() {
    db.update("delete from int_ai_job");
  }

  JsonNode call(String path, String principal, String role, Object body, int status)
      throws Exception {
    var req = post(path).with(user(principal).roles(role));
    if (body != null)
      req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var response = api.perform(req).andReturn().getResponse();
    assertThat(response.getStatus())
        .withFailMessage(
            "%s expected %s got %s: %s",
            path, status, response.getStatus(), response.getContentAsString())
        .isEqualTo(status);
    return response.getContentAsString().isBlank()
        ? json.createObjectNode()
        : json.readTree(response.getContentAsString());
  }

  String draft() throws Exception {
    return call(
            B,
            "reports",
            "AI_ANALYST",
            Map.of("eventId", UUID.randomUUID(), "from", "2026-01-01", "to", "2026-01-02"),
            201)
        .get("id")
        .asText();
  }

  JsonNode output() throws Exception {
    return json.readTree(
        "{\"summary\":\"No recorded output in this range.\",\"suggestions\":[{\"observation\":\"No"
            + " source output events\",\"recommendation\":\"Check the selected date range\"}]}");
  }

  @Test
  void successRequiresIndependentReviewAndNeverPostsBusinessChanges() throws Exception {
    when(provider.generate(any())).thenReturn(output());
    int journals = db.queryForObject("select count(*) from fin_journal", Integer.class);
    String id = draft();
    var result = call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 200);
    assertThat(result.get("state").asText()).isEqualTo("SUCCEEDED");
    call(
        B + "/" + id + "/review",
        "reports",
        "INTEGRATION_MANAGER",
        Map.of("accepted", true, "notes", "self"),
        409);
    call(
        B + "/" + id + "/review",
        "integration",
        "INTEGRATION_MANAGER",
        Map.of("accepted", true, "notes", "Reviewed, advisory only"),
        200);
    assertThat(
            db.queryForObject(
                "select review from int_ai_job where id=?", String.class, UUID.fromString(id)))
        .isEqualTo("ACCEPTED");
    assertThat(db.queryForObject("select count(*) from fin_journal", Integer.class))
        .isEqualTo(journals);
    verify(provider)
        .generate(
            argThat(
                input ->
                    !input.has("employees") && !input.has("payroll") && input.has("products")));
    call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 409);
  }

  @Test
  void failureIsRedactedAndRetryUsesSamePreparedInput() throws Exception {
    when(provider.generate(any()))
        .thenThrow(new RuntimeException("sensitive-provider-secret"))
        .thenReturn(output());
    String id = draft();
    var failed = call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 200);
    assertThat(failed.get("state").asText()).isEqualTo("FAILED");
    assertThat(failed.toString()).doesNotContain("sensitive-provider-secret");
    var passed = call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 200);
    assertThat(passed.get("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(passed.get("attempts").asInt()).isEqualTo(2);
    assertThat(passed.get("input_snapshot")).isEqualTo(failed.get("input_snapshot"));
  }

  @Test
  void invalidOutputAndAttemptLimitDoNotPublishSuggestions() throws Exception {
    when(provider.generate(any())).thenReturn(json.readTree("{\"summary\":42,\"suggestions\":[]}"));
    String id = draft();
    for (int i = 0; i < 3; i++)
      assertThat(
              call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 200).get("state").asText())
          .isEqualTo("FAILED");
    call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 409);
    assertThat(
            db.queryForObject(
                "select output from int_ai_job where id=?", String.class, UUID.fromString(id)))
        .isNull();
  }

  @Test
  void anotherAnalystCannotSendOrReadOwnersSnapshot() throws Exception {
    String id = draft();
    call(B + "/" + id + "/run", "other", "AI_ANALYST", null, 409);
    assertThat(
            api.perform(get(B + "/" + id).with(user("other").roles("AI_ANALYST")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    verify(provider, never()).generate(any());
  }

  @Test
  void interruptedJobRecoveryIsTimeGuarded() throws Exception {
    String id = draft();
    db.update(
        "update int_ai_job set state='RUNNING',attempts=1,started_at=CURRENT_TIMESTAMP where id=?",
        UUID.fromString(id));
    call(B + "/" + id + "/recover", "reports", "AI_ANALYST", null, 409);
    db.update(
        "update int_ai_job set started_at=? where id=?",
        java.time.OffsetDateTime.now().minusMinutes(3),
        UUID.fromString(id));
    call(B + "/" + id + "/recover", "reports", "AI_ANALYST", null, 200);
    assertThat(
            db.queryForObject(
                "select error_code from int_ai_job where id=?", String.class, UUID.fromString(id)))
        .isEqualTo("AI_INTERRUPTED");
  }

  @Test
  void duplicateDraftKeepsItsOriginalSnapshot() throws Exception {
    var request = Map.of("eventId", UUID.randomUUID(), "from", "2026-01-01", "to", "2026-01-02");
    var first = call(B, "reports", "AI_ANALYST", request, 201);
    var second = call(B, "reports", "AI_ANALYST", request, 201);
    assertThat(second.get("input_snapshot")).isEqualTo(first.get("input_snapshot"));
    assertThat(db.queryForObject("select count(*) from int_ai_job", Integer.class)).isEqualTo(1);
  }

  @Test
  void staleAttemptCannotOverwriteANewerRun() throws Exception {
    String id = draft();
    when(provider.generate(any()))
        .thenAnswer(
            invocation -> {
              db.update(
                  "update int_ai_job set attempts=2,state='RUNNING' where id=?",
                  UUID.fromString(id));
              return output();
            });
    var result = call(B + "/" + id + "/run", "reports", "AI_ANALYST", null, 200);
    assertThat(result.get("state").asText()).isEqualTo("RUNNING");
    assertThat(result.get("output").isNull()).isTrue();
    assertThat(result.get("attempts").asInt()).isEqualTo(2);
  }

  @Test
  void draftRateLimitAndGlobalCallCapAreEnforced() throws Exception {
    var ids = new ArrayList<String>();
    for (int i = 0; i < 5; i++) ids.add(draft());
    call(
        B,
        "reports",
        "AI_ANALYST",
        Map.of("eventId", UUID.randomUUID(), "from", "2026-01-01", "to", "2026-01-02"),
        409);
    for (int i = 0; i < 2; i++)
      db.update(
          "update int_ai_job set state='RUNNING',attempts=1,started_at=CURRENT_TIMESTAMP where"
              + " id=?",
          UUID.fromString(ids.get(i)));
    call(B + "/" + ids.get(2) + "/run", "reports", "AI_ANALYST", null, 409);
    verify(provider, never()).generate(any());
  }
}
