package com.plastiflow.integration;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class ReportService {
  final IntegrationStore s;

  ReportService(IntegrationStore s) {
    this.s = s;
  }

  Map<String, Object> production(LocalDate from, LocalDate to) {
    s.require(
        to.isAfter(from) && !to.isAfter(from.plusDays(93)),
        "REPORT_RANGE",
        "Choose a positive date range of at most 93 days; end date is exclusive.");
    var events =
        s.db.queryForList(
            "select p.sku,p.name,p.unit,e.good_qty,e.reject_qty from mfg_output e join mfg_order o"
                + " on o.id=e.order_id join mfg_recipe r on r.id=o.recipe_id join catalog_product p"
                + " on p.id=r.product_id where e.created_at>=? and e.created_at<? order by"
                + " p.sku,e.created_at limit 10001",
            s.time(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
            s.time(to.atStartOfDay(ZoneOffset.UTC).toInstant()));
    s.require(
        events.size() <= 10000,
        "REPORT_TOO_LARGE",
        "Narrow the date range; report exceeds 10,000 output events.");
    var grouped = new LinkedHashMap<String, Map<String, Object>>();
    for (var e : events) {
      var row =
          grouped.computeIfAbsent(
              (String) e.get("sku"),
              k -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("sku", k);
                item.put("name", e.get("name"));
                item.put("unit", e.get("unit"));
                item.put("good_qty", BigDecimal.ZERO);
                item.put("reject_qty", BigDecimal.ZERO);
                return item;
              });
      row.put("good_qty", ((BigDecimal) row.get("good_qty")).add((BigDecimal) e.get("good_qty")));
      row.put(
          "reject_qty", ((BigDecimal) row.get("reject_qty")).add((BigDecimal) e.get("reject_qty")));
    }
    var byProduct = new ArrayList<>(grouped.values());
    return Map.of(
        "from",
        from,
        "to",
        to,
        "timezone",
        "UTC",
        "output_events",
        events.size(),
        "products",
        byProduct,
        "note",
        "Actual output events in range; quantities must be compared within the same product/unit."
            + " Not OEE or financial revenue.");
  }

  Map<String, Object> deviceHealth() {
    return Map.of(
        "devices",
        s.db.queryForList(
            "select code,kind,active,last_seen,last_applied from int_device order by code limit"
                + " 200"),
        "eventStates",
        s.db.queryForList(
            "select state,count(*) count from int_event group by state order by state"));
  }

  Map<String, Object> financial(UUID period) {
    var p = s.one("select id,code,currency,ends_on from fin_period where id=?", period);
    var rows =
        s.db.queryForList(
            "select a.code,a.name,a.kind,sum(e.debit) debit,sum(e.credit)"
                + " credit,sum(e.debit-e.credit) balance from fin_entry e join fin_journal j on"
                + " j.id=e.journal_id join fin_period p on p.id=j.period_id join fin_account a on"
                + " a.id=e.account_id where p.currency=? and j.booked_on<? group by"
                + " a.code,a.name,a.kind order by a.code",
            p.get("currency"),
            p.get("ends_on"));
    return Map.of("period", p, "balances", rows);
  }

  String csv(List<Map<String, Object>> rows, List<String> columns) {
    var out = new StringBuilder();
    out.append(String.join(",", columns)).append("\r\n");
    for (var row : rows) {
      var cells = new ArrayList<String>();
      for (String column : columns) {
        String value = Objects.toString(row.get(column), "");
        if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        cells.add("\"" + value.replace("\"", "\"\"") + "\"");
      }
      out.append(String.join(",", cells)).append("\r\n");
    }
    return out.toString();
  }
}
