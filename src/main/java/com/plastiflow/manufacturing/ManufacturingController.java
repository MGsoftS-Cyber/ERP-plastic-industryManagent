package com.plastiflow.manufacturing;

import static com.plastiflow.manufacturing.Requests.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manufacturing")
@PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR','QUALITY_MANAGER','WORKFORCE_MANAGER')")
class ManufacturingController {
  private final Store s;
  private final EquipmentService equipment;
  private final RecipeService recipes;
  private final ProductionService production;
  private final WorkforceService workforce;
  private final ScheduleService scheduling;
  private final QualityService quality;
  private final TraceService trace;

  ManufacturingController(
      Store s,
      EquipmentService equipment,
      RecipeService recipes,
      ProductionService production,
      WorkforceService workforce,
      ScheduleService scheduling,
      QualityService quality,
      TraceService trace) {
    this.s = s;
    this.equipment = equipment;
    this.recipes = recipes;
    this.production = production;
    this.workforce = workforce;
    this.scheduling = scheduling;
    this.quality = quality;
    this.trace = trace;
  }

  @GetMapping("/overview")
  Map<String, Object> overview() {
    return Map.ofEntries(
        Map.entry(
            "machines", s.db.queryForList("select * from mfg_machine order by code limit 200")),
        Map.entry("molds", s.db.queryForList("select * from mfg_mold order by code limit 200")),
        Map.entry(
            "recipes",
            s.db.queryForList("select * from mfg_recipe order by created_at desc limit 200")),
        Map.entry("orders", s.db.queryForList("select * from mfg_order order by code limit 200")),
        Map.entry("lots", s.db.queryForList("select * from mfg_lot order by code limit 200")),
        Map.entry("batches", s.db.queryForList("select * from mfg_batch limit 200")),
        Map.entry(
            "employees",
            s.db.queryForList(
                "select id,code,name,active from mfg_employee order by code limit 200")),
        Map.entry(
            "shifts",
            s.db.queryForList("select * from mfg_shift order by starts_at desc limit 200")),
        Map.entry(
            "schedule",
            s.db.queryForList("select * from mfg_schedule order by starts_at limit 200")),
        Map.entry(
            "maintenance",
            s.db.queryForList("select * from mfg_maintenance_block order by starts_at limit 200")),
        Map.entry("plans", s.db.queryForList("select * from mfg_inspection_plan limit 200")),
        Map.entry(
            "nonconformities", s.db.queryForList("select * from mfg_nonconformity limit 200")),
        Map.entry(
            "pallets", s.db.queryForList("select * from mfg_pallet order by code limit 200")));
  }

  @PostMapping("/machines")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> machine(@Valid @RequestBody Machine r) {
    return equipment.machine(r);
  }

  @PostMapping("/molds")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> mold(@Valid @RequestBody Mold r) {
    return equipment.mold(r);
  }

  @PutMapping("/machines/{machine}/molds/{mold}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  void compatibility(@PathVariable UUID machine, @PathVariable UUID mold) {
    equipment.compatibility(machine, mold);
  }

  @PutMapping("/machines/{id}/state")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  void machineState(@PathVariable UUID id, @Valid @RequestBody State r) {
    equipment.machineState(id, r);
  }

  @PutMapping("/molds/{id}/state")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  void moldState(@PathVariable UUID id, @Valid @RequestBody State r) {
    equipment.moldState(id, r);
  }

  @PostMapping("/recipes")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> recipe(@Valid @RequestBody Recipe r) {
    return recipes.create(r);
  }

  @GetMapping("/recipes/{id}")
  Map<String, Object> recipe(@PathVariable UUID id) {
    return recipes.detail(id);
  }

  @PostMapping("/employees")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> employee(@Valid @RequestBody Employee r) {
    return workforce.employee(r);
  }

  @PutMapping("/employees/{id}/qualifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  void qualification(@PathVariable UUID id, @Valid @RequestBody Qualification r) {
    workforce.qualification(id, r);
  }

  @PostMapping("/shifts")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('WORKFORCE_MANAGER')")
  Map<String, Object> shift(@Valid @RequestBody Shift r) {
    return workforce.shift(r);
  }

  @PostMapping("/orders")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> order(@Valid @RequestBody Order r) {
    return production.create(r);
  }

  @GetMapping("/orders/{id}")
  Map<String, Object> order(@PathVariable UUID id) {
    return production.detail(id);
  }

  @PutMapping("/orders/{id}/schedule")
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> schedule(@PathVariable UUID id, @Valid @RequestBody Schedule r) {
    return scheduling.schedule(id, r);
  }

  @PostMapping("/maintenance-blocks")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> maintenance(@Valid @RequestBody Maintenance r) {
    return scheduling.maintenance(r);
  }

  @PostMapping("/lots")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> lot(@Valid @RequestBody Lot r) {
    return production.lot(r);
  }

  @PutMapping("/lots/{id}/quality")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('QUALITY_MANAGER')")
  void lotQuality(@PathVariable UUID id, @Valid @RequestBody Disposition r) {
    quality.lotDecision(id, r);
  }

  @PostMapping("/orders/{id}/start")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR')")
  void start(@PathVariable UUID id) {
    production.start(id);
  }

  @PostMapping("/orders/{id}/consumptions")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR')")
  Map<String, Object> consume(@PathVariable UUID id, @Valid @RequestBody Consume r) {
    return production.consume(id, r);
  }

  @PostMapping("/orders/{id}/outputs")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR')")
  Map<String, Object> output(@PathVariable UUID id, @Valid @RequestBody Output r) {
    return production.output(id, r);
  }

  @PostMapping("/orders/{id}/downtime")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR')")
  void downtime(@PathVariable UUID id, @Valid @RequestBody Downtime r) {
    production.downtime(id, r);
  }

  @PostMapping("/orders/{id}/complete")
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','OPERATOR')")
  Map<String, Object> complete(@PathVariable UUID id) {
    return production.complete(id);
  }

  @PostMapping("/inspection-plans")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('QUALITY_MANAGER')")
  Map<String, Object> plan(@Valid @RequestBody InspectionPlan r) {
    return quality.plan(r);
  }

  @PostMapping("/batches/{id}/inspections")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('QUALITY_MANAGER')")
  Map<String, Object> inspect(@PathVariable UUID id, @Valid @RequestBody Measurements r) {
    return quality.inspect(id, r);
  }

  @PutMapping("/batches/{id}/quality")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('QUALITY_MANAGER')")
  void release(@PathVariable UUID id, @Valid @RequestBody Disposition r) {
    quality.release(id, r);
  }

  @PutMapping("/nonconformities/{id}/resolve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('QUALITY_MANAGER')")
  void resolve(@PathVariable UUID id, @Valid @RequestBody Reason r) {
    quality.resolve(id, r.reason());
  }

  @PostMapping("/pallets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> pallet(@Valid @RequestBody Pallet r) {
    return trace.pallet(r);
  }

  @PostMapping("/dispatch-references")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> dispatch(@Valid @RequestBody Dispatch r) {
    return trace.dispatch(r);
  }

  @GetMapping("/batches/{id}/trace")
  Map<String, Object> batchTrace(@PathVariable UUID id) {
    return trace.batch(id);
  }

  @GetMapping("/lots/{id}/trace")
  Map<String, Object> lotTrace(@PathVariable UUID id) {
    return trace.lot(id);
  }

  @PostMapping("/orders/{id}/abort")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  void abort(@PathVariable UUID id, @Valid @RequestBody Abort r) {
    production.abort(id, r);
  }

  @PostMapping("/maintenance-blocks/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  void cancelWindow(@PathVariable UUID id, @Valid @RequestBody Abort r) {
    scheduling.cancelMaintenance(id, r);
  }
}
