package com.plastiflow.finance;

import static com.plastiflow.finance.Requests.*;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class CostRecyclingService {
  final FinanceStore s;

  CostRecyclingService(FinanceStore s) {
    this.s = s;
  }

  Map<String, Object> valuation(Valuation r) {
    s.lock();
    s.currency(r.currency());
    s.one("select * from mfg_lot where id=?", r.lotId());
    s.db.update(
        "insert into fin_lot_value values(?,?,?,?)",
        r.lotId(),
        r.currency(),
        r.unitCost(),
        r.evidence());
    s.audit(r.lotId(), "LOT_VALUE_RECORDED", r);
    return Map.of("id", r.lotId());
  }

  Map<String, Object> cost(Costing r) {
    s.lock();
    s.currency(r.currency());
    var order =
        s.one("select * from mfg_order where id=? and state='COMPLETED' for update", r.orderId());
    var batch =
        s.one(
            "select * from mfg_batch where order_id=? and quality='APPROVED' for update",
            r.orderId());
    s.require(
        s.db.queryForList("select id from fin_cost where order_id=?", r.orderId()).isEmpty(),
        "COST_ALREADY_CAPTURED",
        "This order already has an immutable costing snapshot.");
    BigDecimal material = BigDecimal.ZERO, allocated = BigDecimal.ZERO;
    var sources = new ArrayList<Map<String, Object>>();
    var materialRows =
        s.db.queryForList(
            "select c.lot_id,sum(c.kg) kg from mfg_consumption c where c.order_id=? group by"
                + " c.lot_id",
            r.orderId());
    s.require(
        !materialRows.isEmpty(), "NO_CONSUMPTION", "Actual material consumption is required.");
    for (var row : materialRows) {
      var v = s.one("select * from fin_lot_value where lot_id=?", row.get("lot_id"));
      s.require(
          r.currency().equals(v.get("currency")),
          "CURRENCY_MISMATCH",
          "Every source cost must use the costing currency.");
      BigDecimal amount = s.amount(row, "kg").multiply(s.amount(v, "unit_cost"));
      material = material.add(amount);
      sources.add(
          Map.of(
              "lotId",
              row.get("lot_id"),
              "kg",
              row.get("kg"),
              "unitCost",
              v.get("unit_cost"),
              "unroundedCost",
              amount,
              "evidence",
              v.get("evidence")));
    }
    UUID id = s.id();
    var pending = new HashMap<String, BigDecimal>();
    for (var a : r.allocations()) {
      BigDecimal cap;
      Map<String, Object> source;
      if (a.kind() == AllocationKind.EXPENSE) {
        source =
            s.one(
                "select e.*,p.currency from fin_expense e join fin_budget b on b.id=e.budget_id"
                    + " join fin_period p on p.id=b.period_id where e.id=? and e.state='POSTED'",
                a.sourceId());
        cap = s.amount(source, "amount");
      } else {
        source =
            s.one(
                "select l.*,p.currency,p.money_scale from wf_pay_line l join wf_period p on"
                    + " p.id=l.period_id where l.id=? and p.state='FINALIZED'",
                a.sourceId());
        s.require(
            ((Number) source.get("money_scale")).intValue() == 2,
            "MONEY_SCALE",
            "Allocation requires two-decimal payroll.");
        cap = s.amount(source, "gross");
      }
      s.require(
          r.currency().equals(source.get("currency")),
          "CURRENCY_MISMATCH",
          "Allocation currencies must match.");
      String key = a.kind() + ":" + a.sourceId();
      BigDecimal prior =
          s.sum(
              "select sum(amount) from fin_allocation where source_type=? and source_id=?",
              a.kind().name(),
              a.sourceId());
      BigDecimal thisRequest = pending.getOrDefault(key, BigDecimal.ZERO).add(a.amount());
      s.require(
          prior.add(thisRequest).compareTo(cap) <= 0,
          "ALLOCATION_EXCEEDED",
          "Allocations cannot exceed their source expense or gross pay line.");
      pending.put(key, thisRequest);
      allocated = allocated.add(a.amount());
    }
    material = material.setScale(2, RoundingMode.HALF_UP);
    BigDecimal total = material.add(allocated),
        qty = s.amount(batch, "qty"),
        unit = total.divide(qty, 6, RoundingMode.HALF_UP);
    s.db.update(
        "insert into fin_cost values(?,?,?,?,?,?,?,?,?)",
        id,
        r.orderId(),
        r.currency(),
        material,
        allocated,
        total,
        unit,
        qty,
        s.encode(
            Map.of(
                "materials",
                sources,
                "allocations",
                r.allocations(),
                "rejectQty",
                order.get("reject_qty"),
                "basis",
                "Actual consumption; accepted batch units. Analytic snapshot, no GL posting.")));
    for (var a : r.allocations())
      s.db.update(
          "insert into fin_allocation values(?,?,?,?,?)",
          s.id(),
          id,
          a.kind().name(),
          a.sourceId(),
          a.amount());
    s.audit(id, "COST_CAPTURED", r);
    return s.one("select * from fin_cost where id=?", id);
  }

  Map<String, Object> scrap(Scrap r) {
    s.lock();
    var lot =
        s.one(
            "select l.*,p.kind,p.unit from mfg_lot l join catalog_product p on p.id=l.material_id"
                + " where l.id=? for update",
            r.lotId());
    s.require(
        "RAW_MATERIAL".equals(lot.get("kind")) && "KG".equals(lot.get("unit")),
        "SCRAP_MATERIAL",
        "Recycling currently supports kilogram raw-material lots.");
    s.require(
        s.amount(lot, "remaining_kg").compareTo(r.kg()) >= 0,
        "INSUFFICIENT_STOCK",
        "Scrap transfer exceeds remaining source stock.");
    s.db.update("update mfg_lot set remaining_kg=remaining_kg-? where id=?", r.kg(), r.lotId());
    UUID id = s.id();
    s.db.update(
        "insert into fin_scrap values(?,?,?,?,?,?,?)",
        id,
        r.code(),
        r.lotId(),
        r.kg(),
        r.kg(),
        r.classification(),
        r.reason());
    s.audit(id, "STOCK_TO_SCRAP", r);
    return Map.of("id", id);
  }

  Map<String, Object> recover(UUID scrap, Recovery r) {
    s.lock();
    var w = s.one("select * from fin_scrap where id=? for update", scrap);
    s.require(
        s.amount(w, "remaining_kg").compareTo(r.inputKg()) >= 0,
        "INSUFFICIENT_SCRAP",
        "Recovery input exceeds available scrap.");
    s.require(
        r.recoveredKg().compareTo(r.inputKg()) <= 0,
        "MASS_BALANCE",
        "Recovered mass cannot exceed input mass.");
    var l = s.one("select * from mfg_lot where id=?", w.get("lot_id"));
    UUID lot = s.id();
    s.db.update(
        "insert into"
            + " mfg_lot(id,code,material_id,supplier_reference,location,received_kg,remaining_kg,quality)"
            + " values(?,?,?,?,?,?,?,'PENDING')",
        lot,
        r.lotCode(),
        l.get("material_id"),
        "RECOVERY:" + r.eventId(),
        r.location(),
        r.recoveredKg(),
        r.recoveredKg());
    s.db.update(
        "insert into fin_recovery values(?,?,?,?,?,?,?)",
        r.eventId(),
        scrap,
        r.inputKg(),
        r.recoveredKg(),
        r.inputKg().subtract(r.recoveredKg()),
        lot,
        r.dispositionReference());
    s.db.update("update fin_scrap set remaining_kg=remaining_kg-? where id=?", r.inputKg(), scrap);
    s.audit(
        r.eventId(),
        "RECOVERY_RECORDED",
        Map.of("request", r, "sourceLot", l.get("id"), "outputLot", lot));
    return Map.of("id", r.eventId(), "outputLotId", lot, "quality", "PENDING");
  }

  @Transactional(readOnly = true)
  Map<String, Object> recoveryTrace(UUID lot) {
    var recovery =
        s.one(
            "select r.*,w.lot_id source_lot_id,w.classification,w.reason,l.code"
                + " source_lot_code,l.supplier_reference from fin_recovery r join fin_scrap w on"
                + " w.id=r.scrap_id join mfg_lot l on l.id=w.lot_id where r.output_lot_id=?",
            lot);
    var ancestors = new ArrayList<Map<String, Object>>();
    var current = recovery;
    for (int depth = 0; depth < 1000; depth++) {
      ancestors.add(current);
      var previous =
          s.db.queryForList(
              "select r.*,w.lot_id source_lot_id,l.code source_lot_code,l.supplier_reference from"
                  + " fin_recovery r join fin_scrap w on w.id=r.scrap_id join mfg_lot l on"
                  + " l.id=w.lot_id where r.output_lot_id=?",
              current.get("source_lot_id"));
      if (previous.isEmpty()) break;
      current = previous.get(0);
    }
    return Map.of(
        "recovery",
        recovery,
        "ancestors",
        ancestors,
        "outputLot",
        s.one("select * from mfg_lot where id=?", lot));
  }
}
