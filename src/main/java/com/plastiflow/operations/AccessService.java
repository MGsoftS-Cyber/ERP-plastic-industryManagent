package com.plastiflow.operations;

import static com.plastiflow.operations.Requests.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AccessService {
  private final OpsStore s;

  AccessService(OpsStore s) {
    this.s = s;
  }

  private void lock() {
    s.one("select * from ops_access_lock where id=1 for update");
  }

  Map<String, Object> zone(Zone r) {
    UUID id = s.id();
    s.db.update("insert into ops_access_zone values (?,?,?)", id, r.code(), r.name());
    s.audit(id, "ZONE_CREATED", "");
    return s.one("select * from ops_access_zone where id=?", id);
  }

  Map<String, Object> visitor(Visitor r) {
    s.interval(r.validFrom(), r.validTo());
    for (UUID zone : r.zoneIds()) s.one("select * from ops_access_zone where id=?", zone);
    UUID id = s.id();
    s.db.update(
        "insert into ops_visitor"
            + " (id,reference,name,company,host_reference,valid_from,valid_to,safety_briefed,state)"
            + " values (?,?,?,?,?,?,?,?,?)",
        id,
        r.reference(),
        r.name(),
        r.company(),
        r.hostReference(),
        s.time(r.validFrom()),
        s.time(r.validTo()),
        false,
        "EXPECTED");
    for (UUID zone : r.zoneIds())
      s.db.update("insert into ops_visitor_zone values (?,?)", id, zone);
    s.audit(id, "VISITOR_EXPECTED", r.hostReference());
    return detail(id);
  }

  void badge(Badge r) {
    lock();
    s.db.update("insert into ops_badge values (?,?)", r.code(), true);
  }

  void checkIn(UUID id, CheckIn r) {
    lock();
    var v = s.one("select * from ops_visitor where id=?", id);
    s.require(
        "EXPECTED".equals(v.get("state")),
        "VISITOR_STATE",
        "Only an expected visitor can check in.");
    valid(v);
    s.require(
        r.safetyBriefed(), "BRIEFING_REQUIRED", "Confirm safety briefing before issuing access.");
    var badge = s.one("select * from ops_badge where code=?", r.badge());
    s.require(Boolean.TRUE.equals(badge.get("active")), "BADGE_UNAVAILABLE", "Badge unavailable.");
    s.require(
        !s.exists("select 1 from ops_visitor where badge=? and state='CHECKED_IN'", r.badge()),
        "BADGE_OCCUPIED",
        "Badge is already assigned to a checked-in visitor.");
    s.db.update(
        "update ops_visitor set state='CHECKED_IN',badge=?,safety_briefed=true where id=?",
        r.badge(),
        id);
    s.audit(id, "VISITOR_CHECKED_IN", r.badge());
  }

  Map<String, Object> access(UUID id, Access r) {
    lock();
    var v = s.one("select * from ops_visitor where id=?", id);
    if (s.exists("select 1 from ops_access_event where event_id=?", r.eventId())) {
      var old = s.one("select * from ops_access_event where event_id=?", r.eventId());
      s.require(
          id.equals(old.get("visitor_id"))
              && r.zoneId().equals(old.get("zone_id"))
              && r.direction().name().equals(old.get("direction")),
          "EVENT_CONFLICT",
          "Access event has another payload.");
      return old;
    }
    s.require("CHECKED_IN".equals(v.get("state")), "VISITOR_STATE", "Visitor must be checked in.");
    s.one("select * from ops_access_zone where id=?", r.zoneId());
    if (r.direction() == Direction.ENTER) {
      valid(v);
      s.require(
          s.exists(
              "select 1 from ops_visitor_zone where visitor_id=? and zone_id=?", id, r.zoneId()),
          "ZONE_DENIED",
          "Visitor is not authorized for this zone.");
      s.require(
          !s.exists("select 1 from ops_presence where visitor_id=?", id),
          "ALREADY_IN_ZONE",
          "Record exit from the current zone first.");
      s.db.update("insert into ops_presence values (?,?)", id, r.zoneId());
    } else {
      s.require(
          s.exists("select 1 from ops_presence where visitor_id=? and zone_id=?", id, r.zoneId()),
          "NOT_IN_ZONE",
          "Visitor is not recorded inside this zone.");
      s.db.update("delete from ops_presence where visitor_id=?", id);
    }
    s.db.update(
        "insert into ops_access_event values (?,?,?,?,?,?)",
        r.eventId(),
        id,
        r.zoneId(),
        r.direction().name(),
        s.actor(),
        s.time(Instant.now()));
    s.audit(id, "ZONE_" + r.direction(), r.zoneId().toString());
    return s.one("select * from ops_access_event where event_id=?", r.eventId());
  }

  void checkOut(UUID id, CheckOut r) {
    lock();
    var v = s.one("select * from ops_visitor where id=?", id);
    s.require("CHECKED_IN".equals(v.get("state")), "VISITOR_STATE", "Visitor must be checked in.");
    s.require(
        r.badgeReturned(), "BADGE_RETURN_REQUIRED", "Confirm badge return before checking out.");
    s.require(
        !s.exists("select 1 from ops_presence where visitor_id=?", id),
        "VISITOR_STILL_INSIDE",
        "Record zone exit before checking out.");
    s.db.update("update ops_visitor set state='CHECKED_OUT' where id=?", id);
    s.audit(id, "VISITOR_CHECKED_OUT", "Badge returned");
  }

  void cancel(UUID id, Notes r) {
    lock();
    var v = s.one("select * from ops_visitor where id=?", id);
    s.require(
        "EXPECTED".equals(v.get("state")),
        "VISITOR_STATE",
        "Only expected visits can be cancelled.");
    s.db.update("update ops_visitor set state='CANCELLED' where id=?", id);
    s.audit(id, "VISITOR_CANCELLED", r.notes());
  }

  Map<String, Object> incident(Incident r) {
    if (r.zoneId() != null) s.one("select * from ops_access_zone where id=?", r.zoneId());
    UUID id = s.id();
    s.db.update(
        "insert into ops_incident (id,zone_id,severity,description,state,created_at) values"
            + " (?,?,?,?,?,?)",
        id,
        r.zoneId(),
        r.severity().name(),
        r.description(),
        "OPEN",
        s.time(Instant.now()));
    s.audit(id, "SECURITY_INCIDENT_RECORDED", r.description());
    return s.one("select * from ops_incident where id=?", id);
  }

  void resolve(UUID id, Notes r) {
    var incident = s.one("select * from ops_incident where id=? for update", id);
    s.require("OPEN".equals(incident.get("state")), "INCIDENT_STATE", "Incident already resolved.");
    s.db.update("update ops_incident set state='RESOLVED',resolution=? where id=?", r.notes(), id);
    s.audit(id, "SECURITY_INCIDENT_RESOLVED", r.notes());
  }

  Map<String, Object> camera(Camera r) {
    s.one("select * from ops_access_zone where id=?", r.zoneId());
    UUID id = s.id();
    s.db.update(
        "insert into ops_camera values (?,?,?,?,?,?)",
        id,
        r.code(),
        r.zoneId(),
        r.name(),
        r.vmsReference(),
        "OFFLINE");
    s.audit(id, "CAMERA_REGISTERED", r.vmsReference());
    return s.one("select * from ops_camera where id=?", id);
  }

  void cameraStatus(UUID id, CameraStatus r) {
    s.one("select * from ops_camera where id=? for update", id);
    s.db.update("update ops_camera set state=? where id=?", r.state().name(), id);
    s.audit(id, "CAMERA_" + r.state(), "");
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var result = new LinkedHashMap<>(s.one("select * from ops_visitor where id=?", id));
    result.put(
        "zones",
        s.db.queryForList(
            "select z.* from ops_access_zone z join ops_visitor_zone v on v.zone_id=z.id where"
                + " v.visitor_id=?",
            id));
    result.put(
        "access_events",
        s.db.queryForList(
            "select * from ops_access_event where visitor_id=? order by created_at", id));
    result.put("presence", s.db.queryForList("select * from ops_presence where visitor_id=?", id));
    return result;
  }

  private void valid(Map<String, Object> v) {
    Instant now = Instant.now();
    s.require(
        !now.isBefore(s.instant(v.get("valid_from"))) && now.isBefore(s.instant(v.get("valid_to"))),
        "VISIT_EXPIRED",
        "Visitor appointment is not currently valid.");
  }
}
