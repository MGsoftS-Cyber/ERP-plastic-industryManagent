package com.plastiflow.manufacturing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Requests {
  private Requests() {}

  public enum Process {
    INJECTION,
    EXTRUSION,
    BLOW_MOLDING,
    RECYCLING
  }

  public enum EquipmentState {
    AVAILABLE,
    UNAVAILABLE
  }

  public enum Quality {
    APPROVED,
    QUARANTINED,
    REJECTED
  }

  public record Machine(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotNull Process process,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal capacityKg) {}

  public record Mold(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @Min(1) int cavities) {}

  public record State(@NotNull EquipmentState state) {}

  public record Employee(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Size(max = 160) String userSubject) {}

  public record Qualification(@NotNull UUID machineId, @NotNull LocalDate validUntil) {}

  public record Shift(@NotNull UUID employeeId, @NotNull Instant start, @NotNull Instant end) {}

  public record Component(
      @NotNull UUID materialId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal kgPerUnit) {}

  public record Operation(
      @NotBlank @Size(max = 160) String name,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6)
          BigDecimal minutesPerUnit) {}

  public record Recipe(
      @NotNull UUID productId,
      @NotNull Process process,
      @NotNull @DecimalMin("0.000001") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
          BigDecimal yieldFactor,
      @NotNull @Size(max = 30)
          Map<@NotBlank @Size(max = 80) String, @NotBlank @Size(max = 160) String> settings,
      @NotBlank @Size(max = 500) String packaging,
      @NotEmpty @Size(max = 100) List<@Valid Component> components,
      @NotEmpty @Size(max = 100) List<@Valid Operation> operations) {}

  public record Metric(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Size(max = 40) String unit,
      @NotNull @Digits(integer = 13, fraction = 6) BigDecimal minimum,
      @NotNull @Digits(integer = 13, fraction = 6) BigDecimal maximum) {}

  public record InspectionPlan(
      @NotNull UUID productId, @NotEmpty @Size(max = 100) List<@Valid Metric> metrics) {}

  public record Order(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 120) String salesReference,
      @NotNull UUID recipeId,
      @NotNull UUID inspectionPlanId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal plannedQty) {}

  public record Lot(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID materialId,
      @NotBlank @Size(max = 120) String supplierReference,
      @NotBlank @Size(max = 80) String location,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal receivedKg) {}

  public record Disposition(@NotNull Quality quality, @NotBlank @Size(max = 500) String reason) {}

  public record Consume(
      @NotNull UUID eventId,
      @NotNull UUID lotId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal kg) {}

  public record Output(
      @NotNull UUID eventId,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal goodQty,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal rejectQty,
      @NotBlank @Size(max = 240) String reason) {}

  public record Downtime(
      @NotNull Instant start, @NotNull Instant end, @NotBlank @Size(max = 240) String reason) {}

  public record Measurements(
      @NotEmpty @Size(max = 100)
          Map<
                  @NotBlank @Size(max = 80) String,
                  @NotNull @Digits(integer = 13, fraction = 6) BigDecimal>
              values) {}

  public record Schedule(
      @NotNull UUID machineId,
      UUID moldId,
      @NotNull UUID employeeId,
      @NotNull UUID shiftId,
      @NotNull Instant start,
      @NotNull Instant end) {}

  public record Maintenance(
      @NotNull UUID machineId,
      @NotNull Instant start,
      @NotNull Instant end,
      @NotBlank @Size(max = 240) String reason) {}

  public record Pallet(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID batchId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal qty) {}

  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  public record Dispatch(
      @NotNull UUID palletId,
      @NotBlank @Size(max = 120) String shipmentReference,
      @NotBlank @Size(max = 120) String customerReference) {}

  public record Abort(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000)
          String reason) {}
}
