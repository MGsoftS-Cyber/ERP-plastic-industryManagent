package com.plastiflow.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Requests {
  private Requests() {}

  public enum ClockKind {
    IN,
    OUT,
    BREAK_START,
    BREAK_END
  }

  public enum AbsenceKind {
    PAID_LEAVE,
    UNPAID_LEAVE,
    ABSENCE
  }

  public enum Basis {
    HOURLY,
    MONTHLY
  }

  public enum PremiumType {
    PERCENT,
    FIXED
  }

  public enum Stacking {
    ADDITIVE,
    MULTIPLICATIVE
  }

  public enum AdjustmentKind {
    BONUS,
    DEDUCTION
  }

  public record Interval(@NotNull Instant start, @NotNull Instant end) {}

  public record Clock(
      @NotNull UUID eventId,
      @NotNull UUID employeeId,
      @NotNull ClockKind kind,
      @NotNull Instant occurredAt,
      @NotBlank @Size(max = 120) String source) {}

  public record Attendance(
      @NotNull UUID employeeId,
      @NotNull Instant start,
      @NotNull Instant end,
      @NotNull @Size(max = 20) List<@Valid Interval> unpaidBreaks,
      @NotNull @Size(max = 100) Set<@NotNull UUID> rawEventIds,
      @NotBlank @Size(max = 1000) String notes) {}

  public record Approval(@Min(0) int overtimeMinutes, @NotBlank @Size(max = 1000) String notes) {}

  public record Absence(
      @NotNull UUID employeeId,
      @NotNull Instant start,
      @NotNull Instant end,
      @NotNull AbsenceKind kind,
      @NotBlank @Size(max = 1000) String reason) {}

  public record Wage(
      @NotNull UUID employeeId,
      @NotNull LocalDate effectiveFrom,
      @NotNull Basis basis,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal amount,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @Min(0) @Max(100000) int monthlyMinutes) {}

  public record Rule(
      @NotNull LocalDate effectiveFrom,
      @NotNull LocalTime nightStart,
      @NotNull LocalTime nightEnd,
      @NotNull PremiumType nightType,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal nightValue,
      @NotNull @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 6)
          BigDecimal overtimePercent,
      @NotNull Stacking stacking) {}

  public record Period(
      @NotBlank @Size(max = 40) String code,
      @NotNull LocalDate start,
      @NotNull LocalDate end,
      @NotBlank @Size(max = 80) String zoneId,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @Min(0) @Max(4) int moneyScale,
      @NotEmpty @Size(max = 500) Set<@NotNull UUID> employeeIds) {}

  public record Adjustment(
      @NotNull UUID employeeId,
      @NotNull AdjustmentKind kind,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal amount,
      @NotBlank @Size(max = 1000) String reason) {}

  public record Decision(@NotNull Boolean approved, @NotBlank @Size(max = 1000) String notes) {}

  public record Notes(@NotBlank @Size(max = 1000) String notes) {}
}
