package com.plastiflow.operations;

import static com.plastiflow.operations.Requests.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class FacilitiesService {
  private final OpsStore s;

  FacilitiesService(OpsStore s) {
    this.s = s;
  }

  Map<String, Object> area(Area r) {
    UUID id = s.id();
    s.db.update("insert into ops_area values (?,?,?)", id, r.code(), r.name());
    s.audit(id, "AREA_CREATED", "");
    return s.one("select * from ops_area where id=?", id);
  }

  Map<String, Object> asset(Asset r) {
    s.one("select * from ops_area where id=?", r.areaId());
    UUID id = s.id();
    s.db.update(
        "insert into ops_asset values (?,?,?,?,?,?)",
        id,
        r.code(),
        r.areaId(),
        r.name(),
        r.kind(),
        "UNAVAILABLE");
    s.audit(id, "ASSET_CREATED", "Inspection required before availability");
    return s.one("select * from ops_asset where id=?", id);
  }

  Map<String, Object> inspection(UUID id, Inspection r) {
    s.one("select * from ops_asset where id=? for update", id);
    UUID inspection = s.id();
    s.db.update(
        "insert into ops_asset_inspection values (?,?,?,?,?,?)",
        inspection,
        id,
        r.passed(),
        r.notes(),
        s.actor(),
        s.time(Instant.now()));
    boolean open =
        s.exists("select 1 from ops_work_order where asset_id=? and state<>'CLOSED'", id);
    s.db.update(
        "update ops_asset set state=? where id=?",
        r.passed() && !open ? "AVAILABLE" : "UNAVAILABLE",
        id);
    s.audit(id, "ASSET_INSPECTED", r.notes());
    return s.one("select * from ops_asset_inspection where id=?", inspection);
  }

  Map<String, Object> contract(Contract r) {
    s.one("select * from ops_asset where id=?", r.assetId());
    s.require(
        !r.endsOn().isBefore(r.startsOn()),
        "INVALID_INTERVAL",
        "Contract end must not precede start.");
    UUID id = s.id();
    s.db.update(
        "insert into ops_service_contract values (?,?,?,?,?,?,?)",
        id,
        r.assetId(),
        r.supplierReference(),
        java.sql.Date.valueOf(r.startsOn()),
        java.sql.Date.valueOf(r.endsOn()),
        r.amount(),
        r.currency());
    s.audit(id, "SERVICE_CONTRACT_CREATED", r.supplierReference());
    return s.one("select * from ops_service_contract where id=?", id);
  }
}
