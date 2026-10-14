package com.plastiflow.finance;

import static com.plastiflow.finance.Requests.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
class FinanceController {
  final FinanceStore s;
  final AccountingService a;
  final CostRecyclingService c;

  FinanceController(FinanceStore s, AccountingService a, CostRecyclingService c) {
    this.s = s;
    this.a = a;
    this.c = c;
  }

  @GetMapping("/overview")
  @PreAuthorize("hasAnyRole('ACCOUNTANT','FINANCE_MANAGER','FINANCE_APPROVER')")
  Map<String, Object> overview() {
    var out = new LinkedHashMap<String, Object>();
    for (String table :
        List.of("account", "period", "journal", "budget", "expense", "lot_value", "cost"))
      out.put(table, s.db.queryForList("select * from fin_" + table + " limit 200"));
    out.put(
        "invoice",
        s.db.queryForList(
            "select i.*, i.total-coalesce((select sum(p.amount) from fin_payment p where"
                + " p.invoice_id=i.id),0) outstanding from fin_invoice i limit 200"));
    out.put("payment", s.db.queryForList("select * from fin_payment limit 200"));
    out.put("orders", s.db.queryForList("select id,code,state from mfg_order limit 200"));
    out.put(
        "lots", s.db.queryForList("select id,code,remaining_kg,quality from mfg_lot limit 200"));
    out.put(
        "workOrders",
        s.db.queryForList("select id,code,state,currency from ops_work_order limit 200"));
    out.put(
        "payPeriods",
        s.db.queryForList(
            "select id,code,state,currency from wf_period where state='FINALIZED' limit 200"));
    return out;
  }

  @GetMapping("/payroll/{id}/lines")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  List<Map<String, Object>> payLines(@PathVariable UUID id) {
    s.one("select * from wf_period where id=? and state='FINALIZED'", id);
    return s.db.queryForList(
        "select id,employee_id,gross,payable,deduction from wf_pay_line where period_id=?", id);
  }

  @GetMapping("/recycling-overview")
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','QUALITY_MANAGER','ACCOUNTANT','FINANCE_MANAGER')")
  Map<String, Object> recycling() {
    return Map.of(
        "scrap",
        s.db.queryForList("select * from fin_scrap limit 200"),
        "recovery",
        s.db.queryForList("select * from fin_recovery limit 200"),
        "lots",
        s.db.queryForList("select id,code,remaining_kg,quality from mfg_lot limit 200"));
  }

  @GetMapping("/recovery-trace/{id}")
  @PreAuthorize("hasAnyRole('PRODUCTION_MANAGER','QUALITY_MANAGER','ACCOUNTANT','FINANCE_MANAGER')")
  Map<String, Object> trace(@PathVariable UUID id) {
    return c.recoveryTrace(id);
  }

  @PostMapping("/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> account(@Valid @RequestBody Account r) {
    return a.account(r);
  }

  @PostMapping("/periods")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> period(@Valid @RequestBody Period r) {
    return a.period(r);
  }

  @PostMapping("/periods/{id}/close")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  void close(@PathVariable UUID id) {
    a.close(id);
  }

  @PostMapping("/journals")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> journal(@Valid @RequestBody Journal r) {
    return a.journal(r);
  }

  @GetMapping("/journals/{id}")
  @PreAuthorize("hasAnyRole('ACCOUNTANT','FINANCE_MANAGER','FINANCE_APPROVER')")
  Map<String, Object> journal(@PathVariable UUID id) {
    return a.detail(id);
  }

  @PostMapping("/journals/{id}/reverse")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> reverse(@PathVariable UUID id, @Valid @RequestBody Posting r) {
    return a.reverse(id, r);
  }

  @GetMapping("/periods/{id}/trial-balance")
  @PreAuthorize("hasAnyRole('ACCOUNTANT','FINANCE_MANAGER','FINANCE_APPROVER')")
  List<Map<String, Object>> trial(@PathVariable UUID id) {
    return a.trial(id);
  }

  @PostMapping("/invoices")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('FINANCE_MANAGER','ACCOUNTANT')")
  Map<String, Object> invoice(@Valid @RequestBody Invoice r) {
    return a.invoice(r);
  }

  @PostMapping("/invoices/{id}/post")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> postInvoice(@PathVariable UUID id, @Valid @RequestBody Posting r) {
    return a.postInvoice(id, r);
  }

  @PostMapping("/invoices/{id}/payments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> payment(@PathVariable UUID id, @Valid @RequestBody Payment r) {
    return a.payment(id, r);
  }

  @PostMapping("/budgets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FINANCE_MANAGER')")
  Map<String, Object> budget(@Valid @RequestBody Budget r) {
    return a.budget(r);
  }

  @PostMapping("/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('FINANCE_MANAGER')")
  Map<String, Object> expense(@Valid @RequestBody Expense r) {
    return a.expense(r);
  }

  @PostMapping("/expenses/{id}/approve")
  @PreAuthorize("hasRole('FINANCE_APPROVER')")
  void approve(@PathVariable UUID id) {
    a.approveExpense(id);
  }

  @PostMapping("/expenses/{id}/cancel")
  @PreAuthorize("hasAnyRole('FINANCE_MANAGER','FINANCE_APPROVER')")
  void cancel(@PathVariable UUID id) {
    a.cancelExpense(id);
  }

  @PostMapping("/expenses/{id}/post")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> expensePost(@PathVariable UUID id, @Valid @RequestBody ExpensePosting r) {
    return a.postExpense(id, r);
  }

  @PostMapping("/payroll/{id}/post")
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> payroll(@PathVariable UUID id, @Valid @RequestBody PayrollPosting r) {
    return a.payroll(id, r);
  }

  @PostMapping("/lot-values")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> value(@Valid @RequestBody Valuation r) {
    return c.valuation(r);
  }

  @PostMapping("/costs")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ACCOUNTANT')")
  Map<String, Object> cost(@Valid @RequestBody Costing r) {
    return c.cost(r);
  }

  @PostMapping("/scrap")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> scrap(@Valid @RequestBody Scrap r) {
    return c.scrap(r);
  }

  @PostMapping("/scrap/{id}/recover")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PRODUCTION_MANAGER')")
  Map<String, Object> recover(@PathVariable UUID id, @Valid @RequestBody Recovery r) {
    return c.recover(id, r);
  }
}
