package com.plastiflow.operations;

import static com.plastiflow.operations.Requests.*;

import com.plastiflow.manufacturing.MaintenanceGateway;
import com.plastiflow.shared.BusinessException;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class MaintenanceService {
  private final OpsStore s;
  private final MaintenanceGateway machines;

  MaintenanceService(OpsStore s, MaintenanceGateway machines) {
    this.s = s;
    this.machines = machines;
  }

  Map<String, Object> technician(Technician r) {
    UUID id = s.id();
    s.db.update(
        "insert into ops_technician values (?,?,?,?)", id, r.code(), r.name(), r.userSubject());
    s.audit(id, "TECHNICIAN_CREATED", "");
    return s.one("select id,code,name from ops_technician where id=?", id);
  }

  Map<String, Object> create(WorkOrder r) {
    s.require(
        (r.machineId() == null) != (r.assetId() == null),
        "WORK_TARGET",
        "Select exactly one machine or facility asset.");
    s.require(
        (r.kind() == WorkKind.FACILITY) == (r.assetId() != null),
        "WORK_KIND",
        "Facility work needs an asset; other work needs a machine.");
    UUID id = s.id();
    if (r.machineId() != null) {
      s.one("select * from mfg_machine where id=?", r.machineId());
      if (r.kind() == WorkKind.BREAKDOWN) machines.hold(r.machineId(), id);
    } else {
      s.one("select * from ops_asset where id=? for update", r.assetId());
      s.db.update("update ops_asset set state='UNAVAILABLE' where id=?", r.assetId());
    }
    s.db.update(
        "insert into ops_work_order"
            + " (id,code,machine_id,asset_id,kind,description,state,currency,created_at) values"
            + " (?,?,?,?,?,?,?,?,?)",
        id,
        r.code(),
        r.machineId(),
        r.assetId(),
        r.kind().name(),
        r.description(),
        "OPEN",
        r.currency(),
        s.time(Instant.now()));
    s.audit(id, "WORK_ORDER_CREATED", r.description());
    return detail(id);
  }

  Map<String, Object> assign(UUID id, Assignment r) {
    var work = locked(id);
    s.require(
        Set.of("OPEN", "ASSIGNED").contains(work.get("state")),
        "WORK_STATE",
        "Assign only open or assigned work.");
    s.one("select * from ops_technician where id=?", r.technicianId());
    s.db.update(
        "update ops_work_order set technician_id=?, state='ASSIGNED' where id=?",
        r.technicianId(),
        id);
    s.audit(id, "WORK_ASSIGNED", r.technicianId().toString());
    return detail(id);
  }

  void start(UUID id) {
    var work = locked(id);
    authorizeTechnician(work);
    s.require(
        "ASSIGNED".equals(work.get("state")),
        "WORK_STATE",
        "Work must be assigned before repair starts.");
    if (work.get("machine_id") != null) {
      UUID machine = (UUID) work.get("machine_id");
      machines.requireStopped(machine);
      machines.hold(machine, id);
    }
    s.db.update("update ops_work_order set state='IN_PROGRESS' where id=?", id);
    s.audit(id, "REPAIR_STARTED", "");
  }

  void submit(UUID id, Notes r) {
    var work = locked(id);
    authorizeTechnician(work);
    s.require(
        "IN_PROGRESS".equals(work.get("state")),
        "WORK_STATE",
        "Only repairs in progress may be submitted.");
    s.db.update(
        "update ops_work_order set state='REVIEW',repair_notes=?,submitted_at=? where id=?",
        r.notes(),
        s.time(Instant.now()),
        id);
    s.audit(id, "REPAIR_SUBMITTED", r.notes());
  }

  void release(UUID id, Notes r) {
    var work = locked(id);
    s.require(
        "REVIEW".equals(work.get("state")),
        "WORK_STATE",
        "Repair must be submitted for review first.");
    if (work.get("machine_id") != null) {
      s.require(
          s.role("MAINTENANCE_MANAGER"),
          "MANAGER_REQUIRED",
          "Machine return to service requires maintenance manager approval.");
      machines.requireStopped((UUID) work.get("machine_id"));
      machines.release((UUID) work.get("machine_id"), id);
    } else {
      UUID asset = (UUID) work.get("asset_id");
      s.one("select * from ops_asset where id=? for update", asset);
      var inspections =
          s.db.queryForList(
              "select * from ops_asset_inspection where asset_id=? order by created_at desc,id desc"
                  + " limit 1",
              asset);
      s.require(
          !inspections.isEmpty()
              && Boolean.TRUE.equals(inspections.get(0).get("passed"))
              && !s.instant(inspections.get(0).get("created_at"))
                  .isBefore(s.instant(work.get("submitted_at"))),
          "ASSET_INSPECTION_REQUIRED",
          "A passing facility inspection is required before return to service.");
      if (!s.exists(
          "select 1 from ops_work_order where asset_id=? and id<>? and state<>'CLOSED'", asset, id))
        s.db.update("update ops_asset set state='AVAILABLE' where id=?", asset);
    }
    s.db.update(
        "update ops_work_order set state='CLOSED',release_notes=? where id=?", r.notes(), id);
    s.audit(id, "RETURN_TO_SERVICE_APPROVED", r.notes());
  }

  Map<String, Object> plan(Plan r) {
    s.one("select * from mfg_machine where id=?", r.machineId());
    UUID id = s.id();
    s.db.update(
        "insert into ops_plan values (?,?,?,?,?,?)",
        id,
        r.machineId(),
        r.intervalDays(),
        java.sql.Date.valueOf(r.nextDue()),
        r.instructions(),
        r.currency());
    s.audit(id, "PREVENTIVE_PLAN_CREATED", "");
    return s.one("select * from ops_plan where id=?", id);
  }

  Map<String, Object> generate(UUID planId) {
    var plan = s.one("select * from ops_plan where id=? for update", planId);
    LocalDate due = ((java.sql.Date) plan.get("next_due")).toLocalDate();
    s.require(
        !due.isAfter(LocalDate.now(ZoneOffset.UTC)), "NOT_DUE", "Preventive plan is not due yet.");
    UUID id = s.id();
    String code = "PM-" + id.toString().replace("-", "");
    s.db.update(
        "insert into ops_work_order"
            + " (id,code,machine_id,plan_id,due_date,kind,description,state,currency,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        id,
        code,
        plan.get("machine_id"),
        planId,
        java.sql.Date.valueOf(due),
        "PREVENTIVE",
        plan.get("instructions"),
        "OPEN",
        plan.get("currency"),
        s.time(Instant.now()));
    s.db.update(
        "update ops_plan set next_due=? where id=?",
        java.sql.Date.valueOf(due.plusDays((Integer) plan.get("interval_days"))),
        planId);
    s.audit(id, "PREVENTIVE_WORK_GENERATED", due.toString());
    return detail(id);
  }

  void window(UUID id, Window r) {
    var work = locked(id);
    s.require(
        work.get("machine_id") != null && Set.of("OPEN", "ASSIGNED").contains(work.get("state")),
        "WORK_STATE",
        "Book windows only for open machine work.");
    machines.reserveWindow((UUID) work.get("machine_id"), id, r.start(), r.end(), r.reason());
    s.audit(id, "WORK_WINDOW_BOOKED", r.reason());
  }

  Map<String, Object> receipt(PartReceipt r) {
    s.one("select * from catalog_product where id=? for update", r.productId());
    if (s.exists("select 1 from ops_part_event where event_id=?", r.eventId())) {
      var old = s.one("select * from ops_part_event where event_id=?", r.eventId());
      samePart(old, r.productId(), r.quantity(), r.unitCost(), r.currency(), null, "RECEIPT");
      return old;
    }
    part(r.productId(), r.quantity());
    if (!s.exists("select 1 from ops_part_stock where product_id=?", r.productId()))
      s.db.update("insert into ops_part_stock values (?,?)", r.productId(), BigDecimal.ZERO);
    s.one("select * from ops_part_stock where product_id=? for update", r.productId());
    s.db.update(
        "update ops_part_stock set quantity=quantity+? where product_id=?",
        r.quantity(),
        r.productId());
    s.db.update(
        "insert into ops_part_event values (?,?,?,?,?,?,?,?)",
        r.eventId(),
        r.productId(),
        null,
        "RECEIPT",
        r.quantity(),
        r.unitCost(),
        r.currency(),
        s.time(Instant.now()));
    s.audit(r.eventId(), "SPARE_RECEIVED", "");
    return s.one("select * from ops_part_event where event_id=?", r.eventId());
  }

  Map<String, Object> use(UUID id, PartUse r) {
    var work = locked(id);
    authorizeTechnician(work);
    if (s.exists("select 1 from ops_part_event where event_id=?", r.eventId())) {
      var old = s.one("select * from ops_part_event where event_id=?", r.eventId());
      samePart(
          old,
          r.productId(),
          r.quantity(),
          r.unitCost(),
          (String) work.get("currency"),
          id,
          "USAGE");
      return old;
    }
    s.require(
        "IN_PROGRESS".equals(work.get("state")),
        "WORK_STATE",
        "Parts may be consumed only during repair.");
    part(r.productId(), r.quantity());
    var stock = s.one("select * from ops_part_stock where product_id=? for update", r.productId());
    s.require(
        ((BigDecimal) stock.get("quantity")).compareTo(r.quantity()) >= 0,
        "INSUFFICIENT_PARTS",
        "Not enough spare parts.");
    s.db.update(
        "update ops_part_stock set quantity=quantity-? where product_id=?",
        r.quantity(),
        r.productId());
    s.db.update(
        "insert into ops_part_event values (?,?,?,?,?,?,?,?)",
        r.eventId(),
        r.productId(),
        id,
        "USAGE",
        r.quantity(),
        r.unitCost(),
        work.get("currency"),
        s.time(Instant.now()));
    s.audit(id, "SPARE_CONSUMED", r.productId().toString());
    return s.one("select * from ops_part_event where event_id=?", r.eventId());
  }

  Map<String, Object> labor(UUID id, Labor r) {
    var work = locked(id);
    authorizeTechnician(work);
    if (s.exists("select 1 from ops_labor where event_id=?", r.eventId())) {
      var old = s.one("select * from ops_labor where event_id=?", r.eventId());
      s.require(
          id.equals(old.get("work_order_id"))
              && r.hours().compareTo((BigDecimal) old.get("hours")) == 0
              && r.hourlyRate().compareTo((BigDecimal) old.get("hourly_rate")) == 0,
          "EVENT_CONFLICT",
          "Labor event already has another payload.");
      return old;
    }
    s.require(
        "IN_PROGRESS".equals(work.get("state")),
        "WORK_STATE",
        "Labor may be recorded only during repair.");
    s.db.update(
        "insert into ops_labor values (?,?,?,?,?)",
        r.eventId(),
        id,
        r.hours(),
        r.hourlyRate(),
        s.time(Instant.now()));
    s.audit(id, "LABOR_RECORDED", "");
    return s.one("select * from ops_labor where event_id=?", r.eventId());
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var work = new LinkedHashMap<>(s.one("select * from ops_work_order where id=?", id));
    if (s.role("MAINTENANCE_TECHNICIAN")) authorizeTechnician(work);
    work.put("parts", s.db.queryForList("select * from ops_part_event where work_order_id=?", id));
    work.put("labor", s.db.queryForList("select * from ops_labor where work_order_id=?", id));
    BigDecimal partCost =
        s.db.queryForObject(
            "select coalesce(sum(quantity*unit_cost),0) from ops_part_event where work_order_id=?",
            BigDecimal.class,
            id);
    BigDecimal laborCost =
        s.db.queryForObject(
            "select coalesce(sum(hours*hourly_rate),0) from ops_labor where work_order_id=?",
            BigDecimal.class,
            id);
    work.put("total_cost", partCost.add(laborCost));
    return work;
  }

  private Map<String, Object> locked(UUID id) {
    return s.one("select * from ops_work_order where id=? for update", id);
  }

  private void authorizeTechnician(Map<String, Object> work) {
    if (s.role("MAINTENANCE_MANAGER")) return;
    if (work.get("technician_id") == null
        || !s.exists(
            "select 1 from ops_technician where id=? and user_subject=?",
            work.get("technician_id"),
            s.actor()))
      throw new BusinessException(
          HttpStatus.FORBIDDEN,
          "NOT_ASSIGNED",
          "Technician may work only on their own assigned orders.");
  }

  private void part(UUID product, BigDecimal quantity) {
    var p = s.one("select * from catalog_product where id=?", product);
    s.require(
        "SPARE_PART".equals(p.get("kind")) && Boolean.TRUE.equals(p.get("active")),
        "INVALID_PART",
        "Use an active spare-part product.");
    if ("PIECE".equals(p.get("unit")))
      s.require(
          quantity.stripTrailingZeros().scale() <= 0,
          "WHOLE_PIECES",
          "Piece quantities must be whole numbers.");
  }

  private void samePart(
      Map<String, Object> old,
      UUID product,
      BigDecimal qty,
      BigDecimal cost,
      String currency,
      UUID work,
      String kind) {
    s.require(
        product.equals(old.get("product_id"))
            && qty.compareTo((BigDecimal) old.get("quantity")) == 0
            && cost.compareTo((BigDecimal) old.get("unit_cost")) == 0
            && currency.equals(old.get("currency"))
            && Objects.equals(work, old.get("work_order_id"))
            && kind.equals(old.get("kind")),
        "EVENT_CONFLICT",
        "Part event already has another payload.");
  }
}
