package com.plastiflow.payroll;

import static com.plastiflow.payroll.Requests.*;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class PayrollService {
  private final PayrollStore s;

  PayrollService(PayrollStore s) {
    this.s = s;
  }

  Map<String, Object> wage(Wage r) {
    s.lock();
    s.employee(r.employeeId());
    s.require(
        r.basis() == Basis.HOURLY ? r.monthlyMinutes() == 0 : r.monthlyMinutes() > 0,
        "WAGE_BASIS",
        "Hourly wages require zero monthly minutes; monthly wages require contractual monthly paid"
            + " minutes.");
    s.datedChange(r.employeeId(), r.effectiveFrom());
    UUID id = s.id();
    s.db.update(
        "insert into wf_wage values (?,?,?,?,?,?,?)",
        id,
        r.employeeId(),
        java.sql.Date.valueOf(r.effectiveFrom()),
        r.basis().name(),
        r.amount(),
        r.currency(),
        r.monthlyMinutes());
    s.audit(id, "WAGE_VERSION_CREATED", r.effectiveFrom().toString());
    return s.one("select * from wf_wage where id=?", id);
  }

  Map<String, Object> rule(Rule r) {
    s.lock();
    s.require(
        !r.nightStart().equals(r.nightEnd())
            && r.nightStart().getSecond() == 0
            && r.nightStart().getNano() == 0
            && r.nightEnd().getSecond() == 0
            && r.nightEnd().getNano() == 0,
        "NIGHT_WINDOW",
        "Use distinct whole-minute night boundaries.");
    s.datedChange(null, r.effectiveFrom());
    UUID id = s.id();
    s.db.update(
        "insert into wf_rule values (?,?,?,?,?,?,?,?)",
        id,
        java.sql.Date.valueOf(r.effectiveFrom()),
        java.sql.Time.valueOf(r.nightStart()),
        java.sql.Time.valueOf(r.nightEnd()),
        r.nightType().name(),
        r.nightValue(),
        r.overtimePercent(),
        r.stacking().name());
    s.audit(id, "PREMIUM_VERSION_CREATED", r.effectiveFrom().toString());
    return s.one("select * from wf_rule where id=?", id);
  }

  Map<String, Object> period(Requests.Period r) {
    s.lock();
    ZoneId zone;
    try {
      zone = ZoneId.of(r.zoneId());
    } catch (Exception e) {
      throw new com.plastiflow.shared.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_ZONE",
          "Use a valid IANA time zone.");
    }
    s.require(
        r.end().isAfter(r.start())
            && java.time.temporal.ChronoUnit.DAYS.between(r.start(), r.end()) <= 62,
        "PERIOD_RANGE",
        "Use a positive period of at most 62 days; end date is exclusive.");
    for (UUID employee : r.employeeIds()) {
      s.employee(employee);
      for (var p :
          s.db.queryForList(
              "select p.* from wf_period p join wf_period_employee e on e.period_id=p.id where"
                  + " e.employee_id=?",
              employee)) {
        ZoneId oldZone = ZoneId.of((String) p.get("zone_id"));
        Instant
            a =
                ((java.sql.Date) p.get("starts_on"))
                    .toLocalDate()
                    .atStartOfDay(oldZone)
                    .toInstant(),
            b = ((java.sql.Date) p.get("ends_on")).toLocalDate().atStartOfDay(oldZone).toInstant();
        s.require(
            !r.start().atStartOfDay(zone).toInstant().isBefore(b)
                || !r.end().atStartOfDay(zone).toInstant().isAfter(a),
            "PERIOD_OVERLAP",
            "Employee already belongs to an overlapping payroll period.");
      }
    }
    UUID id = s.id();
    s.db.update(
        "insert into wf_period (id,code,starts_on,ends_on,zone_id,currency,money_scale,state)"
            + " values (?,?,?,?,?,?,?,?)",
        id,
        r.code(),
        java.sql.Date.valueOf(r.start()),
        java.sql.Date.valueOf(r.end()),
        r.zoneId(),
        r.currency(),
        r.moneyScale(),
        "DRAFT");
    for (UUID employee : r.employeeIds())
      s.db.update("insert into wf_period_employee values (?,?)", id, employee);
    s.audit(id, "PAY_PERIOD_CREATED", r.code());
    return detail(id);
  }

  Map<String, Object> adjustment(UUID period, Adjustment r) {
    s.lock();
    var p = mutable(period);
    s.require(
        s.exists(
            "select 1 from wf_period_employee where period_id=? and employee_id=?",
            period,
            r.employeeId()),
        "PERIOD_EMPLOYEE",
        "Employee is not in this period.");
    UUID id = s.id();
    s.db.update(
        "insert into wf_adjustment"
            + " (id,period_id,employee_id,kind,amount,reason,state,approved_by,created_by) values"
            + " (?,?,?,?,?,?,?,?,?)",
        id,
        period,
        r.employeeId(),
        r.kind().name(),
        r.amount(),
        r.reason(),
        "DRAFT",
        null,
        s.actor());
    dirty(period);
    s.audit(id, "ADJUSTMENT_REQUESTED", r.reason());
    return s.one("select * from wf_adjustment where id=?", id);
  }

  void decide(UUID id, Decision r) {
    s.lock();
    var a = s.one("select * from wf_adjustment where id=?", id);
    mutable((UUID) a.get("period_id"));
    s.require("DRAFT".equals(a.get("state")), "ADJUSTMENT_STATE", "Adjustment already reviewed.");
    if (r.approved())
      s.require(
          a.get("created_by") != null && !s.actor().equals(a.get("created_by")),
          "SEPARATE_APPROVER",
          "A different identified person must approve a bonus or deduction. Unknown legacy authors"
              + " may be rejected but cannot be approved.");
    s.db.update(
        "update wf_adjustment set state=?,approved_by=? where id=?",
        r.approved() ? "APPROVED" : "REJECTED",
        s.actor(),
        id);
    dirty((UUID) a.get("period_id"));
    s.audit(id, "ADJUSTMENT_" + (r.approved() ? "APPROVED" : "REJECTED"), r.notes());
  }

  Map<String, Object> calculate(UUID id) {
    s.lock();
    var p = mutable(id);
    ZoneId zone = ZoneId.of((String) p.get("zone_id"));
    Instant
        from = ((java.sql.Date) p.get("starts_on")).toLocalDate().atStartOfDay(zone).toInstant(),
        to = ((java.sql.Date) p.get("ends_on")).toLocalDate().atStartOfDay(zone).toInstant();
    s.require(
        !s.exists("select 1 from wf_adjustment where period_id=? and state='DRAFT'", id),
        "PENDING_ADJUSTMENTS",
        "Review all requested bonuses/deductions first.");
    s.db.update("delete from wf_pay_line where period_id=?", id);
    var rules = s.db.queryForList("select * from wf_rule order by effective_from");
    for (var person :
        s.db.queryForList("select employee_id from wf_period_employee where period_id=?", id))
      calculateEmployee(p, (UUID) person.get("employee_id"), from, to, zone, rules);
    s.db.update(
        "update wf_period set state='CALCULATED',calculated_by=?,reviewed_by=null where id=?",
        s.actor(),
        id);
    s.audit(id, "PAYROLL_CALCULATED", "");
    return detail(id);
  }

  private void calculateEmployee(
      Map<String, Object> p,
      UUID employee,
      Instant from,
      Instant to,
      ZoneId zone,
      List<Map<String, Object>> rules) {
    UUID period = (UUID) p.get("id");
    var rates =
        s.db.queryForList(
            "select * from wf_wage where employee_id=? order by effective_from", employee);
    s.require(
        !s.exists(
                "select 1 from wf_attendance where employee_id=? and starts_at<? and ends_at>? and"
                    + " state='DRAFT'",
                employee,
                s.time(to),
                s.time(from))
            && !s.exists(
                "select 1 from wf_absence where employee_id=? and starts_at<? and ends_at>? and"
                    + " state='DRAFT'",
                employee,
                s.time(to),
                s.time(from)),
        "UNVALIDATED_TIME",
        "Approve all attendance and absence records intersecting the period.");
    var buckets = new LinkedHashMap<String, Map<String, Object>>();
    BigDecimal base = BigDecimal.ZERO, nightPay = BigDecimal.ZERO, otPay = BigDecimal.ZERO;
    int worked = 0, nightCount = 0, otCount = 0, leave = 0;
    var intervals =
        s.db.queryForList(
            "select * from wf_attendance where employee_id=? and starts_at<? and ends_at>? and"
                + " state='APPROVED' order by starts_at",
            employee,
            s.time(to),
            s.time(from));
    for (var a : intervals) {
      Instant start = s.instant(a.get("starts_at")), end = s.instant(a.get("ends_at"));
      List<Interval> breaks =
          s.decode((String) a.get("breaks"), new TypeReference<List<Interval>>() {});
      int remaining = AttendanceService.paidMinutes(start, end, breaks),
          overtime = (Integer) a.get("overtime_minutes");
      for (Instant minute = start; minute.isBefore(end); minute = minute.plusSeconds(60)) {
        boolean unpaid = false;
        for (var b : breaks)
          if (!minute.isBefore(b.start()) && minute.isBefore(b.end())) {
            unpaid = true;
            break;
          }
        if (unpaid) continue;
        boolean ot = remaining <= overtime;
        remaining--;
        if (minute.isBefore(from) || !minute.isBefore(to)) continue;
        LocalDate date = minute.atZone(zone).toLocalDate();
        var wage = effective(rates, date, "WAGE_MISSING");
        s.require(
            p.get("currency").equals(wage.get("currency")),
            "CURRENCY_MISMATCH",
            "Wage currency must match the pay period.");
        var rule = effective(rules, date, "PREMIUM_RULE_MISSING");
        boolean night =
            isNight(
                minute.atZone(zone).toLocalTime(),
                ((java.sql.Time) rule.get("night_start")).toLocalTime(),
                ((java.sql.Time) rule.get("night_end")).toLocalTime());
        BigDecimal perMinute = perMinute(wage),
            nightExtra =
                night
                    ? ("PERCENT".equals(rule.get("night_type"))
                        ? perMinute
                            .multiply((BigDecimal) rule.get("night_value"))
                            .divide(BigDecimal.valueOf(100), 18, RoundingMode.HALF_EVEN)
                        : ((BigDecimal) rule.get("night_value"))
                            .divide(BigDecimal.valueOf(60), 18, RoundingMode.HALF_EVEN))
                    : BigDecimal.ZERO;
        BigDecimal overtimeExtra =
            ot
                ? perMinute
                    .add(
                        "MULTIPLICATIVE".equals(rule.get("stacking"))
                            ? nightExtra
                            : BigDecimal.ZERO)
                    .multiply((BigDecimal) rule.get("overtime_percent"))
                    .divide(BigDecimal.valueOf(100), 18, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO;
        base = base.add(perMinute);
        nightPay = nightPay.add(nightExtra);
        otPay = otPay.add(overtimeExtra);
        worked++;
        if (night) nightCount++;
        if (ot) otCount++;
        bucket(buckets, wage, rule, night, ot, false, perMinute, nightExtra, overtimeExtra);
      }
    }
    for (var a :
        s.db.queryForList(
            "select * from wf_absence where employee_id=? and starts_at<? and ends_at>? and"
                + " state='APPROVED' and kind='PAID_LEAVE'",
            employee,
            s.time(to),
            s.time(from))) {
      Instant start = s.instant(a.get("starts_at")), end = s.instant(a.get("ends_at"));
      for (Instant minute = start; minute.isBefore(end); minute = minute.plusSeconds(60)) {
        if (minute.isBefore(from) || !minute.isBefore(to)) continue;
        var wage = effective(rates, minute.atZone(zone).toLocalDate(), "WAGE_MISSING");
        s.require(
            p.get("currency").equals(wage.get("currency")),
            "CURRENCY_MISMATCH",
            "Wage currency must match the period.");
        BigDecimal rate = perMinute(wage);
        base = base.add(rate);
        leave++;
        bucket(buckets, wage, null, false, false, true, rate, BigDecimal.ZERO, BigDecimal.ZERO);
      }
    }
    BigDecimal bonus = BigDecimal.ZERO, deduction = BigDecimal.ZERO;
    var adjustments =
        s.db.queryForList(
            "select * from wf_adjustment where period_id=? and employee_id=? and state='APPROVED'",
            period,
            employee);
    for (var a : adjustments)
      if ("BONUS".equals(a.get("kind"))) bonus = bonus.add((BigDecimal) a.get("amount"));
      else deduction = deduction.add((BigDecimal) a.get("amount"));
    int scale = (Integer) p.get("money_scale");
    base = money(base, scale);
    nightPay = money(nightPay, scale);
    otPay = money(otPay, scale);
    bonus = money(bonus, scale);
    deduction = money(deduction, scale);
    BigDecimal gross = base.add(nightPay).add(otPay).add(bonus),
        payable = gross.subtract(deduction);
    s.require(payable.signum() >= 0, "EXCESSIVE_DEDUCTION", "Entered deductions exceed gross pay.");
    UUID line = s.id();
    s.db.update(
        "insert into wf_pay_line values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        line,
        period,
        employee,
        worked,
        nightCount,
        otCount,
        leave,
        base,
        nightPay,
        otPay,
        bonus,
        deduction,
        gross,
        payable,
        s.encode(
            Map.of(
                "segments",
                buckets.values(),
                "adjustments",
                adjustments,
                "attendance_ids",
                intervals.stream().map(a -> a.get("id")).toList(),
                "zone_id",
                zone.getId(),
                "rounding",
                "HALF_UP by pay component; statutory taxes not configured")));
  }

  void review(UUID id, Notes r) {
    s.lock();
    var p = s.one("select * from wf_period where id=?", id);
    s.require(
        "CALCULATED".equals(p.get("state")),
        "PAYROLL_STATE",
        "Calculate fresh payroll before review.");
    s.require(
        !s.actor().equals(p.get("calculated_by")),
        "SEPARATE_REVIEWER",
        "Reviewer must differ from the payroll calculator.");
    s.db.update("update wf_period set state='REVIEWED',reviewed_by=? where id=?", s.actor(), id);
    s.audit(id, "PAYROLL_REVIEWED", r.notes());
  }

  void finalizePeriod(UUID id, Notes r) {
    s.lock();
    var p = s.one("select * from wf_period where id=?", id);
    s.require(
        "REVIEWED".equals(p.get("state")),
        "PAYROLL_STATE",
        "Payroll must be reviewed and unchanged before finalization.");
    s.db.update("update wf_period set state='FINALIZED',finalized_by=? where id=?", s.actor(), id);
    s.audit(id, "PAYROLL_FINALIZED", r.notes());
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    var p = new LinkedHashMap<>(s.one("select * from wf_period where id=?", id));
    p.put(
        "lines",
        s.db.queryForList(
            "select l.*,e.code,e.name from wf_pay_line l join mfg_employee e on e.id=l.employee_id"
                + " where period_id=? order by e.code",
            id));
    p.put("adjustments", s.db.queryForList("select * from wf_adjustment where period_id=?", id));
    return p;
  }

  private Map<String, Object> mutable(UUID id) {
    var p = s.one("select * from wf_period where id=?", id);
    s.require(
        !"FINALIZED".equals(p.get("state")), "PAYROLL_FROZEN", "Finalized payroll is immutable.");
    return p;
  }

  private void dirty(UUID id) {
    s.db.update(
        "update wf_period set state='DIRTY',reviewed_by=null where id=? and state<>'DRAFT'", id);
  }

  private Map<String, Object> effective(
      List<Map<String, Object>> versions, LocalDate day, String code) {
    Map<String, Object> chosen = null;
    for (var v : versions)
      if (!((java.sql.Date) v.get("effective_from")).toLocalDate().isAfter(day)) chosen = v;
    s.require(
        chosen != null, code, "An effective wage/premium version is missing for " + day + ".");
    return chosen;
  }

  private BigDecimal perMinute(Map<String, Object> wage) {
    return ((BigDecimal) wage.get("amount"))
        .divide(
            BigDecimal.valueOf(
                "HOURLY".equals(wage.get("basis")) ? 60 : (Integer) wage.get("monthly_minutes")),
            18,
            RoundingMode.HALF_EVEN);
  }

  static boolean isNight(LocalTime t, LocalTime start, LocalTime end) {
    return start.isBefore(end)
        ? !t.isBefore(start) && t.isBefore(end)
        : !t.isBefore(start) || t.isBefore(end);
  }

  private BigDecimal money(BigDecimal v, int scale) {
    return v.setScale(scale, RoundingMode.HALF_UP);
  }

  private void bucket(
      Map<String, Map<String, Object>> buckets,
      Map<String, Object> wage,
      Map<String, Object> rule,
      boolean night,
      boolean ot,
      boolean leave,
      BigDecimal base,
      BigDecimal np,
      BigDecimal op) {
    String key =
        wage.get("id") + "/" + (rule == null ? "LEAVE" : rule.get("id")) + "/" + night + "/" + ot;
    var b =
        buckets.computeIfAbsent(
            key,
            k -> {
              var m = new LinkedHashMap<String, Object>();
              m.put("wage_version", wage);
              m.put("rule_version", rule);
              m.put("night", night);
              m.put("overtime", ot);
              m.put("paid_leave", leave);
              m.put("minutes", 0);
              m.put("base", BigDecimal.ZERO);
              m.put("night_extra", BigDecimal.ZERO);
              m.put("overtime_extra", BigDecimal.ZERO);
              return m;
            });
    b.put("minutes", (Integer) b.get("minutes") + 1);
    b.put("base", ((BigDecimal) b.get("base")).add(base));
    b.put("night_extra", ((BigDecimal) b.get("night_extra")).add(np));
    b.put("overtime_extra", ((BigDecimal) b.get("overtime_extra")).add(op));
  }
}
