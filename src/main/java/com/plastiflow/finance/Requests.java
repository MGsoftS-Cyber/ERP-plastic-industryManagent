package com.plastiflow.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class Requests {
  private Requests() {}

  public enum AccountKind {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
  }

  public enum InvoiceKind {
    SALE,
    PURCHASE
  }

  public enum AllocationKind {
    EXPENSE,
    PAYROLL
  }

  public record Account(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 160) String name,
      @NotNull AccountKind kind) {}

  public record Period(
      @NotBlank @Size(max = 40) String code,
      @NotNull LocalDate startsOn,
      @NotNull LocalDate endsOn,
      @Pattern(regexp = "[A-Z]{3}") @NotNull String currency) {}

  public record Line(
      @NotNull UUID accountId,
      @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal debit,
      @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal credit) {}

  public record Journal(
      @NotNull UUID eventId,
      @NotNull UUID periodId,
      @NotNull LocalDate bookedOn,
      @NotBlank @Size(max = 120) String reference,
      @NotBlank @Size(max = 1000) String description,
      @NotEmpty @Size(max = 100) List<@Valid Line> lines) {}

  public record Posting(
      @NotNull UUID periodId,
      @NotNull LocalDate bookedOn,
      @NotBlank @Size(max = 120) String reference) {}

  public record Invoice(
      @NotBlank @Size(max = 40) String code,
      @NotNull InvoiceKind kind,
      @NotBlank @Size(max = 160) String partyReference,
      @NotBlank @Size(max = 160) String documentReference,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal net,
      @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal tax,
      @NotNull LocalDate dueOn,
      @NotNull UUID controlAccount,
      @NotNull UUID netAccount,
      UUID taxAccount) {}

  public record Payment(
      @NotNull UUID eventId,
      @NotNull UUID periodId,
      @NotNull LocalDate bookedOn,
      @NotBlank @Size(max = 120) String reference,
      @NotNull UUID bankAccount,
      @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
      @NotBlank @Size(max = 160) String externalReference) {}

  public record Budget(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID periodId,
      @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {}

  public record Expense(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID budgetId,
      @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
      @NotBlank @Size(max = 1000) String description,
      UUID sourceWorkOrder) {}

  public record ExpensePosting(
      @NotNull UUID periodId,
      @NotNull LocalDate bookedOn,
      @NotBlank @Size(max = 120) String reference,
      @NotNull UUID expenseAccount,
      @NotNull UUID payableAccount) {}

  public record PayrollPosting(
      @NotNull UUID periodId,
      @NotNull LocalDate bookedOn,
      @NotBlank @Size(max = 120) String reference,
      @NotNull UUID wagesAccount,
      @NotNull UUID payableAccount,
      @NotNull UUID deductionsAccount) {}

  public record Valuation(
      @NotNull UUID lotId,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal unitCost,
      @NotBlank @Size(max = 1000) String evidence) {}

  public record Allocation(
      @NotNull AllocationKind kind,
      @NotNull UUID sourceId,
      @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {}

  public record Costing(
      @NotNull UUID orderId,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull @Size(max = 100) List<@Valid Allocation> allocations) {}

  public record Scrap(
      @NotBlank @Size(max = 40) String code,
      @NotNull UUID lotId,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal kg,
      @NotBlank @Size(max = 160) String classification,
      @NotBlank @Size(max = 1000) String reason) {}

  public record Recovery(
      @NotNull UUID eventId,
      @NotBlank @Size(max = 40) String lotCode,
      @NotBlank @Size(max = 80) String location,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal inputKg,
      @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal recoveredKg,
      @NotBlank @Size(max = 160) String dispositionReference) {}
}
