package com.plastiflow.integration;

import static com.plastiflow.integration.Requests.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.plastiflow.shared.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AiService {
  final IntegrationStore s;
  final ReportService reports;
  final AiProvider provider;
  final TransactionTemplate tx;

  AiService(
      IntegrationStore s,
      ReportService reports,
      AiProvider provider,
      PlatformTransactionManager manager) {
    this.s = s;
    this.reports = reports;
    this.provider = provider;
    tx = new TransactionTemplate(manager);
  }

  Map<String, Object> create(AiRequest r) {
    return tx.execute(
        status -> {
          s.lock();
          var prior = s.db.queryForList("select * from int_ai_job where id=?", r.eventId());
          if (!prior.isEmpty()) {
            var old = prior.get(0);
            s.require(
                s.actor().equals(old.get("created_by"))
                    && s.decode((String) old.get("input_snapshot"))
                        .path("from")
                        .asText()
                        .equals(r.from().toString())
                    && s.decode((String) old.get("input_snapshot"))
                        .path("to")
                        .asText()
                        .equals(r.to().toString()),
                "EVENT_CONFLICT",
                "AI job ID belongs to another request.");
            return old;
          }
          s.require(
              s.db.queryForObject(
                      "select count(*) from int_ai_job where created_by=? and created_at>?",
                      Integer.class,
                      s.actor(),
                      s.time(Instant.now().minusSeconds(60)))
                  < 5,
              "AI_RATE_LIMIT",
              "At most five new AI drafts per minute.");
          var report = reports.production(r.from(), r.to());
          s.require(
              s.encode(report).length() <= 16000,
              "AI_INPUT_TOO_LARGE",
              "Narrow the reporting range or product set.");
          s.db.update(
              "insert into int_ai_job"
                  + " values(?,?,'QUEUED',?,null,null,0,null,?,null,null,null,null)",
              r.eventId(),
              s.actor(),
              s.encode(report),
              s.time(Instant.now()));
          s.audit(r.eventId(), "AI_DRAFT_PREPARED", Map.of("from", r.from(), "to", r.to()));
          return detail(r.eventId());
        });
  }

  Map<String, Object> detail(UUID id) {
    var job = s.one("select * from int_ai_job where id=?", id);
    s.require(
        s.actor().equals(job.get("created_by")) || s.role("INTEGRATION_MANAGER"),
        "AI_OWNER",
        "Only the creator or integration manager may inspect this draft.");
    return job;
  }

  List<Map<String, Object>> list() {
    return s.role("INTEGRATION_MANAGER")
        ? s.db.queryForList("select * from int_ai_job order by created_at desc limit 200")
        : s.db.queryForList(
            "select * from int_ai_job where created_by=? order by created_at desc limit 200",
            s.actor());
  }

  Map<String, Object> run(UUID id) {
    if (!provider.enabled())
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AI_DISABLED",
          "Configure and enable the AI provider before running a draft.");
    var job =
        tx.execute(
            status -> {
              s.lock();
              var j = detail(id);
              s.require(
                  s.actor().equals(j.get("created_by")),
                  "AI_OWNER",
                  "Only the creator may send this prepared report to the provider.");
              s.require(
                  Set.of("QUEUED", "FAILED").contains(j.get("state"))
                      && ((Number) j.get("attempts")).intValue() < 3,
                  "AI_JOB_STATE",
                  "Run a queued/failed job with fewer than three attempts.");
              s.require(
                  s.db.queryForObject(
                          "select count(*) from int_ai_job where state='RUNNING'", Integer.class)
                      < 2,
                  "AI_BUSY",
                  "At most two AI calls may run simultaneously.");
              s.db.update(
                  "update int_ai_job set"
                      + " state='RUNNING',attempts=attempts+1,model=?,started_at=?,error_code=null"
                      + " where id=?",
                  provider.model(),
                  s.time(Instant.now()),
                  id);
              return j;
            });
    int attempt = ((Number) job.get("attempts")).intValue() + 1;
    try {
      JsonNode output = provider.generate(s.decode((String) job.get("input_snapshot")));
      validate(output);
      return tx.execute(
          status -> {
            s.lock();
            s.require(
                "RUNNING".equals(detail(id).get("state"))
                    && ((Number) detail(id).get("attempts")).intValue() == attempt,
                "AI_JOB_STATE",
                "Job is no longer running.");
            s.db.update(
                "update int_ai_job set state='SUCCEEDED',output=? where id=?",
                output.toString(),
                id);
            s.audit(id, "AI_SUGGESTION_CREATED", Map.of("model", provider.model()));
            return detail(id);
          });
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      return tx.execute(
          status -> {
            s.lock();
            s.db.update(
                "update int_ai_job set state='FAILED',error_code='AI_PROVIDER_FAILURE' where id=?"
                    + " and state='RUNNING' and attempts=?",
                id,
                attempt);
            s.audit(id, "AI_FAILED", Map.of("code", "AI_PROVIDER_FAILURE"));
            return detail(id);
          });
    }
  }

  void validate(JsonNode output) {
    s.require(
        output != null
            && output.isObject()
            && output.size() == 2
            && output.path("summary").isTextual()
            && !output.path("summary").asText().isBlank()
            && output.path("summary").asText().length() <= 2000
            && output.path("suggestions").isArray()
            && output.path("suggestions").size() <= 8,
        "AI_INVALID_OUTPUT",
        "Provider output does not match the bounded suggestion format.");
    for (var row : output.path("suggestions"))
      s.require(
          row.isObject()
              && row.size() == 2
              && row.path("observation").isTextual()
              && row.path("recommendation").isTextual()
              && !row.path("observation").asText().isBlank()
              && !row.path("recommendation").asText().isBlank()
              && row.path("observation").asText().length() <= 1000
              && row.path("recommendation").asText().length() <= 1000,
          "AI_INVALID_OUTPUT",
          "Invalid suggestion fields.");
  }

  void recover(UUID id) {
    tx.executeWithoutResult(
        status -> {
          s.lock();
          var j = detail(id);
          s.require(
              "RUNNING".equals(j.get("state"))
                  && s.instant(j.get("started_at")).isBefore(Instant.now().minusSeconds(120)),
              "AI_JOB_STATE",
              "Only a running job older than two minutes may be recovered.");
          s.db.update(
              "update int_ai_job set state='FAILED',error_code='AI_INTERRUPTED' where id=?", id);
          s.audit(id, "AI_JOB_RECOVERED", Map.of());
        });
  }

  void review(UUID id, Decision r) {
    tx.executeWithoutResult(
        status -> {
          s.lock();
          var j = detail(id);
          s.require(
              "SUCCEEDED".equals(j.get("state")) && j.get("review") == null,
              "AI_JOB_STATE",
              "Review an unreviewed successful suggestion.");
          s.require(
              !s.actor().equals(j.get("created_by")),
              "SEPARATE_REVIEWER",
              "Another person must review the suggestion.");
          s.db.update(
              "update int_ai_job set review=?,reviewed_by=?,review_notes=? where id=?",
              r.accepted() ? "ACCEPTED" : "REJECTED",
              s.actor(),
              r.notes(),
              id);
          s.audit(id, "AI_SUGGESTION_REVIEWED", r);
        });
  }
}
