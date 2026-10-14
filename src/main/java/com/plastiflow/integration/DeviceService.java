package com.plastiflow.integration;

import static com.plastiflow.integration.Requests.*;

import com.plastiflow.operations.DeviceGateway;
import com.plastiflow.payroll.ClockGateway;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DeviceService {
  final IntegrationStore s;
  final ClockGateway clock;
  final DeviceGateway ops;

  DeviceService(IntegrationStore s, ClockGateway clock, DeviceGateway ops) {
    this.s = s;
    this.clock = clock;
    this.ops = ops;
  }

  Map<String, Object> register(Device r) {
    s.lock();
    if (r.kind() == DeviceKind.CAMERA) {
      s.require(r.targetId() != null, "TARGET_REQUIRED", "Camera device requires a camera record.");
      s.one("select id from ops_camera where id=?", r.targetId());
      s.require(
          s.db
              .queryForList(
                  "select id from int_device where kind='CAMERA' and target_id=? and active=true",
                  r.targetId())
              .isEmpty(),
          "CAMERA_BOUND",
          "Camera already has an active device.");
    } else if (r.kind() == DeviceKind.SENSOR) {
      s.require(
          r.targetId() != null
              && r.metric() != null
              && !r.metric().isBlank()
              && r.unit() != null
              && !r.unit().isBlank(),
          "SENSOR_CONFIG",
          "Sensor needs a machine, metric and unit.");
      s.one("select id from mfg_machine where id=?", r.targetId());
    } else {
      s.require(
          r.targetId() == null,
          "UNEXPECTED_TARGET",
          "Attendance and scales use explicit per-person or per-visit bindings.");
    }
    Object watermark = null;
    if (r.kind() == DeviceKind.CAMERA) {
      var history =
          s.db.queryForList(
              "select last_applied from int_device where kind='CAMERA' and target_id=? and"
                  + " last_applied is not null order by last_applied desc limit 1",
              r.targetId());
      if (!history.isEmpty()) watermark = history.get(0).get("last_applied");
    }
    UUID id = s.id();
    s.db.update(
        "insert into int_device values(?,?,?,?,?,?,?, ?,true,null,?)",
        id,
        r.code(),
        r.name(),
        r.kind().name(),
        r.principal(),
        r.targetId(),
        r.metric(),
        r.unit(),
        watermark);
    s.audit(id, "DEVICE_REGISTERED", r);
    return Map.of("id", id);
  }

  void revoke(UUID id) {
    s.lock();
    s.one("select * from int_device where id=?", id);
    s.db.update("update int_device set active=false where id=?", id);
    s.audit(id, "DEVICE_REVOKED", Map.of());
  }

  void identity(UUID device, Identity r) {
    s.lock();
    s.one("select * from int_device where id=? and kind='ATTENDANCE' and active=true", device);
    s.one("select * from mfg_employee where id=? and active=true", r.employeeId());
    s.db.update(
        "insert into int_identity values(?,?,?)", device, r.externalPerson(), r.employeeId());
    s.audit(device, "IDENTITY_BOUND", r);
  }

  void visit(UUID device, VisitBinding r) {
    s.lock();
    s.one("select * from int_device where id=? and kind='SCALE' and active=true", device);
    s.one("select id from ops_truck_visit where id=?", r.visitId());
    s.db.update("insert into int_scale_visit values(?,?)", device, r.visitId());
    s.audit(device, "VISIT_BOUND", r);
  }

  Map<String, Object> receive(Event r) {
    s.lock();
    var device = s.one("select * from int_device where principal=?", s.actor());
    s.require(Boolean.TRUE.equals(device.get("active")), "DEVICE_REVOKED", "Device is revoked.");
    var normalized =
        new Event(r.eventId(), r.sequence(), s.time(r.occurredAt()).toInstant(), r.data());
    var existing = s.db.queryForList("select * from int_event where id=?", r.eventId());
    if (!existing.isEmpty()) {
      var old = existing.get(0);
      s.require(
          device.get("id").equals(old.get("device_id"))
              && s.decode((String) old.get("payload")).equals(s.decode(s.encode(normalized))),
          "EVENT_CONFLICT",
          "Event ID already has a different device or payload.");
      return old;
    }
    s.require(
        !r.occurredAt().isAfter(Instant.now().plusSeconds(300))
            && !r.occurredAt().isBefore(Instant.now().minus(Duration.ofDays(30))),
        "EVENT_TIME",
        "Event must be within the last 30 days and no more than five minutes ahead.");
    validate((String) device.get("kind"), r.data());
    s.db.update(
        "insert into int_event values(?,?,?,?,?,'RECEIVED',0,null,?)",
        r.eventId(),
        device.get("id"),
        r.sequence(),
        s.time(r.occurredAt()),
        s.encode(normalized),
        s.time(Instant.now()));
    s.db.update(
        "update int_device set last_seen=? where id=?", s.time(Instant.now()), device.get("id"));
    s.audit(r.eventId(), "DEVICE_EVENT_RECEIVED", Map.of("device", device.get("id")));
    return event(r.eventId());
  }

  void validate(String kind, Map<String, String> data) {
    Set<String> keys =
        switch (kind) {
          case "ATTENDANCE" -> Set.of("person", "kind");
          case "CAMERA" -> Set.of("state");
          case "SENSOR" -> Set.of("value");
          default -> Set.of("visitId", "kind", "kg");
        };
    s.require(
        data.keySet().equals(keys),
        "EVENT_SCHEMA",
        "Event fields do not match the registered device type.");
    try {
      switch (kind) {
        case "ATTENDANCE" ->
            s.require(
                Set.of("IN", "OUT", "BREAK_START", "BREAK_END").contains(data.get("kind")),
                "CLOCK_KIND",
                "Unknown clock kind.");
        case "CAMERA" ->
            s.require(
                Set.of("ONLINE", "OFFLINE", "MAINTENANCE").contains(data.get("state")),
                "CAMERA_STATE",
                "Unknown camera state.");
        case "SENSOR" -> decimal(data.get("value"), false);
        default -> {
          UUID.fromString(data.get("visitId"));
          s.require(
              Set.of("TARE", "GROSS").contains(data.get("kind")),
              "WEIGHT_KIND",
              "Unknown weight kind.");
          decimal(data.get("kg"), true);
        }
      }
    } catch (IllegalArgumentException ex) {
      s.require(false, "EVENT_SCHEMA", "Invalid UUID or numeric payload.");
    }
  }

  BigDecimal decimal(String value, boolean positive) {
    BigDecimal n = new BigDecimal(value);
    s.require(
        n.scale() <= 6
            && n.abs().compareTo(new BigDecimal("1000000000000")) < 0
            && (!positive || n.signum() > 0),
        "EVENT_NUMBER",
        "Provide a bounded number with at most six decimals; weights must be positive.");
    return n;
  }

  Map<String, Object> apply(UUID id) {
    s.lock();
    var e = s.one("select * from int_event where id=? for update", id);
    if (Set.of("APPLIED", "IGNORED").contains(e.get("state"))) return e;
    s.require(
        ((Number) e.get("attempts")).intValue() < 10,
        "RETRY_LIMIT",
        "Event reached its ten-attempt limit.");
    var d = s.one("select * from int_device where id=? for update", e.get("device_id"));
    s.require(
        Boolean.TRUE.equals(d.get("active")),
        "DEVICE_REVOKED",
        "Revoked devices cannot project events.");
    var data = s.decode((String) e.get("payload")).get("data");
    Instant at = s.instant(e.get("occurred_at"));
    String state = "APPLIED";
    switch ((String) d.get("kind")) {
      case "ATTENDANCE" -> {
        var mapping =
            s.one(
                "select * from int_identity where device_id=? and external_person=?",
                d.get("id"),
                data.get("person").asText());
        clock.capture(
            id,
            (UUID) mapping.get("employee_id"),
            data.get("kind").asText(),
            at,
            "DEVICE:" + d.get("code"));
      }
      case "CAMERA" -> {
        if (d.get("last_applied") != null && !at.isAfter(s.instant(d.get("last_applied"))))
          state = "IGNORED";
        else ops.camera((UUID) d.get("target_id"), data.get("state").asText());
      }
      case "SCALE" -> {
        UUID visit = UUID.fromString(data.get("visitId").asText());
        s.one("select * from int_scale_visit where device_id=? and visit_id=?", d.get("id"), visit);
        ops.weight(
            visit, id, data.get("kind").asText(), decimal(data.get("kg").asText(), true), at);
      }
      default -> {
        /* Sensor measurements remain telemetry; they cannot change machine settings. */
      }
    }
    s.db.update(
        "update int_event set state=?,attempts=attempts+1,error_code=null where id=?", state, id);
    if (d.get("last_applied") == null || at.isAfter(s.instant(d.get("last_applied"))))
      s.db.update("update int_device set last_applied=? where id=?", s.time(at), d.get("id"));
    s.audit(id, "DEVICE_EVENT_" + state, Map.of());
    return event(id);
  }

  Map<String, Object> failed(UUID id, String code) {
    s.lock();
    s.one("select * from int_event where id=?", id);
    s.db.update(
        "update int_event set state='FAILED',attempts=attempts+1,error_code=? where id=? and state"
            + " in ('RECEIVED','FAILED') and attempts<10",
        code,
        id);
    s.audit(id, "DEVICE_EVENT_FAILED", Map.of("code", code));
    return event(id);
  }

  @Transactional(readOnly = true)
  Map<String, Object> event(UUID id) {
    return s.one("select * from int_event where id=?", id);
  }
}
