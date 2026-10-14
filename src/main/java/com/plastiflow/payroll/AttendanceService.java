package com.plastiflow.payroll;

import static com.plastiflow.payroll.Requests.*;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AttendanceService {
  private final PayrollStore s;

  AttendanceService(PayrollStore s) {
    this.s = s;
  }

  Map<String, Object> clock(Requests.Clock r) {
    s.lock();
    if (s.exists("select 1 from wf_clock_event where event_id=?", r.eventId())) {
      var old = s.one("select * from wf_clock_event where event_id=?", r.eventId());
      s.require(
          r.employeeId().equals(old.get("employee_id"))
              && r.kind().name().equals(old.get("kind"))
              && r.source().equals(old.get("source"))
              && r.occurredAt()
                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                  .equals(s.instant(old.get("occurred_at"))),
          "EVENT_CONFLICT",
          "Clock event ID already has another payload.");
      return old;
    }
    s.employee(r.employeeId());
    s.db.update(
        "insert into wf_clock_event values (?,?,?,?,?)",
        r.eventId(),
        r.employeeId(),
        r.kind().name(),
        s.time(r.occurredAt()),
        r.source());
    s.audit(r.eventId(), "CLOCK_CAPTURED", r.source());
    return s.one("select * from wf_clock_event where event_id=?", r.eventId());
  }

  Map<String, Object> attendance(Attendance r) {
    s.lock();
    return create(r, null);
  }

  private Map<String, Object> create(Attendance r, UUID replaces) {
    s.employee(r.employeeId());
    s.interval(r.start(), r.end());
    s.changed(r.employeeId(), r.start(), r.end());
    var breaks = new ArrayList<>(r.unpaidBreaks());
    breaks.sort(Comparator.comparing(Interval::start));
    Instant previous = r.start();
    for (var b : breaks) {
      s.interval(b.start(), b.end());
      s.require(
          !b.start().isBefore(previous) && !b.end().isAfter(r.end()),
          "BREAK_OVERLAP",
          "Breaks must be nonoverlapping and inside attendance.");
      previous = b.end();
    }
    s.require(
        paidMinutes(r.start(), r.end(), breaks) > 0,
        "EMPTY_ATTENDANCE",
        "Attendance requires positive paid time.");
    for (UUID event : r.rawEventIds()) {
      var e = s.one("select * from wf_clock_event where event_id=?", event);
      s.require(
          r.employeeId().equals(e.get("employee_id"))
              && !s.instant(e.get("occurred_at")).isBefore(r.start())
              && !s.instant(e.get("occurred_at")).isAfter(r.end()),
          "CLOCK_MISMATCH",
          "Linked events must belong to this employee and interval.");
    }
    s.require(
        !s.exists(
            "select 1 from wf_attendance where employee_id=? and state<>'SUPERSEDED' and"
                + " starts_at<? and ends_at>?",
            r.employeeId(),
            s.time(r.end()),
            s.time(r.start())),
        "ATTENDANCE_OVERLAP",
        "Attendance overlaps another active record.");
    s.require(
        !s.exists(
            "select 1 from wf_absence where employee_id=? and state<>'CANCELLED' and starts_at<?"
                + " and ends_at>?",
            r.employeeId(),
            s.time(r.end()),
            s.time(r.start())),
        "ABSENCE_OVERLAP",
        "Attendance overlaps recorded absence.");
    UUID id = s.id();
    s.db.update(
        "insert into wf_attendance"
            + " (id,employee_id,starts_at,ends_at,breaks,raw_event_ids,state,overtime_minutes,notes,replaces_id)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        id,
        r.employeeId(),
        s.time(r.start()),
        s.time(r.end()),
        s.encode(breaks),
        s.encode(r.rawEventIds()),
        "DRAFT",
        0,
        r.notes(),
        replaces);
    s.audit(id, "ATTENDANCE_DRAFTED", r.notes());
    return s.one("select * from wf_attendance where id=?", id);
  }

  void approve(UUID id, Approval r) {
    s.lock();
    var a = s.one("select * from wf_attendance where id=?", id);
    s.require(
        "DRAFT".equals(a.get("state")),
        "ATTENDANCE_STATE",
        "Only draft attendance may be approved.");
    Instant start = s.instant(a.get("starts_at")), end = s.instant(a.get("ends_at"));
    List<Interval> breaks =
        s.decode((String) a.get("breaks"), new TypeReference<List<Interval>>() {});
    s.require(
        r.overtimeMinutes() <= paidMinutes(start, end, breaks),
        "INVALID_OVERTIME",
        "Approved overtime cannot exceed net worked time.");
    s.changed((UUID) a.get("employee_id"), start, end);
    s.db.update(
        "update wf_attendance set state='APPROVED',overtime_minutes=?,approved_by=? where id=?",
        r.overtimeMinutes(),
        s.actor(),
        id);
    s.audit(id, "ATTENDANCE_APPROVED", r.notes());
  }

  Map<String, Object> correct(UUID id, Attendance r) {
    s.lock();
    var old = s.one("select * from wf_attendance where id=?", id);
    s.require(
        !"SUPERSEDED".equals(old.get("state")) && r.employeeId().equals(old.get("employee_id")),
        "CORRECTION_TARGET",
        "Correct an active record for the same employee.");
    s.changed(r.employeeId(), s.instant(old.get("starts_at")), s.instant(old.get("ends_at")));
    s.db.update("update wf_attendance set state='SUPERSEDED' where id=?", id);
    return create(r, id);
  }

  Map<String, Object> absence(Absence r) {
    s.lock();
    s.employee(r.employeeId());
    s.interval(r.start(), r.end());
    s.changed(r.employeeId(), r.start(), r.end());
    s.require(
        !s.exists(
                "select 1 from wf_attendance where employee_id=? and state<>'SUPERSEDED' and"
                    + " starts_at<? and ends_at>?",
                r.employeeId(),
                s.time(r.end()),
                s.time(r.start()))
            && !s.exists(
                "select 1 from wf_absence where employee_id=? and state<>'CANCELLED' and"
                    + " starts_at<? and ends_at>?",
                r.employeeId(),
                s.time(r.end()),
                s.time(r.start())),
        "ABSENCE_OVERLAP",
        "Absence overlaps attendance or another absence.");
    UUID id = s.id();
    s.db.update(
        "insert into wf_absence values (?,?,?,?,?,?,?,?)",
        id,
        r.employeeId(),
        s.time(r.start()),
        s.time(r.end()),
        r.kind().name(),
        "DRAFT",
        r.reason(),
        null);
    s.audit(id, "ABSENCE_REQUESTED", r.reason());
    return s.one("select * from wf_absence where id=?", id);
  }

  void approveAbsence(UUID id, Notes r) {
    s.lock();
    var a = s.one("select * from wf_absence where id=?", id);
    s.require("DRAFT".equals(a.get("state")), "ABSENCE_STATE", "Absence already approved.");
    s.changed(
        (UUID) a.get("employee_id"), s.instant(a.get("starts_at")), s.instant(a.get("ends_at")));
    s.db.update("update wf_absence set state='APPROVED',approved_by=? where id=?", s.actor(), id);
    s.audit(id, "ABSENCE_APPROVED", r.notes());
  }

  void voidAttendance(UUID id, Notes r) {
    s.lock();
    var a = s.one("select * from wf_attendance where id=?", id);
    s.require(
        !"SUPERSEDED".equals(a.get("state")), "ATTENDANCE_STATE", "Attendance already superseded.");
    s.changed(
        (UUID) a.get("employee_id"), s.instant(a.get("starts_at")), s.instant(a.get("ends_at")));
    s.db.update("update wf_attendance set state='SUPERSEDED' where id=?", id);
    s.audit(id, "ATTENDANCE_VOIDED", r.notes());
  }

  void cancelAbsence(UUID id, Notes r) {
    s.lock();
    var a = s.one("select * from wf_absence where id=?", id);
    s.require(!"CANCELLED".equals(a.get("state")), "ABSENCE_STATE", "Absence already cancelled.");
    s.changed(
        (UUID) a.get("employee_id"), s.instant(a.get("starts_at")), s.instant(a.get("ends_at")));
    s.db.update("update wf_absence set state='CANCELLED' where id=?", id);
    s.audit(id, "ABSENCE_CANCELLED", r.notes());
  }

  static int paidMinutes(Instant start, Instant end, List<Interval> breaks) {
    long total = Duration.between(start, end).toMinutes();
    for (var b : breaks) total -= Duration.between(b.start(), b.end()).toMinutes();
    return Math.toIntExact(total);
  }
}
