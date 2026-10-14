package com.plastiflow.manufacturing;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public module boundary: operations may hold/release machines without owning their tables. */
@Service
@Transactional
public class MaintenanceGateway {
  private final Store s;

  MaintenanceGateway(Store s) {
    this.s = s;
  }

  public void hold(UUID machine, UUID hold) {
    var m = s.one("select * from mfg_machine where id=? for update", machine);
    if (s.exists("select 1 from mfg_machine_hold where hold_id=?", hold)) return;
    var existing =
        s.db.queryForList("select restore_state from mfg_machine_hold where machine_id=?", machine);
    String restore =
        existing.isEmpty()
            ? (String) m.get("state")
            : (String) existing.get(0).get("restore_state");
    s.db.update("insert into mfg_machine_hold values (?,?,?)", hold, machine, restore);
    s.db.update("update mfg_machine set state='UNAVAILABLE' where id=?", machine);
    s.audit(machine, "MAINTENANCE_HOLD");
  }

  public void release(UUID machine, UUID hold) {
    s.one("select * from mfg_machine where id=? for update", machine);
    var rows =
        s.db.queryForList(
            "select * from mfg_machine_hold where hold_id=? and machine_id=?", hold, machine);
    if (rows.isEmpty()) return;
    String restore = (String) rows.get(0).get("restore_state");
    s.db.update("delete from mfg_machine_hold where hold_id=?", hold);
    if (!s.exists("select 1 from mfg_machine_hold where machine_id=?", machine))
      s.db.update("update mfg_machine set state=? where id=?", restore, machine);
    s.audit(machine, "MAINTENANCE_RELEASE");
  }

  public void requireStopped(UUID machine) {
    s.one("select * from mfg_machine where id=? for update", machine);
    s.require(
        !s.exists(
            "select 1 from mfg_schedule sc join mfg_order o on o.id=sc.order_id where"
                + " sc.machine_id=? and o.state='RUNNING'",
            machine),
        "PRODUCTION_RUNNING",
        "Close the active ERP production job before starting repair work.");
  }

  public void reserveWindow(
      UUID machine, UUID workOrder, Instant start, Instant end, String reason) {
    s.interval(start, end);
    s.one("select * from mfg_machine where id=? for update", machine);
    s.require(
        !s.exists(
            "select 1 from mfg_schedule sc join mfg_order o on o.id=sc.order_id where"
                + " sc.machine_id=? and o.state in ('SCHEDULED','RUNNING') and sc.starts_at<? and"
                + " sc.ends_at>?",
            machine,
            s.timestamp(end),
            s.timestamp(start)),
        "SCHEDULE_CONFLICT",
        "Reschedule production before booking maintenance.");
    s.require(
        !s.exists(
            "select 1 from mfg_maintenance_block where machine_id=? and cancelled=false and"
                + " starts_at<? and ends_at>?",
            machine,
            s.timestamp(end),
            s.timestamp(start)),
        "MAINTENANCE_OVERLAP",
        "Another maintenance window already occupies the interval.");
    s.db.update(
        "insert into mfg_maintenance_block (id,machine_id,starts_at,ends_at,reason) values"
            + " (?,?,?,?,?)",
        s.id(),
        machine,
        s.timestamp(start),
        s.timestamp(end),
        reason.substring(0, Math.min(reason.length(), 180)) + " [work order " + workOrder + "]");
    s.audit(machine, "WORK_ORDER_WINDOW_BOOKED");
  }
}
