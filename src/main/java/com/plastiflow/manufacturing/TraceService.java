package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class TraceService {
  private final Store s;

  TraceService(Store s) {
    this.s = s;
  }

  Map<String, Object> pallet(Pallet r) {
    var b = s.one("select * from mfg_batch where id=? for update", r.batchId());
    s.require(
        "APPROVED".equals(b.get("quality")),
        "BATCH_NOT_APPROVED",
        "Only approved output may be packed.");
    BigDecimal allocated =
        s.db.queryForObject(
            "select coalesce(sum(qty),0) from mfg_pallet where batch_id=?",
            BigDecimal.class,
            r.batchId());
    s.require(
        allocated.add(r.qty()).compareTo(s.decimal(b, "qty")) <= 0,
        "PALLET_OVERALLOCATION",
        "Pallets exceed the batch output.");
    var p =
        s.one(
            "select p.unit from catalog_product p join mfg_recipe r on r.product_id=p.id join"
                + " mfg_order o on o.recipe_id=r.id where o.id=?",
            b.get("order_id"));
    if ("PIECE".equals(p.get("unit")))
      s.require(
          r.qty().stripTrailingZeros().scale() <= 0,
          "WHOLE_PIECES",
          "Pallet piece quantities must be whole numbers.");
    UUID id = s.id();
    s.db.update("insert into mfg_pallet values (?,?,?,?)", id, r.code(), r.batchId(), r.qty());
    s.audit(id, "PALLET_CREATED");
    return s.one("select * from mfg_pallet where id=?", id);
  }

  Map<String, Object> dispatch(Dispatch r) {
    var p = s.one("select * from mfg_pallet where id=?", r.palletId());
    var batch = s.one("select * from mfg_batch where id=? for update", p.get("batch_id"));
    s.require(
        "APPROVED".equals(batch.get("quality")),
        "BATCH_NOT_APPROVED",
        "Quality hold blocks linking a dispatch.");
    UUID id = s.id();
    s.db.update(
        "insert into mfg_dispatch_reference values (?,?,?,?)",
        id,
        r.palletId(),
        r.shipmentReference(),
        r.customerReference());
    s.audit(id, "DISPATCH_REFERENCE_LINKED");
    return s.one("select * from mfg_dispatch_reference where id=?", id);
  }

  @Transactional(readOnly = true)
  Map<String, Object> batch(UUID id) {
    var b = s.one("select * from mfg_batch where id=?", id);
    var result = new LinkedHashMap<String, Object>();
    result.put("batch", b);
    result.put("order", s.one("select * from mfg_order where id=?", b.get("order_id")));
    result.put(
        "source_lots",
        s.db.queryForList(
            "select l.id,l.code,l.material_id,l.supplier_reference,l.location,c.kg,c.event_id from"
                + " mfg_consumption c join mfg_lot l on l.id=c.lot_id where c.order_id=? order by"
                + " l.code",
            b.get("order_id")));
    result.put(
        "recovery_links",
        s.db.queryForList(
            "select r.*,w.lot_id source_lot_id,w.classification from fin_recovery r join fin_scrap"
                + " w on w.id=r.scrap_id join mfg_consumption c on c.lot_id=r.output_lot_id where"
                + " c.order_id=?",
            b.get("order_id")));
    result.put(
        "pallets",
        s.db.queryForList("select * from mfg_pallet where batch_id=? order by code", id));
    result.put(
        "dispatch_references",
        s.db.queryForList(
            "select d.* from mfg_dispatch_reference d join mfg_pallet p on p.id=d.pallet_id where"
                + " p.batch_id=?",
            id));
    return result;
  }

  @Transactional(readOnly = true)
  Map<String, Object> lot(UUID id) {
    return Map.of(
        "lot",
        s.one("select * from mfg_lot where id=?", id),
        "consumption_orders",
        s.db.queryForList(
            "select o.id,o.code,o.state,c.event_id,c.kg,c.created_at from mfg_consumption c join"
                + " mfg_order o on o.id=c.order_id where c.lot_id=? order by c.created_at",
            id),
        "downstream_batches",
        s.db.queryForList(
            "select distinct b.* from mfg_batch b join mfg_consumption c on c.order_id=b.order_id"
                + " where c.lot_id=?",
            id));
  }
}
