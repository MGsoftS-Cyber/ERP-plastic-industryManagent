package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ProductionService {
  private final Store s;

  ProductionService(Store s) {
    this.s = s;
  }

  Map<String, Object> create(Order r) {
    var recipe = s.one("select * from mfg_recipe where id=?", r.recipeId());
    var product = s.product((UUID) recipe.get("product_id"), "FINISHED_GOOD");
    if ("PIECE".equals(product.get("unit")))
      s.require(
          r.plannedQty().stripTrailingZeros().scale() <= 0,
          "WHOLE_PIECES",
          "Piece quantities must be whole numbers.");
    var plan = s.one("select * from mfg_inspection_plan where id=?", r.inspectionPlanId());
    s.require(
        plan.get("product_id").equals(recipe.get("product_id")),
        "PLAN_PRODUCT",
        "Inspection plan belongs to another product.");
    UUID id = s.id();
    s.db.update(
        "insert into mfg_order"
            + " (id,code,sales_reference,recipe_id,inspection_plan_id,planned_qty,good_qty,reject_qty,state)"
            + " values (?,?,?,?,?,?,?,?,?)",
        id,
        r.code().strip(),
        r.salesReference(),
        r.recipeId(),
        r.inspectionPlanId(),
        r.plannedQty(),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "PLANNED");
    s.audit(id, "PRODUCTION_ORDER_CREATED");
    return detail(id);
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var result = new LinkedHashMap<>(s.one("select * from mfg_order where id=?", id));
    result.put("requirements", requirements(id));
    result.put("schedule", s.db.queryForList("select * from mfg_schedule where order_id=?", id));
    result.put(
        "consumptions", s.db.queryForList("select * from mfg_consumption where order_id=?", id));
    result.put("output_events", s.db.queryForList("select * from mfg_output where order_id=?", id));
    result.put("downtime", s.db.queryForList("select * from mfg_downtime where order_id=?", id));
    return result;
  }

  List<Map<String, Object>> requirements(UUID id) {
    var order = s.one("select * from mfg_order where id=?", id);
    var recipe = s.one("select * from mfg_recipe where id=?", order.get("recipe_id"));
    var result = new ArrayList<Map<String, Object>>();
    for (var c :
        s.db.queryForList(
            "select * from mfg_component where recipe_id=? order by material_id",
            order.get("recipe_id"))) {
      BigDecimal qty =
          s.decimal(c, "kg_per_unit")
              .multiply(s.decimal(order, "planned_qty"))
              .divide(s.decimal(recipe, "yield_factor"), 6, RoundingMode.CEILING);
      BigDecimal available =
          s.db.queryForObject(
              "select coalesce(sum(remaining_kg),0) from mfg_lot where material_id=? and"
                  + " quality='APPROVED'",
              BigDecimal.class,
              c.get("material_id"));
      BigDecimal consumed =
          s.db.queryForObject(
              "select coalesce(sum(c.kg),0) from mfg_consumption c join mfg_lot l on l.id=c.lot_id"
                  + " where c.order_id=? and l.material_id=?",
              BigDecimal.class,
              id,
              c.get("material_id"));
      BigDecimal remaining =
          Set.of("COMPLETED", "ABORTED").contains(order.get("state"))
              ? BigDecimal.ZERO
              : qty.subtract(consumed).max(BigDecimal.ZERO);
      result.add(
          Map.of(
              "material_id",
              c.get("material_id"),
              "required_kg",
              qty,
              "consumed_kg",
              consumed,
              "remaining_required_kg",
              remaining,
              "available_kg",
              available,
              "shortage_kg",
              remaining.subtract(available).max(BigDecimal.ZERO)));
    }
    return result;
  }

  Map<String, Object> lot(Lot r) {
    var p = s.product(r.materialId(), "RAW_MATERIAL");
    s.require("KG".equals(p.get("unit")), "MATERIAL_UNIT", "Material lot requires KG base unit.");
    UUID id = s.id();
    s.db.update(
        "insert into mfg_lot values (?,?,?,?,?,?,?,?)",
        id,
        r.code().strip(),
        r.materialId(),
        r.supplierReference(),
        r.location(),
        r.receivedKg(),
        r.receivedKg(),
        "PENDING");
    s.audit(id, "MATERIAL_LOT_REGISTERED");
    return s.one("select * from mfg_lot where id=?", id);
  }

  void start(UUID id) {
    var o = lockedOrder(id);
    s.require("SCHEDULED".equals(o.get("state")), "ORDER_STATE", "Only a scheduled job can start.");
    var a = s.one("select * from mfg_schedule where order_id=?", id);
    Instant now = Instant.now();
    s.require(
        !now.isBefore(s.time(a, "starts_at")) && now.isBefore(s.time(a, "ends_at")),
        "OUTSIDE_SCHEDULE",
        "Start the job within its scheduled interval.");
    var m = s.one("select * from mfg_machine where id=? for update", a.get("machine_id"));
    s.require("AVAILABLE".equals(m.get("state")), "MACHINE_UNAVAILABLE", "Machine unavailable.");
    if (a.get("mold_id") != null) {
      var mold = s.one("select * from mfg_mold where id=? for update", a.get("mold_id"));
      s.require("AVAILABLE".equals(mold.get("state")), "MOLD_UNAVAILABLE", "Mold unavailable.");
    }
    var employee = s.one("select * from mfg_employee where id=? for update", a.get("employee_id"));
    s.require(
        Boolean.TRUE.equals(employee.get("active")),
        "EMPLOYEE_INACTIVE",
        "Assigned employee must be active at job start.");
    s.require(
        !s.exists(
            "select 1 from mfg_schedule a join mfg_order o on a.order_id=o.id where"
                + " o.state='RUNNING' and (a.machine_id=? or a.employee_id=? or a.mold_id=?)",
            a.get("machine_id"),
            a.get("employee_id"),
            a.get("mold_id")),
        "RESOURCE_RUNNING",
        "Machine, tooling or worker already runs another job.");
    s.require(
        !s.exists(
            "select 1 from mfg_maintenance_block where machine_id=? and cancelled=false and"
                + " starts_at<? and ends_at>?",
            a.get("machine_id"),
            s.timestamp(Instant.now().plusNanos(1)),
            s.timestamp(Instant.now())),
        "MAINTENANCE_BLOCK",
        "Machine is in a maintenance window.");
    // Recheck qualification at actual start. Scheduled start/end are planning data, not machine
    // commands.
    var q =
        s.one(
            "select * from mfg_qualification where employee_id=? and machine_id=?",
            a.get("employee_id"),
            a.get("machine_id"));
    s.require(
        !((java.sql.Date) q.get("valid_until"))
            .toLocalDate()
            .isBefore(LocalDate.now(ZoneOffset.UTC)),
        "QUALIFICATION_EXPIRED",
        "Operator qualification expired.");
    s.db.update("update mfg_order set state='RUNNING' where id=?", id);
    s.audit(id, "JOB_STARTED");
  }

  Map<String, Object> consume(UUID id, Consume r) {
    var o = lockedOrder(id);
    if (s.exists("select 1 from mfg_consumption where event_id=?", r.eventId())) {
      var old = s.one("select * from mfg_consumption where event_id=?", r.eventId());
      s.require(
          id.equals(old.get("order_id"))
              && r.lotId().equals(old.get("lot_id"))
              && r.kg().compareTo(s.decimal(old, "kg")) == 0,
          "EVENT_CONFLICT",
          "Event ID was used with a different payload.");
      return old;
    }
    running(o);
    var lot = s.one("select * from mfg_lot where id=? for update", r.lotId());
    s.product((UUID) lot.get("material_id"), "RAW_MATERIAL");
    s.require(
        "APPROVED".equals(lot.get("quality")),
        "LOT_NOT_APPROVED",
        "Only approved material can be consumed.");
    s.require(
        s.exists(
            "select 1 from mfg_component where recipe_id=? and material_id=?",
            o.get("recipe_id"),
            lot.get("material_id")),
        "WRONG_MATERIAL",
        "Material is not in the order recipe.");
    s.require(
        s.decimal(lot, "remaining_kg").compareTo(r.kg()) >= 0,
        "INSUFFICIENT_STOCK",
        "Material lot does not contain enough kilograms.");
    s.db.update("update mfg_lot set remaining_kg=remaining_kg-? where id=?", r.kg(), r.lotId());
    s.db.update(
        "insert into mfg_consumption values (?,?,?,?,?)",
        r.eventId(),
        id,
        r.lotId(),
        r.kg(),
        s.timestamp(Instant.now()));
    s.audit(id, "MATERIAL_CONSUMED");
    return s.one("select * from mfg_consumption where event_id=?", r.eventId());
  }

  Map<String, Object> output(UUID id, Output r) {
    var o = lockedOrder(id);
    if (s.exists("select 1 from mfg_output where event_id=?", r.eventId())) {
      var old = s.one("select * from mfg_output where event_id=?", r.eventId());
      s.require(
          id.equals(old.get("order_id"))
              && r.goodQty().compareTo(s.decimal(old, "good_qty")) == 0
              && r.rejectQty().compareTo(s.decimal(old, "reject_qty")) == 0
              && r.reason().equals(old.get("reason")),
          "EVENT_CONFLICT",
          "Event ID was used with a different payload.");
      return old;
    }
    running(o);
    s.require(
        r.goodQty().add(r.rejectQty()).signum() > 0,
        "EMPTY_OUTPUT",
        "Record at least one quantity.");
    var recipe = s.one("select * from mfg_recipe where id=?", o.get("recipe_id"));
    var product = s.one("select * from catalog_product where id=?", recipe.get("product_id"));
    if ("PIECE".equals(product.get("unit")))
      s.require(
          r.goodQty().stripTrailingZeros().scale() <= 0
              && r.rejectQty().stripTrailingZeros().scale() <= 0,
          "WHOLE_PIECES",
          "Piece quantities must be whole numbers.");
    s.require(
        s.decimal(o, "good_qty").add(r.goodQty()).compareTo(s.decimal(o, "planned_qty")) <= 0,
        "OVERPRODUCTION",
        "Good output cannot exceed the planned quantity in this increment.");
    s.db.update(
        "update mfg_order set good_qty=good_qty+?, reject_qty=reject_qty+? where id=?",
        r.goodQty(),
        r.rejectQty(),
        id);
    s.db.update(
        "insert into mfg_output values (?,?,?,?,?,?)",
        r.eventId(),
        id,
        r.goodQty(),
        r.rejectQty(),
        r.reason(),
        s.timestamp(Instant.now()));
    s.audit(id, "OUTPUT_RECORDED");
    return s.one("select * from mfg_output where event_id=?", r.eventId());
  }

  void downtime(UUID id, Downtime r) {
    var o = lockedOrder(id);
    running(o);
    s.interval(r.start(), r.end());
    s.require(
        !s.exists(
            "select 1 from mfg_downtime where order_id=? and starts_at<? and ends_at>?",
            id,
            s.timestamp(r.end()),
            s.timestamp(r.start())),
        "DOWNTIME_OVERLAP",
        "Downtime interval overlaps an existing event.");
    s.db.update(
        "insert into mfg_downtime values (?,?,?,?,?)",
        s.id(),
        id,
        s.timestamp(r.start()),
        s.timestamp(r.end()),
        r.reason());
    s.audit(id, "DOWNTIME_RECORDED");
  }

  Map<String, Object> complete(UUID id) {
    var o = lockedOrder(id);
    if ("COMPLETED".equals(o.get("state")))
      return s.one("select * from mfg_batch where order_id=?", id);
    running(o);
    s.require(
        s.decimal(o, "good_qty").signum() > 0,
        "NO_GOOD_OUTPUT",
        "A batch requires positive candidate-good output.");
    for (var c :
        s.db.queryForList(
            "select material_id from mfg_component where recipe_id=?", o.get("recipe_id")))
      s.require(
          s.exists(
              "select 1 from mfg_consumption c join mfg_lot l on c.lot_id=l.id where c.order_id=?"
                  + " and l.material_id=?",
              id,
              c.get("material_id")),
          "MISSING_CONSUMPTION",
          "Record actual consumption for every recipe component.");
    UUID batch = s.id();
    s.db.update(
        "insert into mfg_batch values (?,?,?,?)", batch, id, s.decimal(o, "good_qty"), "PENDING");
    s.db.update("update mfg_order set state='COMPLETED' where id=?", id);
    s.audit(id, "JOB_COMPLETED");
    return s.one("select * from mfg_batch where id=?", batch);
  }

  void abort(UUID id, Abort r) {
    var order = lockedOrder(id);
    s.require(
        Set.of("PLANNED", "SCHEDULED", "RUNNING").contains(order.get("state")),
        "ORDER_STATE",
        "Only an active order may be aborted.");
    s.require(
        s.decimal(order, "good_qty").signum() == 0,
        "GOOD_OUTPUT_PRESENT",
        "Complete and inspect existing candidate-good output instead of discarding it through"
            + " abort.");
    s.db.update(
        "update mfg_order set state='ABORTED',aborted_at=?,abort_reason=? where id=?",
        s.timestamp(Instant.now()),
        r.reason(),
        id);
    s.audit(id, "JOB_ABORTED", r.reason());
  }

  private Map<String, Object> lockedOrder(UUID id) {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean manager =
        auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PRODUCTION_MANAGER"));
    if (!manager
        && !s.exists(
            "select 1 from mfg_schedule sc join mfg_employee e on e.id=sc.employee_id where"
                + " sc.order_id=? and e.user_subject=?",
            id,
            auth.getName()))
      throw new com.plastiflow.shared.BusinessException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "NOT_ASSIGNED",
          "Operator may execute only their own assigned job.");
    return s.one("select * from mfg_order where id=? for update", id);
  }

  private void running(Map<String, Object> o) {
    s.require("RUNNING".equals(o.get("state")), "ORDER_STATE", "Operation requires a running job.");
  }
}
