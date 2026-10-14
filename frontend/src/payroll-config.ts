export const payrollActions = {
  attendance: [
    {
      title: "Record original clock event",
      path: "/clock-events",
      fields: [
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "kind",
          label: "Event type",
          options: ["IN", "OUT", "BREAK_START", "BREAK_END"],
        },
        {
          key: "occurredAt",
          label: "Recorded time",
          type: "datetime-local",
        },
        {
          key: "source",
          label: "Source / terminal reference",
        },
      ],
      kind: "event",
    },
    {
      title: "Draft attendance",
      path: "/attendance",
      fields: [
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "start",
          label: "Starts",
          type: "datetime-local",
        },
        {
          key: "end",
          label: "Ends",
          type: "datetime-local",
        },
        {
          key: "notes",
          label: "Attendance notes",
        },
      ],
      kind: "attendance",
    },
    {
      title: "Approve attendance and overtime",
      path: "/attendance/{id}/approve",
      fields: [
        {
          key: "id",
          label: "Draft attendance",
          source: "attendance",
        },
        {
          key: "overtimeMinutes",
          label: "Approved overtime: last paid minutes",
          type: "number",
        },
        {
          key: "notes",
          label: "Approval / correction notes",
        },
      ],
      kind: null,
    },
    {
      title: "Correct attendance with a new revision",
      path: "/attendance/{id}/corrections",
      fields: [
        {
          key: "id",
          label: "Attendance to replace",
          source: "attendance",
        },
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "start",
          label: "Starts",
          type: "datetime-local",
        },
        {
          key: "end",
          label: "Ends",
          type: "datetime-local",
        },
        {
          key: "notes",
          label: "Correction reason",
        },
      ],
      kind: "attendance",
    },
    {
      title: "Request absence / leave",
      path: "/absences",
      fields: [
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "start",
          label: "Starts",
          type: "datetime-local",
        },
        {
          key: "end",
          label: "Ends",
          type: "datetime-local",
        },
        {
          key: "kind",
          label: "Absence type",
          options: ["PAID_LEAVE", "UNPAID_LEAVE", "ABSENCE"],
        },
        {
          key: "reason",
          label: "Reason",
        },
      ],
      kind: null,
    },
    {
      title: "Approve absence / leave",
      path: "/absences/{id}/approve",
      fields: [
        {
          key: "id",
          label: "Absence",
          source: "absences",
        },
        {
          key: "notes",
          label: "Approval / correction notes",
        },
      ],
      kind: null,
    },
    {
      title: "Void incorrect attendance",
      path: "/attendance/{id}/void",
      fields: [
        {
          key: "id",
          label: "Attendance",
          source: "attendance",
        },
        {
          key: "notes",
          label: "Reason",
        },
      ],
      kind: null,
    },
    {
      title: "Cancel absence / leave",
      path: "/absences/{id}/cancel",
      fields: [
        {
          key: "id",
          label: "Absence",
          source: "absences",
        },
        {
          key: "notes",
          label: "Reason",
        },
      ],
      kind: null,
    },
  ],
  pay: [
    {
      title: "Create wage version",
      path: "/wages",
      fields: [
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "effectiveFrom",
          label: "Effective from",
          type: "date",
        },
        {
          key: "basis",
          label: "Pay basis",
          options: ["HOURLY", "MONTHLY"],
        },
        {
          key: "amount",
          label: "Hourly rate / monthly salary",
          type: "number",
        },
        {
          key: "currency",
          label: "Currency (e.g. DZD)",
        },
        {
          key: "monthlyMinutes",
          label: "Contractual monthly minutes (0 for hourly)",
          type: "number",
        },
      ],
      kind: null,
    },
    {
      title: "Create premium rule version",
      path: "/rules",
      fields: [
        {
          key: "effectiveFrom",
          label: "Effective from",
          type: "date",
        },
        {
          key: "nightStart",
          label: "Night starts (local time)",
          type: "time",
        },
        {
          key: "nightEnd",
          label: "Night ends (local time)",
          type: "time",
        },
        {
          key: "nightType",
          label: "Premium type",
          options: ["PERCENT", "FIXED"],
        },
        {
          key: "nightValue",
          label: "Percent premium / extra amount per hour",
          type: "number",
        },
        {
          key: "overtimePercent",
          label: "Overtime extra percent",
          type: "number",
        },
        {
          key: "stacking",
          label: "Night + overtime combination",
          options: ["ADDITIVE", "MULTIPLICATIVE"],
        },
      ],
      kind: null,
    },
    {
      title: "Create pay period",
      path: "/periods",
      fields: [
        {
          key: "code",
          label: "Period code",
        },
        {
          key: "start",
          label: "First included date",
          type: "date",
        },
        {
          key: "end",
          label: "First excluded date",
          type: "date",
        },
        {
          key: "zoneId",
          label: "IANA timezone (Africa/Algiers, UTC\u2026)",
        },
        {
          key: "currency",
          label: "Currency",
        },
        {
          key: "moneyScale",
          label: "Decimal places for money",
          type: "number",
        },
        {
          key: "employeeIds",
          label: "Employees",
          source: "employees",
          type: "multiple",
        },
      ],
      kind: null,
    },
    {
      title: "Request bonus / deduction",
      path: "/periods/{id}/adjustments",
      fields: [
        {
          key: "id",
          label: "Pay period",
          source: "periods",
        },
        {
          key: "employeeId",
          label: "Employee",
          source: "employees",
        },
        {
          key: "kind",
          label: "Adjustment type",
          options: ["BONUS", "DEDUCTION"],
        },
        {
          key: "amount",
          label: "Amount in period currency",
          type: "number",
        },
        {
          key: "reason",
          label: "Reason / correction reference",
        },
      ],
      kind: null,
    },
    {
      title: "Approve or reject adjustment",
      path: "/adjustments/{id}/decision",
      fields: [
        {
          key: "id",
          label: "Adjustment",
          source: "adjustments",
        },
        {
          key: "approved",
          label: "Approve?",
          type: "boolean",
        },
        {
          key: "notes",
          label: "Approval / correction notes",
        },
      ],
      kind: null,
    },
    {
      title: "Calculate approved payroll",
      path: "/periods/{id}/calculate",
      fields: [
        {
          key: "id",
          label: "Pay period",
          source: "periods",
        },
      ],
      kind: null,
    },
    {
      title: "Review payroll (separate approver)",
      path: "/periods/{id}/review",
      fields: [
        {
          key: "id",
          label: "Pay period",
          source: "periods",
        },
        {
          key: "notes",
          label: "Approval / correction notes",
        },
      ],
      kind: null,
    },
    {
      title: "Finalize reviewed payroll",
      path: "/periods/{id}/finalize",
      fields: [
        {
          key: "id",
          label: "Pay period",
          source: "periods",
        },
        {
          key: "notes",
          label: "Approval / correction notes",
        },
      ],
      kind: null,
    },
  ],
};
