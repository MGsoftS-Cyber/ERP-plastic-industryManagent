package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class EquipmentService {
  private final Store s;

  EquipmentService(Store s) {
    this.s = s;
  }

  Map<String, Object> machine(Machine r) {
    UUID id = s.id();
    s.db.update(
        "insert into mfg_machine values (?,?,?,?,?,?)",
        id,
        r.code().strip(),
        r.name().strip(),
        r.process().name(),
        r.capacityKg(),
        "AVAILABLE");
    s.audit(id, "MACHINE_CREATED");
    return s.one("select * from mfg_machine where id=?", id);
  }

  Map<String, Object> mold(Mold r) {
    UUID id = s.id();
    s.db.update(
        "insert into mfg_mold values (?,?,?,?,?)",
        id,
        r.code().strip(),
        r.name().strip(),
        r.cavities(),
        "AVAILABLE");
    s.audit(id, "MOLD_CREATED");
    return s.one("select * from mfg_mold where id=?", id);
  }

  void compatibility(UUID machine, UUID mold) {
    s.one("select * from mfg_machine where id=? for update", machine);
    s.one("select * from mfg_mold where id=? for update", mold);
    if (!s.exists(
        "select 1 from mfg_compatibility where machine_id=? and mold_id=?", machine, mold))
      s.db.update("insert into mfg_compatibility values (?,?)", machine, mold);
    s.audit(machine, "MOLD_COMPATIBILITY_ADDED");
  }

  void machineState(UUID id, State r) {
    s.one("select * from mfg_machine where id=? for update", id);
    s.require(
        r.state() != EquipmentState.AVAILABLE
            || !s.exists("select 1 from mfg_machine_hold where machine_id=?", id),
        "MAINTENANCE_HOLD",
        "Release all maintenance work orders before making the machine available.");
    s.db.update("update mfg_machine set state=? where id=?", r.state().name(), id);
    s.audit(id, "MACHINE_" + r.state());
  }

  void moldState(UUID id, State r) {
    s.one("select * from mfg_mold where id=? for update", id);
    s.db.update("update mfg_mold set state=? where id=?", r.state().name(), id);
    s.audit(id, "MOLD_" + r.state());
  }
}
