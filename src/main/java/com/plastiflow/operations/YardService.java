package com.plastiflow.operations;

import static com.plastiflow.operations.Requests.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class YardService {
  private final OpsStore s;

  YardService(OpsStore s) {
    this.s = s;
  }

  private void lock() {
    s.one("select * from ops_yard_lock where id=1 for update");
  }

  Map<String, Object> space(Space r) {
    lock();
    UUID id = s.id();
    s.db.update("insert into ops_yard_space values (?,?,?,?)", id, r.code(), r.kind().name(), true);
    s.audit(id, "YARD_SPACE_CREATED", "");
    return s.one("select * from ops_yard_space where id=?", id);
  }

  Map<String, Object> truck(Truck r) {
    lock();
    s.interval(r.plannedStart(), r.plannedEnd());
    UUID id = s.id();
    s.db.update(
        "insert into ops_truck_visit"
            + " (id,reference,carrier,driver,tractor_plate,trailer_plate,purpose,document_reference,planned_start,planned_end,state,documents_checked)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        r.reference(),
        r.carrier(),
        r.driver(),
        r.tractorPlate().strip().toUpperCase(Locale.ROOT),
        r.trailerPlate().strip().toUpperCase(Locale.ROOT),
        r.purpose().name(),
        r.documentReference(),
        s.time(r.plannedStart()),
        s.time(r.plannedEnd()),
        "SCHEDULED",
        false);
    event(id, "SCHEDULED", "Appointment created");
    return detail(id);
  }

  Map<String, Object> booking(UUID id, Booking r) {
    lock();
    var visit = s.one("select * from ops_truck_visit where id=?", id);
    s.require(
        Set.of("SCHEDULED", "ARRIVED", "GATE_CHECKED", "WAITING").contains(visit.get("state")),
        "VISIT_STATE",
        "Dock booking is locked after docking or cancellation.");
    s.interval(r.start(), r.end());
    var space = s.one("select * from ops_yard_space where id=?", r.spaceId());
    s.require(
        "DOCK".equals(space.get("kind")) && Boolean.TRUE.equals(space.get("active")),
        "DOCK_REQUIRED",
        "Select an active dock.");
    s.require(
        !s.exists(
            "select 1 from ops_dock_booking b join ops_truck_visit v on v.id=b.visit_id where"
                + " b.visit_id<>? and b.space_id=? and b.starts_at<? and b.ends_at>? and v.state"
                + " not in ('CANCELLED','REJECTED','DEPARTED')",
            id,
            r.spaceId(),
            s.time(r.end()),
            s.time(r.start())),
        "DOCK_BOOKING_OVERLAP",
        "Dock is already booked during this interval.");
    s.db.update("delete from ops_dock_booking where visit_id=?", id);
    s.db.update(
        "insert into ops_dock_booking values (?,?,?,?)",
        id,
        r.spaceId(),
        s.time(r.start()),
        s.time(r.end()));
    s.audit(id, "DOCK_BOOKED", r.spaceId().toString());
    return detail(id);
  }

  void arrive(UUID id, TruckNotes r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "SCHEDULED");
    s.require(
        !s.exists(
            "select 1 from ops_truck_visit where id<>? and (tractor_plate=? or trailer_plate=?) and"
                + " state in"
                + " ('ARRIVED','GATE_CHECKED','WAITING','AT_DOCK','HANDLING','READY_EXIT')",
            id,
            v.get("tractor_plate"),
            v.get("trailer_plate")),
        "VEHICLE_ALREADY_INSIDE",
        "Tractor or trailer already has an active visit.");
    s.db.update(
        "update ops_truck_visit set state='ARRIVED',entry_at=? where id=?",
        s.time(Instant.now()),
        id);
    event(id, "ARRIVED", r.notes());
  }

  void gate(UUID id, GateCheck r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "ARRIVED");
    s.db.update(
        "update ops_truck_visit set state=?,gate_notes=? where id=?",
        r.accepted() ? "GATE_CHECKED" : "REJECTED",
        r.notes(),
        id);
    event(id, r.accepted() ? "GATE_CHECKED" : "REJECTED", r.notes());
  }

  void assign(UUID id, SpaceAssignment r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    s.require(
        Set.of("GATE_CHECKED", "WAITING").contains(v.get("state")),
        "VISIT_STATE",
        "Gate approval is required before assigning parking or a dock.");
    var space = s.one("select * from ops_yard_space where id=?", r.spaceId());
    s.require(Boolean.TRUE.equals(space.get("active")), "SPACE_UNAVAILABLE", "Space unavailable.");
    s.require(
        !s.exists(
            "select 1 from ops_truck_visit where id<>? and space_id=? and state in"
                + " ('WAITING','AT_DOCK','HANDLING','READY_EXIT')",
            id,
            r.spaceId()),
        "SPACE_OCCUPIED",
        "Another truck occupies the space.");
    boolean dock = "DOCK".equals(space.get("kind"));
    if (dock) {
      var booking = s.one("select * from ops_dock_booking where visit_id=?", id);
      Instant now = Instant.now();
      s.require(
          r.spaceId().equals(booking.get("space_id"))
              && !now.isBefore(s.instant(booking.get("starts_at")))
              && now.isBefore(s.instant(booking.get("ends_at"))),
          "BOOKING_REQUIRED",
          "Dock assignment must match a currently valid booking.");
    }
    s.db.update(
        "update ops_truck_visit set space_id=?,state=? where id=?",
        r.spaceId(),
        dock ? "AT_DOCK" : "WAITING",
        id);
    event(id, dock ? "AT_DOCK" : "WAITING", r.spaceId().toString());
  }

  void handling(UUID id, TruckNotes r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "AT_DOCK");
    s.db.update("update ops_truck_visit set state='HANDLING' where id=?", id);
    event(id, "HANDLING", r.notes());
  }

  void finish(UUID id, FinishHandling r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "HANDLING");
    s.require(
        r.documentsChecked(),
        "DOCUMENTS_REQUIRED",
        "Confirm loading/unloading documents before finishing.");
    var weights = s.db.queryForList("select * from ops_weight_ticket where visit_id=?", id);
    if (!weights.isEmpty()) {
      s.require(weights.size() == 2, "WEIGHT_PAIR_REQUIRED", "Provide both tare and gross weight.");
      net(weights);
    }
    s.db.update(
        "update ops_truck_visit set state='READY_EXIT',documents_checked=true where id=?", id);
    event(id, "READY_EXIT", r.notes());
  }

  Map<String, Object> weight(UUID id, Weight r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    if (s.exists("select 1 from ops_weight_ticket where event_id=?", r.eventId())) {
      var old = s.one("select * from ops_weight_ticket where event_id=?", r.eventId());
      s.require(
          id.equals(old.get("visit_id"))
              && r.kind().name().equals(old.get("kind"))
              && r.kg().compareTo((BigDecimal) old.get("kg")) == 0
              && r.measuredAt()
                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                  .equals(s.instant(old.get("measured_at"))),
          "EVENT_CONFLICT",
          "Weight event has another payload.");
      return old;
    }
    s.require(
        Set.of("GATE_CHECKED", "WAITING", "AT_DOCK", "HANDLING").contains(v.get("state")),
        "VISIT_STATE",
        "Weight entry is allowed after gate approval and before handling finishes.");
    s.require(
        !r.measuredAt().isAfter(Instant.now().plusSeconds(60))
            && !r.measuredAt().isBefore(s.instant(v.get("entry_at"))),
        "WEIGHT_TIME",
        "Measurement must belong to this visit and not be in the future.");
    s.db.update(
        "insert into ops_weight_ticket values (?,?,?,?,?)",
        r.eventId(),
        id,
        r.kind().name(),
        r.kg(),
        s.time(r.measuredAt()));
    var weights = s.db.queryForList("select * from ops_weight_ticket where visit_id=?", id);
    if (weights.size() == 2) net(weights);
    s.audit(id, "WEIGHT_RECORDED", r.kind().name());
    return s.one("select * from ops_weight_ticket where event_id=?", r.eventId());
  }

  void depart(UUID id, TruckNotes r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "READY_EXIT");
    s.require(
        Boolean.TRUE.equals(v.get("documents_checked")),
        "DOCUMENTS_REQUIRED",
        "Documents must be checked.");
    s.db.update(
        "update ops_truck_visit set state='DEPARTED',space_id=null,exit_at=? where id=?",
        s.time(Instant.now()),
        id);
    event(id, "DEPARTED", r.notes());
  }

  void cancel(UUID id, TruckNotes r) {
    lock();
    var v = s.one("select * from ops_truck_visit where id=?", id);
    state(v, "SCHEDULED");
    s.db.update("update ops_truck_visit set state='CANCELLED' where id=?", id);
    event(id, "CANCELLED", r.notes());
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var result = new LinkedHashMap<>(s.one("select * from ops_truck_visit where id=?", id));
    var weights = s.db.queryForList("select * from ops_weight_ticket where visit_id=?", id);
    result.put("weights", weights);
    if (weights.size() == 2) result.put("net_kg", net(weights));
    result.put(
        "events",
        s.db.queryForList(
            "select * from ops_visit_event where visit_id=? order by created_at", id));
    result.put("booking", s.db.queryForList("select * from ops_dock_booking where visit_id=?", id));
    return result;
  }

  private BigDecimal net(List<Map<String, Object>> weights) {
    BigDecimal tare = null, gross = null;
    for (var w : weights)
      if ("TARE".equals(w.get("kind"))) tare = (BigDecimal) w.get("kg");
      else gross = (BigDecimal) w.get("kg");
    s.require(
        tare != null && gross != null && gross.compareTo(tare) >= 0,
        "INVALID_WEIGHTS",
        "Gross weight must be at least tare weight.");
    return gross.subtract(tare);
  }

  private void state(Map<String, Object> v, String expected) {
    s.require(
        expected.equals(v.get("state")),
        "VISIT_STATE",
        "Action requires visit state " + expected + ".");
  }

  private void event(UUID id, String state, String notes) {
    s.db.update(
        "insert into ops_visit_event values (?,?,?,?,?,?)",
        s.id(),
        id,
        state,
        notes,
        s.actor(),
        s.time(Instant.now()));
    s.audit(id, "TRUCK_" + state, notes);
  }
}
