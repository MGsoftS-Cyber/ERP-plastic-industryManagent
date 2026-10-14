export type Field = {
  key: string;
  label: string;
  source?: string;
  options?: string[];
  type?: string;
  optional?: boolean;
};
export type Action = {
  title: string;
  role: string;
  path: string;
  fields: Field[];
  kind?: string;
};
const f = (key: string, label: string, source?: string): Field => ({
  key,
  label,
  source,
});
const n = (key: string, label: string): Field => ({
  key,
  label,
  type: "number",
});
const d = (key: string, label: string): Field => ({ key, label, type: "date" });
const o = (key: string, label: string, options: string[]): Field => ({
  key,
  label,
  options,
});
const posting = [
  f("periodId", "Accounting period", "period"),
  d("bookedOn", "Booking date"),
  f("reference", "Unique journal reference"),
];
const account = (key: string, label: string) => f(key, label, "account");
export const actions: Record<string, Action[]> = {
  Accounting: [
    {
      title: "Create account",
      role: "ACCOUNTANT",
      path: "/accounts",
      fields: [
        f("code", "Code"),
        f("name", "Name"),
        o("kind", "Account type", [
          "ASSET",
          "LIABILITY",
          "EQUITY",
          "REVENUE",
          "EXPENSE",
        ]),
      ],
    },
    {
      title: "Open accounting period",
      role: "ACCOUNTANT",
      path: "/periods",
      fields: [
        f("code", "Code"),
        d("startsOn", "Start date"),
        d("endsOn", "End date (exclusive)"),
        f("currency", "Currency (two decimal minor units, e.g. USD)"),
      ],
    },
    {
      title: "Post balanced journal",
      role: "ACCOUNTANT",
      path: "/journals",
      fields: [...posting, f("description", "Description")],
      kind: "journal",
    },
    {
      title: "Reverse manual journal",
      role: "ACCOUNTANT",
      path: "/journals/{id}/reverse",
      fields: [f("id", "Original journal", "journal"), ...posting],
    },
    {
      title: "Post finalized payroll",
      role: "ACCOUNTANT",
      path: "/payroll/{id}/post",
      fields: [
        f("id", "Finalized payroll period", "payPeriods"),
        ...posting,
        account("wagesAccount", "Wages expense account"),
        account("payableAccount", "Payroll liability account"),
        account("deductionsAccount", "Deductions liability account"),
      ],
    },
    {
      title: "Close accounting period",
      role: "ACCOUNTANT",
      path: "/periods/{id}/close",
      fields: [f("id", "Period", "period")],
    },
  ],
  Invoices: [
    {
      title: "Create invoice",
      role: "FINANCE_MANAGER or ACCOUNTANT",
      path: "/invoices",
      fields: [
        f("code", "Unique invoice code"),
        o("kind", "Invoice type", ["SALE", "PURCHASE"]),
        f("partyReference", "Customer or supplier reference"),
        f("documentReference", "Source document reference"),
        f("currency", "Currency"),
        n("net", "Net amount"),
        n("tax", "Explicit tax amount"),
        d("dueOn", "Due date"),
        account("controlAccount", "Receivable / payable account"),
        account("netAccount", "Revenue / expense / inventory account"),
        {
          ...account(
            "taxAccount",
            "Tax account (required when tax is positive)",
          ),
          optional: true,
        },
      ],
    },
    {
      title: "Post invoice",
      role: "ACCOUNTANT",
      path: "/invoices/{id}/post",
      fields: [f("id", "Invoice", "invoice"), ...posting],
    },
    {
      title: "Record invoice settlement",
      role: "ACCOUNTANT",
      path: "/invoices/{id}/payments",
      fields: [
        f("id", "Invoice", "invoice"),
        ...posting,
        account("bankAccount", "Bank / cash account"),
        n("amount", "Settlement amount"),
        f("externalReference", "Unique bank statement evidence reference"),
      ],
      kind: "event",
    },
  ],
  Expenses: [
    {
      title: "Set budget",
      role: "FINANCE_MANAGER",
      path: "/budgets",
      fields: [
        f("code", "Budget / cost center code"),
        f("periodId", "Accounting period", "period"),
        n("amount", "Budget amount"),
      ],
    },
    {
      title: "Submit expense",
      role: "FINANCE_MANAGER",
      path: "/expenses",
      fields: [
        f("code", "Unique expense code"),
        f("budgetId", "Budget", "budget"),
        n("amount", "Evidence-backed amount"),
        f("description", "Description and evidence reference"),
        {
          ...f(
            "sourceWorkOrder",
            "Closed maintenance / facility order (optional)",
            "workOrders",
          ),
          optional: true,
        },
      ],
    },
    {
      title: "Approve expense",
      role: "FINANCE_APPROVER",
      path: "/expenses/{id}/approve",
      fields: [f("id", "Expense", "expense")],
    },
    {
      title: "Cancel unposted expense",
      role: "FINANCE_MANAGER or FINANCE_APPROVER",
      path: "/expenses/{id}/cancel",
      fields: [f("id", "Expense", "expense")],
    },
    {
      title: "Post approved expense",
      role: "ACCOUNTANT",
      path: "/expenses/{id}/post",
      fields: [
        f("id", "Expense", "expense"),
        ...posting,
        account("expenseAccount", "Expense account"),
        account("payableAccount", "Liability account"),
      ],
    },
  ],
  Costing: [
    {
      title: "Record immutable lot valuation",
      role: "ACCOUNTANT",
      path: "/lot-values",
      fields: [
        f("lotId", "Source lot", "lots"),
        f("currency", "Currency"),
        n("unitCost", "Cost per kg (up to six decimals)"),
        f("evidence", "Valuation evidence and method"),
      ],
    },
    {
      title: "Capture production cost",
      role: "ACCOUNTANT",
      path: "/costs",
      fields: [
        f("orderId", "Completed order with approved batch", "orders"),
        f("currency", "Currency"),
      ],
      kind: "cost",
    },
  ],
  Recycling: [
    {
      title: "Transfer stock to scrap",
      role: "PRODUCTION_MANAGER",
      path: "/scrap",
      fields: [
        f("code", "Unique scrap code"),
        f("lotId", "Source material lot", "lots"),
        n("kg", "Kilograms to remove from stock"),
        f("classification", "Contamination / scrap classification"),
        f("reason", "Reason and assessment evidence"),
      ],
    },
    {
      title: "Record recovery",
      role: "PRODUCTION_MANAGER",
      path: "/scrap/{id}/recover",
      fields: [
        f("id", "Scrap stock", "scrap"),
        f("lotCode", "Unique recovered lot code"),
        f("location", "Quarantine location"),
        n("inputKg", "Scrap consumed (kg)"),
        n("recoveredKg", "Recovered material (kg)"),
        f("dispositionReference", "Evidence for recovery and loss disposal"),
      ],
      kind: "event",
    },
  ],
};
export const tables: Record<string, { source: string; columns: string[] }[]> = {
  Accounting: [
    { source: "account", columns: ["id", "code", "name", "kind"] },
    {
      source: "period",
      columns: ["id", "code", "starts_on", "ends_on", "currency", "state"],
    },
    {
      source: "journal",
      columns: ["id", "reference", "booked_on", "source_type", "actor"],
    },
  ],
  Invoices: [
    {
      source: "invoice",
      columns: [
        "id",
        "code",
        "kind",
        "party_reference",
        "currency",
        "net",
        "tax",
        "total",
        "outstanding",
        "due_on",
        "state",
      ],
    },
    {
      source: "payment",
      columns: ["invoice_id", "amount", "external_reference", "journal_id"],
    },
  ],
  Expenses: [
    { source: "budget", columns: ["id", "code", "period_id", "amount"] },
    {
      source: "expense",
      columns: [
        "id",
        "code",
        "budget_id",
        "amount",
        "source_work_order",
        "state",
        "created_by",
        "approved_by",
      ],
    },
  ],
  Costing: [
    {
      source: "lot_value",
      columns: ["lot_id", "currency", "unit_cost", "evidence"],
    },
    {
      source: "cost",
      columns: [
        "id",
        "order_id",
        "currency",
        "material_cost",
        "allocated_cost",
        "total",
        "unit_cost",
        "output_qty",
      ],
    },
  ],
  Recycling: [
    {
      source: "scrap",
      columns: ["id", "code", "lot_id", "kg", "remaining_kg", "classification"],
    },
    {
      source: "recovery",
      columns: [
        "id",
        "scrap_id",
        "input_kg",
        "recovered_kg",
        "loss_kg",
        "output_lot_id",
        "disposition_reference",
      ],
    },
  ],
};
