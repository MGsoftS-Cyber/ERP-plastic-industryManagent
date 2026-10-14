package com.plastiflow.finance;

import static com.plastiflow.finance.Requests.*;

import java.math.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AccountingService {
  final FinanceStore s;

  AccountingService(FinanceStore s) {
    this.s = s;
  }

  Map<String, Object> account(Account r) {
    s.lock();
    UUID id = s.id();
    s.db.update("insert into fin_account values(?,?,?,?)", id, r.code(), r.name(), r.kind().name());
    s.audit(id, "ACCOUNT_CREATED", r);
    return Map.of("id", id);
  }

  Map<String, Object> period(Period r) {
    s.lock();
    s.currency(r.currency());
    s.require(
        r.endsOn().isAfter(r.startsOn()), "INVALID_PERIOD", "End date must follow start date.");
    s.require(
        s.db
            .queryForList(
                "select id from fin_period where currency=? and starts_on<? and ends_on>?",
                r.currency(),
                r.endsOn(),
                r.startsOn())
            .isEmpty(),
        "PERIOD_OVERLAP",
        "Accounting periods in a currency cannot overlap.");
    UUID id = s.id();
    s.db.update(
        "insert into fin_period values(?,?,?,?,?,'OPEN')",
        id,
        r.code(),
        r.startsOn(),
        r.endsOn(),
        r.currency());
    s.audit(id, "PERIOD_CREATED", r);
    return Map.of("id", id);
  }

  void close(UUID id) {
    s.lock();
    var p = s.one("select * from fin_period where id=? for update", id);
    s.require("OPEN".equals(p.get("state")), "PERIOD_CLOSED", "Period already closed.");
    s.require(
        s.db
            .queryForList(
                "select e.id from fin_expense e join fin_budget b on b.id=e.budget_id where"
                    + " b.period_id=? and e.state not in ('POSTED','CANCELLED')",
                id)
            .isEmpty(),
        "UNPOSTED_EXPENSES",
        "Post all approved expenses or resolve drafts before closing.");
    s.db.update("update fin_period set state='CLOSED' where id=?", id);
    s.audit(id, "PERIOD_CLOSED", Map.of());
  }

  Map<String, Object> journal(Journal r) {
    s.lock();
    UUID id =
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "MANUAL",
            r.eventId(),
            r.description(),
            r.lines());
    return detail(id);
  }

  UUID post(
      UUID period,
      LocalDate date,
      String ref,
      String source,
      UUID sourceId,
      String description,
      List<Line> lines) {
    s.period(period, date);
    s.require(lines.size() >= 2, "UNBALANCED_JOURNAL", "At least two nonzero lines are required.");
    BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
    for (var l : lines) {
      s.one("select * from fin_account where id=?", l.accountId());
      s.require(
          (l.debit().signum() > 0 && l.credit().signum() == 0)
              || (l.credit().signum() > 0 && l.debit().signum() == 0),
          "INVALID_LINE",
          "Each line needs either a positive debit or positive credit.");
      s.require(
          l.debit().scale() <= 2 && l.credit().scale() <= 2,
          "MONEY_SCALE",
          "Amounts must use at most two decimals.");
      debit = debit.add(l.debit());
      credit = credit.add(l.credit());
    }
    s.require(
        debit.compareTo(credit) == 0 && debit.signum() > 0,
        "UNBALANCED_JOURNAL",
        "Debits must equal credits.");
    UUID id = s.id();
    s.db.update(
        "insert into fin_journal values(?,?,?,?,?,?,?,?,?)",
        id,
        period,
        date,
        ref,
        source,
        sourceId,
        description,
        s.actor(),
        OffsetDateTime.now(ZoneOffset.UTC));
    int n = 0;
    for (var l : lines)
      s.db.update(
          "insert into fin_entry values(?,?,?,?,?)", id, ++n, l.accountId(), l.debit(), l.credit());
    s.audit(
        id,
        "JOURNAL_POSTED",
        Map.of("reference", ref, "source", source, "sourceId", sourceId, "debit", debit));
    return id;
  }

  Map<String, Object> reverse(UUID id, Posting r) {
    s.lock();
    var j = s.one("select * from fin_journal where id=?", id);
    s.require(
        "MANUAL".equals(j.get("source_type")),
        "SOURCE_DOCUMENT",
        "Only manual journals can be reversed here; document corrections require a credit-note"
            + " workflow, not yet implemented.");
    s.require(
        !r.bookedOn().isBefore(((java.sql.Date) j.get("booked_on")).toLocalDate()),
        "REVERSAL_DATE",
        "Reversal cannot precede the original booking date.");
    var oldPeriod = s.one("select * from fin_period where id=?", j.get("period_id"));
    var p = s.period(r.periodId(), r.bookedOn());
    s.require(
        p.get("currency").equals(oldPeriod.get("currency")),
        "CURRENCY_MISMATCH",
        "Reversal must use the original currency.");
    var lines =
        s
            .db
            .queryForList("select * from fin_entry where journal_id=? order by sequence_no", id)
            .stream()
            .map(
                l ->
                    new Line(
                        (UUID) l.get("account_id"), s.amount(l, "credit"), s.amount(l, "debit")))
            .toList();
    return detail(
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "REVERSAL",
            id,
            "Reversal of " + j.get("reference"),
            lines));
  }

  @Transactional(readOnly = true)
  Map<String, Object> detail(UUID id) {
    return Map.of(
        "journal",
        s.one(
            "select j.*,p.currency from fin_journal j join fin_period p on p.id=j.period_id where"
                + " j.id=?",
            id),
        "lines",
        s.db.queryForList(
            "select e.*,a.code,a.name from fin_entry e join fin_account a on a.id=e.account_id"
                + " where journal_id=? order by sequence_no",
            id));
  }

  @Transactional(readOnly = true)
  List<Map<String, Object>> trial(UUID period) {
    var p = s.one("select * from fin_period where id=?", period);
    return s.db.queryForList(
        "select a.id,a.code,a.name,a.kind,sum(e.debit) debit,sum(e.credit)"
            + " credit,sum(e.debit-e.credit) balance from fin_entry e join fin_journal j on"
            + " j.id=e.journal_id join fin_period p on p.id=j.period_id join fin_account a on"
            + " a.id=e.account_id where p.currency=? and j.booked_on<? group by"
            + " a.id,a.code,a.name,a.kind order by a.code",
        p.get("currency"),
        p.get("ends_on"));
  }

  Map<String, Object> invoice(Invoice r) {
    s.lock();
    s.currency(r.currency());
    s.account(r.controlAccount(), r.kind() == InvoiceKind.SALE ? "ASSET" : "LIABILITY");
    s.account(
        r.netAccount(),
        r.kind() == InvoiceKind.SALE ? "REVENUE" : "EXPENSE",
        r.kind() == InvoiceKind.SALE ? "REVENUE" : "ASSET");
    if (r.tax().signum() > 0) {
      s.require(r.taxAccount() != null, "TAX_ACCOUNT", "Tax needs an explicit account.");
      s.account(r.taxAccount(), r.kind() == InvoiceKind.SALE ? "LIABILITY" : "ASSET");
    }
    UUID id = s.id();
    s.db.update(
        "insert into fin_invoice values(?,?,?,?,?,?,?,?,?,?,?,?,?,null,'DRAFT')",
        id,
        r.code(),
        r.kind().name(),
        r.partyReference(),
        r.documentReference(),
        r.currency(),
        r.net(),
        r.tax(),
        r.net().add(r.tax()),
        r.dueOn(),
        r.controlAccount(),
        r.netAccount(),
        r.taxAccount());
    s.audit(id, "INVOICE_CREATED", r);
    return Map.of("id", id);
  }

  Map<String, Object> postInvoice(UUID id, Posting r) {
    s.lock();
    var i = s.one("select * from fin_invoice where id=? for update", id);
    s.require("DRAFT".equals(i.get("state")), "ALREADY_POSTED", "Invoice already posted.");
    sameCurrency(i, r.periodId(), r.bookedOn());
    boolean sale = "SALE".equals(i.get("kind"));
    var lines = new ArrayList<Line>();
    lines.add(line((UUID) i.get("control_account"), s.amount(i, "total"), sale));
    lines.add(line((UUID) i.get("net_account"), s.amount(i, "net"), !sale));
    if (s.amount(i, "tax").signum() > 0)
      lines.add(line((UUID) i.get("tax_account"), s.amount(i, "tax"), !sale));
    UUID j =
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "INVOICE",
            id,
            "Invoice " + i.get("code"),
            lines);
    s.db.update("update fin_invoice set state='POSTED',journal_id=? where id=?", j, id);
    return detail(j);
  }

  Map<String, Object> payment(UUID invoice, Payment r) {
    s.lock();
    var i = s.one("select * from fin_invoice where id=? for update", invoice);
    s.require("POSTED".equals(i.get("state")), "INVOICE_NOT_POSTED", "Post the invoice first.");
    var invoiceJournal = s.one("select booked_on from fin_journal where id=?", i.get("journal_id"));
    s.require(
        !r.bookedOn().isBefore(((java.sql.Date) invoiceJournal.get("booked_on")).toLocalDate()),
        "PAYMENT_DATE",
        "Settlement cannot precede invoice posting; advances need a separate workflow.");
    sameCurrency(i, r.periodId(), r.bookedOn());
    s.account(r.bankAccount(), "ASSET");
    s.require(
        !r.bankAccount().equals(i.get("control_account")),
        "CONTROL_BANK",
        "Bank and control accounts must differ.");
    BigDecimal outstanding =
        s.amount(i, "total")
            .subtract(s.sum("select sum(amount) from fin_payment where invoice_id=?", invoice));
    s.require(
        r.amount().compareTo(outstanding) <= 0,
        "OVERPAYMENT",
        "Payment exceeds outstanding invoice amount.");
    boolean sale = "SALE".equals(i.get("kind"));
    UUID j =
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "PAYMENT",
            r.eventId(),
            "Settlement " + i.get("code"),
            List.of(
                line(r.bankAccount(), r.amount(), sale),
                line((UUID) i.get("control_account"), r.amount(), !sale)));
    s.db.update(
        "insert into fin_payment values(?,?,?,?,?)",
        r.eventId(),
        invoice,
        r.amount(),
        r.externalReference(),
        j);
    return detail(j);
  }

  void sameCurrency(Map<String, Object> source, UUID period, LocalDate date) {
    s.require(
        source.get("currency").equals(s.period(period, date).get("currency")),
        "CURRENCY_MISMATCH",
        "Source and accounting period currencies must match.");
  }

  static Line line(UUID account, BigDecimal value, boolean debit) {
    return new Line(account, debit ? value : BigDecimal.ZERO, debit ? BigDecimal.ZERO : value);
  }

  Map<String, Object> budget(Budget r) {
    s.lock();
    s.one("select * from fin_period where id=? and state='OPEN'", r.periodId());
    UUID id = s.id();
    s.db.update("insert into fin_budget values(?,?,?,?)", id, r.code(), r.periodId(), r.amount());
    s.audit(id, "BUDGET_CREATED", r);
    return Map.of("id", id);
  }

  Map<String, Object> expense(Expense r) {
    s.lock();
    s.one(
        "select p.* from fin_budget b join fin_period p on p.id=b.period_id where b.id=? and"
            + " p.state='OPEN'",
        r.budgetId());
    if (r.sourceWorkOrder() != null) {
      s.require(
          s.db
              .queryForList(
                  "select id from fin_expense where source_work_order=? and state<>'CANCELLED'",
                  r.sourceWorkOrder())
              .isEmpty(),
          "SOURCE_ALREADY_CLAIMED",
          "Work order already linked to an active expense.");
      s.one("select * from ops_work_order where id=? and state='CLOSED'", r.sourceWorkOrder());
    }
    UUID id = s.id();
    s.db.update(
        "insert into fin_expense values(?,?,?,?,?,?,'DRAFT',?,null,null)",
        id,
        r.code(),
        r.budgetId(),
        r.amount(),
        r.description(),
        r.sourceWorkOrder(),
        s.actor());
    s.audit(id, "EXPENSE_CREATED", r);
    return Map.of("id", id);
  }

  void approveExpense(UUID id) {
    s.lock();
    var e = s.one("select * from fin_expense where id=? for update", id);
    s.require("DRAFT".equals(e.get("state")), "EXPENSE_STATE", "Expense already decided.");
    s.require(
        !s.actor().equals(e.get("created_by")),
        "SEPARATE_APPROVER",
        "A different person must approve the expense.");
    var b =
        s.one(
            "select b.*,p.currency,p.state from fin_budget b join fin_period p on p.id=b.period_id"
                + " where b.id=?",
            e.get("budget_id"));
    s.require("OPEN".equals(b.get("state")), "PERIOD_CLOSED", "Budget period is closed.");
    BigDecimal committed =
        s.sum(
            "select sum(amount) from fin_expense where budget_id=? and state in"
                + " ('APPROVED','POSTED')",
            b.get("id"));
    s.require(
        committed.add(s.amount(e, "amount")).compareTo(s.amount(b, "amount")) <= 0,
        "BUDGET_EXCEEDED",
        "Approved expenses exceed the budget.");
    if (e.get("source_work_order") != null) {
      var w = s.one("select * from ops_work_order where id=?", e.get("source_work_order"));
      s.require(
          w.get("currency").equals(b.get("currency")),
          "CURRENCY_MISMATCH",
          "Work-order evidence and budget currencies differ.");
    }
    s.db.update("update fin_expense set state='APPROVED',approved_by=? where id=?", s.actor(), id);
    s.audit(id, "EXPENSE_APPROVED", Map.of());
  }

  void cancelExpense(UUID id) {
    s.lock();
    var e = s.one("select * from fin_expense where id=? for update", id);
    s.require(
        !"POSTED".equals(e.get("state")) && !"CANCELLED".equals(e.get("state")),
        "EXPENSE_STATE",
        "Only an unposted expense may be cancelled.");
    s.db.update("update fin_expense set state='CANCELLED' where id=?", id);
    s.audit(id, "EXPENSE_CANCELLED", Map.of());
  }

  Map<String, Object> postExpense(UUID id, ExpensePosting r) {
    s.lock();
    var e = s.one("select * from fin_expense where id=? for update", id);
    s.require(
        "APPROVED".equals(e.get("state")), "EXPENSE_STATE", "Approve the expense before posting.");
    var b = s.one("select * from fin_budget where id=?", e.get("budget_id"));
    s.require(
        r.periodId().equals(b.get("period_id")),
        "BUDGET_PERIOD",
        "Expense posting must use its budget period.");
    s.account(r.expenseAccount(), "EXPENSE");
    s.account(r.payableAccount(), "LIABILITY");
    UUID j =
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "EXPENSE",
            id,
            e.get("description").toString(),
            List.of(
                line(r.expenseAccount(), s.amount(e, "amount"), true),
                line(r.payableAccount(), s.amount(e, "amount"), false)));
    s.db.update("update fin_expense set state='POSTED',journal_id=? where id=?", j, id);
    return detail(j);
  }

  Map<String, Object> payroll(UUID source, PayrollPosting r) {
    s.lock();
    var p = s.one("select * from wf_period where id=? and state='FINALIZED'", source);
    s.require(
        ((Number) p.get("money_scale")).intValue() == 2,
        "MONEY_SCALE",
        "Payroll posting requires a two-decimal finalized period.");
    sameCurrency(p, r.periodId(), r.bookedOn());
    s.account(r.wagesAccount(), "EXPENSE");
    s.account(r.payableAccount(), "LIABILITY");
    s.account(r.deductionsAccount(), "LIABILITY");
    BigDecimal gross = s.sum("select sum(gross) from wf_pay_line where period_id=?", source),
        payable = s.sum("select sum(payable) from wf_pay_line where period_id=?", source),
        deduction = s.sum("select sum(deduction) from wf_pay_line where period_id=?", source);
    var lines = new ArrayList<Line>();
    if (gross.signum() > 0)
      lines.add(line(r.wagesAccount(), gross.setScale(2, RoundingMode.UNNECESSARY), true));
    if (payable.signum() > 0)
      lines.add(line(r.payableAccount(), payable.setScale(2, RoundingMode.UNNECESSARY), false));
    if (deduction.signum() > 0)
      lines.add(
          line(r.deductionsAccount(), deduction.setScale(2, RoundingMode.UNNECESSARY), false));
    return detail(
        post(
            r.periodId(),
            r.bookedOn(),
            r.reference(),
            "PAYROLL",
            source,
            "Finalized payroll " + p.get("code"),
            lines));
  }
}
