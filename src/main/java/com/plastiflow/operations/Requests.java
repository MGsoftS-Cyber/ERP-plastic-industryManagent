package com.plastiflow.operations;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Requests {
  private Requests() {}

  public enum WorkKind {
    BREAKDOWN,
    PREVENTIVE,
    FACILITY
  }

  public enum SpaceKind {
    PARKING,
    DOCK
  }

  public enum Purpose {
    DELIVERY,
    COLLECTION
  }

  public enum CameraState {
    ONLINE,
    OFFLINE,
    MAINTENANCE
  }

  public enum Severity {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum WeightKind {
    TARE,
    GROSS
  }

  public enum Direction {
    ENTER,
    EXIT
  }

  public record Area(
      @NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name) {}

  public record Asset(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID areaId,
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Size(max = 40) String kind) {}

  public record Technician(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Size(max = 160) String userSubject) {}

  public record WorkOrder(
      @NotBlank @Size(max = 40) String code,
      UUID machineId,
      UUID assetId,
      @NotNull WorkKind kind,
      @NotBlank @Size(max = 500) String description,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record Assignment(@NotNull UUID technicianId) {}

  public record Notes(@NotBlank @Size(max = 1000) String notes) {}

  public record Plan(
      @NotNull UUID machineId,
      @Min(1) @Max(3650) int intervalDays,
      @NotNull LocalDate nextDue,
      @NotBlank @Size(max = 500) String instructions,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record Window(
      @NotNull Instant start, @NotNull Instant end, @NotBlank @Size(max = 240) String reason) {}

  public record PartReceipt(
      @NotNull UUID eventId,
      @NotNull UUID productId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal unitCost,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record PartUse(
      @NotNull UUID eventId,
      @NotNull UUID productId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {}

  public record Labor(
      @NotNull UUID eventId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 6, fraction = 6) BigDecimal hours,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal hourlyRate) {}

  public record Contract(
      @NotNull UUID assetId,
      @NotBlank @Size(max = 120) String supplierReference,
      @NotNull LocalDate startsOn,
      @NotNull LocalDate endsOn,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal amount,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record Inspection(@NotNull Boolean passed, @NotBlank @Size(max = 1000) String notes) {}

  public record Space(@NotBlank @Size(max = 40) String code, @NotNull SpaceKind kind) {}

  public record Truck(
      @NotBlank @Size(max = 40) String reference,
      @NotBlank @Size(max = 160) String carrier,
      @NotBlank @Size(max = 160) String driver,
      @NotBlank @Size(max = 40) String tractorPlate,
      @NotBlank @Size(max = 40) String trailerPlate,
      @NotNull Purpose purpose,
      @NotBlank @Size(max = 120) String documentReference,
      @NotNull Instant plannedStart,
      @NotNull Instant plannedEnd) {}

  public record Booking(@NotNull UUID spaceId, @NotNull Instant start, @NotNull Instant end) {}

  public record SpaceAssignment(@NotNull UUID spaceId) {}

  public record GateCheck(@NotNull Boolean accepted, @NotBlank @Size(max = 500) String notes) {}

  public record TruckNotes(@NotBlank @Size(max = 500) String notes) {}

  public record FinishHandling(
      @NotNull Boolean documentsChecked, @NotBlank @Size(max = 500) String notes) {}

  public record Weight(
      @NotNull UUID eventId,
      @NotNull WeightKind kind,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal kg,
      @NotNull Instant measuredAt) {}

  public record Zone(
      @NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name) {}

  public record Visitor(
      @NotBlank @Size(max = 40) String reference,
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Size(max = 160) String company,
      @NotBlank @Size(max = 120) String hostReference,
      @NotNull Instant validFrom,
      @NotNull Instant validTo,
      @NotEmpty @Size(max = 30) Set<@NotNull UUID> zoneIds) {}

  public record Badge(@NotBlank @Size(max = 40) String code) {}

  public record CheckIn(@NotBlank @Size(max = 40) String badge, @NotNull Boolean safetyBriefed) {}

  public record CheckOut(@NotNull Boolean badgeReturned) {}

  public record Access(@NotNull UUID eventId, @NotNull UUID zoneId, @NotNull Direction direction) {}

  public record Incident(
      UUID zoneId, @NotNull Severity severity, @NotBlank @Size(max = 1000) String description) {}

  public record Camera(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID zoneId,
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Size(max = 160) String vmsReference) {}

  public record CameraStatus(@NotNull CameraState state) {}
}
