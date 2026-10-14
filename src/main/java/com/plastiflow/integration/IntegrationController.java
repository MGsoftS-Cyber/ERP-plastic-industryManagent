package com.plastiflow.integration;

import static com.plastiflow.integration.Requests.*;

import com.plastiflow.shared.BusinessException;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integration")
class IntegrationController {
  final IntegrationStore s;
  final DeviceService devices;
  final PortalService portal;
  final ReportService reports;
  final AiService ai;
  final AiProvider provider;

  IntegrationController(
      IntegrationStore s,
      DeviceService devices,
      PortalService portal,
      ReportService reports,
      AiService ai,
      AiProvider provider) {
    this.s = s;
    this.devices = devices;
    this.portal = portal;
    this.reports = reports;
    this.ai = ai;
    this.provider = provider;
  }

  @GetMapping("/overview")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  Map<String, Object> overview() {
    var out = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "device",
            "identity",
            "scale_visit",
            "event",
            "partner",
            "partner_user",
            "portal_request"))
      out.put(table, s.db.queryForList("select * from int_" + table + " limit 200"));
    out.put(
        "employees",
        s.db.queryForList("select id,code,name from mfg_employee where active=true limit 200"));
    out.put("machines", s.db.queryForList("select id,code,name from mfg_machine limit 200"));
    out.put("cameras", s.db.queryForList("select id,code,name from ops_camera limit 200"));
    out.put(
        "visits", s.db.queryForList("select id,reference,state from ops_truck_visit limit 200"));
    out.put(
        "products",
        s.db.queryForList("select id,sku,name from catalog_product where active=true limit 200"));
    out.put(
        "published",
        s.db.queryForList(
            "select p.id,p.sku,p.name from catalog_product p join int_public_product v on"
                + " v.product_id=p.id order by p.sku limit 200"));
    return out;
  }

  @PostMapping("/devices")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  Map<String, Object> device(@Valid @RequestBody Device r) {
    return devices.register(r);
  }

  @PostMapping("/devices/{id}/revoke")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void revoke(@PathVariable UUID id) {
    devices.revoke(id);
  }

  @PostMapping("/devices/{id}/identities")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void identity(@PathVariable UUID id, @Valid @RequestBody Identity r) {
    devices.identity(id, r);
  }

  @PostMapping("/devices/{id}/visits")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void visit(@PathVariable UUID id, @Valid @RequestBody VisitBinding r) {
    devices.visit(id, r);
  }

  @PostMapping("/device-events")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('DEVICE_AGENT')")
  Map<String, Object> event(@Valid @RequestBody Event r) {
    return devices.receive(r);
  }

  @PostMapping("/events/{id}/apply")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  Map<String, Object> apply(@PathVariable UUID id) {
    try {
      return devices.apply(id);
    } catch (BusinessException ex) {
      return devices.failed(id, ex.code());
    } catch (DataIntegrityViolationException ex) {
      return devices.failed(id, "DOMAIN_DATA_CONFLICT");
    }
  }

  @GetMapping("/reports/production")
  @PreAuthorize("hasRole('REPORT_VIEWER')")
  Map<String, Object> production(@RequestParam LocalDate from, @RequestParam LocalDate to) {
    return reports.production(from, to);
  }

  @GetMapping(value = "/reports/production.csv", produces = "text/csv")
  @PreAuthorize("hasRole('REPORT_VIEWER')")
  ResponseEntity<String> productionCsv(@RequestParam LocalDate from, @RequestParam LocalDate to) {
    var report = reports.production(from, to);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) report.get("products");
    return csv(
        "production.csv",
        reports.csv(rows, List.of("sku", "name", "unit", "good_qty", "reject_qty")));
  }

  @GetMapping("/reports/device-health")
  @PreAuthorize("hasRole('REPORT_VIEWER')")
  Map<String, Object> health() {
    return reports.deviceHealth();
  }

  @GetMapping("/reports/financial/{id}")
  @PreAuthorize("hasAnyRole('ACCOUNTANT','FINANCE_MANAGER')")
  Map<String, Object> financial(@PathVariable UUID id) {
    return reports.financial(id);
  }

  @GetMapping(value = "/reports/financial/{id}.csv", produces = "text/csv")
  @PreAuthorize("hasAnyRole('ACCOUNTANT','FINANCE_MANAGER')")
  ResponseEntity<String> financeCsv(@PathVariable UUID id) {
    var report = reports.financial(id);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) report.get("balances");
    return csv(
        "financial.csv",
        reports.csv(rows, List.of("code", "name", "kind", "debit", "credit", "balance")));
  }

  ResponseEntity<String> csv(String file, String body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(body);
  }

  @GetMapping("/ai")
  @PreAuthorize("hasRole('AI_ANALYST')")
  Map<String, Object> ai() {
    return Map.of("enabled", provider.enabled(), "jobs", ai.list());
  }

  @PostMapping("/ai/jobs")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('AI_ANALYST')")
  Map<String, Object> draft(@Valid @RequestBody AiRequest r) {
    return ai.create(r);
  }

  @GetMapping("/ai/jobs/{id}")
  @PreAuthorize("hasRole('AI_ANALYST')")
  Map<String, Object> aiDetail(@PathVariable UUID id) {
    return ai.detail(id);
  }

  @PostMapping("/ai/jobs/{id}/run")
  @PreAuthorize("hasRole('AI_ANALYST')")
  Map<String, Object> run(@PathVariable UUID id) {
    return ai.run(id);
  }

  @PostMapping("/ai/jobs/{id}/recover")
  @PreAuthorize("hasRole('AI_ANALYST')")
  void recover(@PathVariable UUID id) {
    ai.recover(id);
  }

  @PostMapping("/ai/jobs/{id}/review")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void review(@PathVariable UUID id, @Valid @RequestBody Decision r) {
    ai.review(id, r);
  }

  @PostMapping("/partners")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  Map<String, Object> partner(@Valid @RequestBody Partner r) {
    return portal.partner(r);
  }

  @PostMapping("/partners/{id}/users")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void partnerUser(@PathVariable UUID id, @Valid @RequestBody PartnerUser r) {
    portal.user(id, r);
  }

  @PostMapping("/partners/{id}/revoke")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void revokePartner(@PathVariable UUID id) {
    portal.revoke(id);
  }

  @PostMapping("/partner-users/{principal}/revoke")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void revokePartnerUser(@PathVariable String principal) {
    portal.revokeUser(principal);
  }

  @PostMapping("/published-products/{id}")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void publish(@PathVariable UUID id) {
    portal.publish(id);
  }

  @DeleteMapping("/published-products/{id}")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void unpublish(@PathVariable UUID id) {
    portal.unpublish(id);
  }

  @PostMapping("/portal-requests/{id}/decision")
  @PreAuthorize("hasRole('INTEGRATION_MANAGER')")
  void decision(@PathVariable UUID id, @Valid @RequestBody Decision r) {
    portal.decide(id, r);
  }
}
