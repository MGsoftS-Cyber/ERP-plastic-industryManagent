package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class QualityService {
  private final Store s;

  QualityService(Store s) {
    this.s = s;
  }

  Map<String, Object> plan(InspectionPlan r) {
    s.one("select * from catalog_product where id=? for update", r.productId());
    s.product(r.productId(), "FINISHED_GOOD");
    var names = new HashSet<String>();
    for (var m : r.metrics()) {
      s.require(names.add(m.name()), "DUPLICATE_METRIC", "Metric names must be unique.");
      s.require(
          m.minimum().compareTo(m.maximum()) <= 0,
          "METRIC_RANGE",
          "Minimum must not exceed maximum.");
    }
    int revision =
        s.db.queryForObject(
            "select coalesce(max(revision),0)+1 from mfg_inspection_plan where product_id=?",
            Integer.class,
            r.productId());
    UUID id = s.id();
    s.db.update(
        "insert into mfg_inspection_plan values (?,?,?,?)",
        id,
        r.productId(),
        revision,
        s.encode(r.metrics()));
    s.audit(id, "INSPECTION_PLAN_CREATED");
    return s.one("select * from mfg_inspection_plan where id=?", id);
  }

  void lotDecision(UUID id, Disposition r) {
    s.one("select * from mfg_lot where id=? for update", id);
    s.db.update("update mfg_lot set quality=? where id=?", r.quality().name(), id);
    s.audit(id, "LOT_" + r.quality(), r.reason());
  }

  Map<String, Object> inspect(UUID id, Measurements r) {
    var batch = s.one("select * from mfg_batch where id=? for update", id);
    notDispatched(id);
    var order = s.one("select * from mfg_order where id=?", batch.get("order_id"));
    var plan =
        s.one("select * from mfg_inspection_plan where id=?", order.get("inspection_plan_id"));
    List<Metric> metrics =
        s.decode((String) plan.get("metrics"), new TypeReference<List<Metric>>() {});
    s.require(
        r.values()
            .keySet()
            .equals(
                metrics.stream().map(Metric::name).collect(java.util.stream.Collectors.toSet())),
        "MEASUREMENTS_MISMATCH",
        "Supply exactly the metrics in the order inspection plan.");
    boolean passed = true;
    for (var metric : metrics) {
      var value = r.values().get(metric.name());
      passed &= value.compareTo(metric.minimum()) >= 0 && value.compareTo(metric.maximum()) <= 0;
    }
    UUID inspection = s.id();
    String actor =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
            .getName();
    s.db.update(
        "insert into mfg_inspection values (?,?,?,?,?,?)",
        inspection,
        id,
        s.encode(r.values()),
        passed,
        actor,
        s.timestamp(Instant.now()));
    // Every new inspection revokes prior approval until an explicit release decision.
    s.db.update(
        "update mfg_batch set quality=? where id=?", passed ? "PENDING" : "QUARANTINED", id);
    if (!passed)
      s.db.update(
          "insert into mfg_nonconformity values (?,?,?,?,?)",
          s.id(),
          id,
          "Measurements outside approved limits",
          false,
          s.timestamp(Instant.now()));
    s.audit(id, "BATCH_INSPECTED");
    return s.one("select * from mfg_inspection where id=?", inspection);
  }

  void resolve(UUID id, String reason) {
    var n = s.one("select * from mfg_nonconformity where id=?", id);
    s.one("select * from mfg_batch where id=? for update", n.get("batch_id"));
    s.db.update("update mfg_nonconformity set resolved=true where id=?", id);
    s.audit(id, "NONCONFORMITY_RESOLVED", reason);
  }

  void release(UUID id, Disposition r) {
    s.one("select * from mfg_batch where id=? for update", id);
    notDispatched(id);
    if (r.quality() == Quality.APPROVED) {
      var inspections =
          s.db.queryForList(
              "select * from mfg_inspection where batch_id=? order by created_at desc, id desc"
                  + " limit 1",
              id);
      s.require(
          !inspections.isEmpty() && Boolean.TRUE.equals(inspections.get(0).get("passed")),
          "INSPECTION_REQUIRED",
          "Latest inspection must pass before release.");
      s.require(
          !s.exists("select 1 from mfg_nonconformity where batch_id=? and resolved=false", id),
          "OPEN_NONCONFORMITY",
          "Resolve nonconformities before approval.");
    }
    s.db.update("update mfg_batch set quality=? where id=?", r.quality().name(), id);
    s.audit(id, "BATCH_" + r.quality(), r.reason());
  }

  private void notDispatched(UUID id) {
    s.require(
        !s.exists(
            "select 1 from mfg_pallet p join mfg_dispatch_reference d on d.pallet_id=p.id where"
                + " p.batch_id=?",
            id),
        "ALREADY_DISPATCHED",
        "Use a future recall workflow for dispatched batches; historical dispatch references cannot"
            + " be changed.");
  }
}
