package com.plastiflow.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Requests {
  private Requests() {}

  public enum DeviceKind {
    ATTENDANCE,
    CAMERA,
    SENSOR,
    SCALE
  }

  public enum PartnerKind {
    CUSTOMER,
    SUPPLIER
  }

  public enum RequestKind {
    SALES_REQUEST,
    SUPPLY_OFFER,
    DELIVERY_NOTICE
  }

  public record Device(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotNull DeviceKind kind,
      @NotBlank @Size(max = 160) String principal,
      UUID targetId,
      @Size(max = 40) String metric,
      @Size(max = 24) String unit) {}

  public record Identity(
      @NotBlank @Size(max = 80) String externalPerson, @NotNull UUID employeeId) {}

  public record VisitBinding(@NotNull UUID visitId) {}

  public record Event(
      @NotNull UUID eventId,
      @Min(0) long sequence,
      @NotNull Instant occurredAt,
      @NotNull @Size(min = 1, max = 5)
          Map<@NotBlank @Size(max = 40) String, @NotBlank @Size(max = 240) String> data) {}

  public record Partner(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotNull PartnerKind kind,
      @NotBlank @Size(max = 160) String partyReference) {}

  public record PartnerUser(@NotBlank @Size(max = 160) String principal) {}

  public record RequestLine(
      @NotNull UUID productId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal quantity) {}

  public record PortalRequest(
      @NotNull UUID eventId,
      @NotNull RequestKind kind,
      @NotBlank @Size(max = 80) String reference,
      @NotBlank @Size(max = 1000) String description,
      @NotNull @Size(max = 30) List<@Valid RequestLine> lines) {}

  public record Decision(@NotNull Boolean accepted, @NotBlank @Size(max = 1000) String notes) {}

  public record AiRequest(@NotNull UUID eventId, @NotNull LocalDate from, @NotNull LocalDate to) {}
}
