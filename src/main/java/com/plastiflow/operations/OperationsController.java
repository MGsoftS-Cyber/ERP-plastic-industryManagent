package com.plastiflow.operations;

import static com.plastiflow.operations.Requests.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
class OperationsController {
  private final OpsStore s;
  private final MaintenanceService maintenance;
  private final FacilitiesService facilities;
  private final YardService yard;
  private final AccessService access;

  OperationsController(
      OpsStore s,
      MaintenanceService maintenance,
      FacilitiesService facilities,
      YardService yard,
      AccessService access) {
    this.s = s;
    this.maintenance = maintenance;
    this.facilities = facilities;
    this.yard = yard;
    this.access = access;
  }

  @GetMapping("/maintenance/overview")
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  Map<String, Object> maintenanceOverview() {
    var works =
        s.role("MAINTENANCE_MANAGER")
            ? s.db.queryForList("select * from ops_work_order order by created_at desc limit 200")
            : s.db.queryForList(
                "select w.* from ops_work_order w join ops_technician t on t.id=w.technician_id"
                    + " where t.user_subject=? order by w.created_at desc limit 200",
                s.actor());
    return Map.of(
        "workOrders",
        works,
        "technicians",
        s.db.queryForList("select id,code,name from ops_technician order by code limit 200"),
        "plans",
        s.db.queryForList("select * from ops_plan order by next_due limit 200"),
        "assets",
        s.db.queryForList("select * from ops_asset order by code limit 200"),
        "partStock",
        s.db.queryForList(
            "select st.*,p.sku,p.name,p.unit from ops_part_stock st join catalog_product p on"
                + " p.id=st.product_id limit 200"),
        "machines",
        s.db.queryForList("select * from mfg_machine order by code limit 200"));
  }

  @GetMapping("/facilities/overview")
  @PreAuthorize("hasRole('FACILITIES_MANAGER')")
  Map<String, Object> facilitiesOverview() {
    return Map.of(
        "areas",
        s.db.queryForList("select * from ops_area order by code limit 200"),
        "assets",
        s.db.queryForList("select * from ops_asset order by code limit 200"),
        "contracts",
        s.db.queryForList("select * from ops_service_contract limit 200"),
        "inspections",
        s.db.queryForList("select * from ops_asset_inspection order by created_at desc limit 200"),
        "workOrders",
        s.db.queryForList("select * from ops_work_order where asset_id is not null limit 200"));
  }

  @GetMapping("/yard/overview")
  @PreAuthorize("hasAnyRole('LOGISTICS_MANAGER','GATE_OFFICER')")
  Map<String, Object> yardOverview() {
    return Map.of(
        "spaces",
        s.db.queryForList("select * from ops_yard_space order by code limit 200"),
        "trucks",
        s.db.queryForList("select * from ops_truck_visit order by planned_start desc limit 200"),
        "bookings",
        s.db.queryForList("select * from ops_dock_booking order by starts_at desc limit 200"));
  }

  @GetMapping("/security/overview")
  @PreAuthorize("hasAnyRole('SECURITY_MANAGER','GATE_OFFICER')")
  Map<String, Object> securityOverview() {
    return Map.of(
        "zones",
        s.db.queryForList("select * from ops_access_zone order by code limit 200"),
        "visitors",
        s.db.queryForList("select * from ops_visitor order by valid_from desc limit 200"),
        "badges",
        s.db.queryForList("select * from ops_badge order by code limit 200"),
        "incidents",
        s.db.queryForList("select * from ops_incident order by created_at desc limit 200"),
        "cameras",
        s.db.queryForList("select * from ops_camera order by code limit 200"),
        "presence",
        s.db.queryForList("select * from ops_presence limit 200"));
  }

  @PostMapping("/technicians")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> technician(@Valid @RequestBody Technician r) {
    return maintenance.technician(r);
  }

  @PostMapping("/work-orders")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> work(@Valid @RequestBody WorkOrder r) {
    return maintenance.create(r);
  }

  @GetMapping("/work-orders/{id}")
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  Map<String, Object> work(@PathVariable UUID id) {
    return maintenance.detail(id);
  }

  @PutMapping("/work-orders/{id}/assignment")
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> assign(@PathVariable UUID id, @Valid @RequestBody Assignment r) {
    return maintenance.assign(id, r);
  }

  @PostMapping("/work-orders/{id}/start")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  void start(@PathVariable UUID id) {
    maintenance.start(id);
  }

  @PostMapping("/work-orders/{id}/submit")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  void submit(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    maintenance.submit(id, r);
  }

  @PostMapping("/work-orders/{id}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  void release(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    maintenance.release(id, r);
  }

  @PostMapping("/work-orders/{id}/windows")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  void window(@PathVariable UUID id, @Valid @RequestBody Window r) {
    maintenance.window(id, r);
  }

  @PostMapping("/maintenance-plans")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> plan(@Valid @RequestBody Plan r) {
    return maintenance.plan(r);
  }

  @PostMapping("/maintenance-plans/{id}/generate")
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> generate(@PathVariable UUID id) {
    return maintenance.generate(id);
  }

  @PostMapping("/parts/receipts")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('MAINTENANCE_MANAGER')")
  Map<String, Object> receipt(@Valid @RequestBody PartReceipt r) {
    return maintenance.receipt(r);
  }

  @PostMapping("/work-orders/{id}/parts")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  Map<String, Object> use(@PathVariable UUID id, @Valid @RequestBody PartUse r) {
    return maintenance.use(id, r);
  }

  @PostMapping("/work-orders/{id}/labor")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('MAINTENANCE_MANAGER','MAINTENANCE_TECHNICIAN')")
  Map<String, Object> labor(@PathVariable UUID id, @Valid @RequestBody Labor r) {
    return maintenance.labor(id, r);
  }

  @PostMapping("/areas")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FACILITIES_MANAGER')")
  Map<String, Object> area(@Valid @RequestBody Area r) {
    return facilities.area(r);
  }

  @PostMapping("/assets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FACILITIES_MANAGER')")
  Map<String, Object> asset(@Valid @RequestBody Asset r) {
    return facilities.asset(r);
  }

  @PostMapping("/assets/{id}/inspections")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FACILITIES_MANAGER')")
  Map<String, Object> inspect(@PathVariable UUID id, @Valid @RequestBody Inspection r) {
    return facilities.inspection(id, r);
  }

  @PostMapping("/service-contracts")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FACILITIES_MANAGER')")
  Map<String, Object> contract(@Valid @RequestBody Contract r) {
    return facilities.contract(r);
  }

  @PostMapping("/yard/spaces")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  Map<String, Object> space(@Valid @RequestBody Space r) {
    return yard.space(r);
  }

  @PostMapping("/truck-visits")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  Map<String, Object> truck(@Valid @RequestBody Truck r) {
    return yard.truck(r);
  }

  @GetMapping("/truck-visits/{id}")
  @PreAuthorize("hasAnyRole('LOGISTICS_MANAGER','GATE_OFFICER')")
  Map<String, Object> truck(@PathVariable UUID id) {
    return yard.detail(id);
  }

  @PutMapping("/truck-visits/{id}/booking")
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  Map<String, Object> booking(@PathVariable UUID id, @Valid @RequestBody Booking r) {
    return yard.booking(id, r);
  }

  @PostMapping("/truck-visits/{id}/arrive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  void arrive(@PathVariable UUID id, @Valid @RequestBody TruckNotes r) {
    yard.arrive(id, r);
  }

  @PostMapping("/truck-visits/{id}/gate-check")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  void gate(@PathVariable UUID id, @Valid @RequestBody GateCheck r) {
    yard.gate(id, r);
  }

  @PutMapping("/truck-visits/{id}/space")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  void assignSpace(@PathVariable UUID id, @Valid @RequestBody SpaceAssignment r) {
    yard.assign(id, r);
  }

  @PostMapping("/truck-visits/{id}/handling")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  void handling(@PathVariable UUID id, @Valid @RequestBody TruckNotes r) {
    yard.handling(id, r);
  }

  @PostMapping("/truck-visits/{id}/finish")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  void finish(@PathVariable UUID id, @Valid @RequestBody FinishHandling r) {
    yard.finish(id, r);
  }

  @PostMapping("/truck-visits/{id}/weights")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('LOGISTICS_MANAGER','GATE_OFFICER')")
  Map<String, Object> weight(@PathVariable UUID id, @Valid @RequestBody Weight r) {
    return yard.weight(id, r);
  }

  @PostMapping("/truck-visits/{id}/depart")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  void depart(@PathVariable UUID id, @Valid @RequestBody TruckNotes r) {
    yard.depart(id, r);
  }

  @PostMapping("/truck-visits/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
  void cancel(@PathVariable UUID id, @Valid @RequestBody TruckNotes r) {
    yard.cancel(id, r);
  }

  @PostMapping("/zones")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  Map<String, Object> zone(@Valid @RequestBody Zone r) {
    return access.zone(r);
  }

  @PostMapping("/visitors")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  Map<String, Object> visitor(@Valid @RequestBody Visitor r) {
    return access.visitor(r);
  }

  @GetMapping("/visitors/{id}")
  @PreAuthorize("hasAnyRole('SECURITY_MANAGER','GATE_OFFICER')")
  Map<String, Object> visitor(@PathVariable UUID id) {
    return access.detail(id);
  }

  @PostMapping("/badges")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  void badge(@Valid @RequestBody Badge r) {
    access.badge(r);
  }

  @PostMapping("/visitors/{id}/check-in")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  void checkIn(@PathVariable UUID id, @Valid @RequestBody CheckIn r) {
    access.checkIn(id, r);
  }

  @PostMapping("/visitors/{id}/access")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  Map<String, Object> zoneAccess(@PathVariable UUID id, @Valid @RequestBody Access r) {
    return access.access(id, r);
  }

  @PostMapping("/visitors/{id}/check-out")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('GATE_OFFICER')")
  void checkOut(@PathVariable UUID id, @Valid @RequestBody CheckOut r) {
    access.checkOut(id, r);
  }

  @PostMapping("/visitors/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  void cancelVisitor(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    access.cancel(id, r);
  }

  @PostMapping("/incidents")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('SECURITY_MANAGER','GATE_OFFICER')")
  Map<String, Object> incident(@Valid @RequestBody Incident r) {
    return access.incident(r);
  }

  @PostMapping("/incidents/{id}/resolve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  void resolve(@PathVariable UUID id, @Valid @RequestBody Notes r) {
    access.resolve(id, r);
  }

  @PostMapping("/cameras")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  Map<String, Object> camera(@Valid @RequestBody Camera r) {
    return access.camera(r);
  }

  @PutMapping("/cameras/{id}/state")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SECURITY_MANAGER')")
  void cameraState(@PathVariable UUID id, @Valid @RequestBody CameraStatus r) {
    access.cameraStatus(id, r);
  }
}
