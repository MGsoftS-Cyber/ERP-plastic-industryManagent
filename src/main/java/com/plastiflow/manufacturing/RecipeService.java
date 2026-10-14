package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class RecipeService {
  private final Store s;

  RecipeService(Store s) {
    this.s = s;
  }

  Map<String, Object> create(Recipe r) {
    // Lock the output product to serialize revision allocation across concurrent requests.
    s.one("select * from catalog_product where id=? for update", r.productId());
    s.product(r.productId(), "FINISHED_GOOD");
    var seen = new HashSet<UUID>();
    for (var c : r.components()) {
      s.require(seen.add(c.materialId()), "DUPLICATE_COMPONENT", "Each material must appear once.");
      var p = s.product(c.materialId(), "RAW_MATERIAL");
      s.require(
          "KG".equals(p.get("unit")),
          "MATERIAL_UNIT",
          "This increment supports raw material consumption in KG.");
    }
    int revision =
        s.db.queryForObject(
            "select coalesce(max(revision),0)+1 from mfg_recipe where product_id=?",
            Integer.class,
            r.productId());
    UUID id = s.id();
    s.db.update(
        "insert into mfg_recipe values (?,?,?,?,?,?,?,?)",
        id,
        r.productId(),
        revision,
        r.process().name(),
        r.yieldFactor(),
        s.encode(r.settings()),
        r.packaging(),
        s.timestamp(java.time.Instant.now()));
    for (var c : r.components())
      s.db.update("insert into mfg_component values (?,?,?)", id, c.materialId(), c.kgPerUnit());
    for (int i = 0; i < r.operations().size(); i++) {
      var o = r.operations().get(i);
      s.db.update(
          "insert into mfg_operation values (?,?,?,?)", id, i + 1, o.name(), o.minutesPerUnit());
    }
    s.audit(id, "RECIPE_VERSION_CREATED");
    return detail(id);
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var result = new LinkedHashMap<>(s.one("select * from mfg_recipe where id=?", id));
    result.put(
        "components",
        s.db.queryForList(
            "select * from mfg_component where recipe_id=? order by material_id", id));
    result.put(
        "operations",
        s.db.queryForList(
            "select * from mfg_operation where recipe_id=? order by sequence_no", id));
    return result;
  }
}
