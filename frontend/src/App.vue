<script setup lang="ts">
import { computed, ref } from "vue";
import Table from "./Table.vue";
import CatalogWorkspace from "./CatalogWorkspace.vue";
import OperationsWorkspace from "./OperationsWorkspace.vue";
import PayrollWorkspace from "./PayrollWorkspace.vue";
import FinanceWorkspace from "./FinanceWorkspace.vue";
import IntegrationWorkspace from "./IntegrationWorkspace.vue";
type Row = Record<string, any>;
type Field = {
  key: string;
  label: string;
  source?: string;
  options?: string[];
  type?: string;
  optional?: boolean;
};
type Action = {
  title: string;
  role: string;
  method: string;
  path: string;
  fields: Field[];
  kind?: string;
};
const base = "/api/v1/manufacturing";
const payView = ref("pay");
const workspace = ref("manufacturing"),
  opsSection = ref("maintenance");
function accountChanged() {
  if (["manager", "reader"].includes(username.value)) {
    workspace.value = "catalog";
    return;
  }
  if (username.value === "integration") {
    workspace.value = "integration";
    return;
  }
  if (username.value === "reports") {
    workspace.value = "reports";
    return;
  }
  if (["customer", "customer2", "supplier"].includes(username.value)) {
    workspace.value = "portal";
    return;
  }
  if (["accountant", "finance", "financeapprover"].includes(username.value)) {
    workspace.value = "finance";
    return;
  }
  if (["workforce", "payroll", "payrollapprover"].includes(username.value)) {
    workspace.value = "payroll";
    payView.value = username.value === "workforce" ? "attendance" : "pay";
    return;
  }
  const departments: Record<string, string> = {
    maintenance: "maintenance",
    technician: "maintenance",
    facilities: "facilities",
    logistics: "yard",
    security: "security",
    gate: "yard",
  };
  workspace.value = departments[username.value]
    ? "operations"
    : "manufacturing";
  opsSection.value = departments[username.value] ?? "maintenance";
}
const session = ref<{ subject: string; roles: string[] }>({
  subject: "",
  roles: [],
});
const workspaceRoles: Record<string, string[]> = {
  catalog: ["CATALOG_MANAGER", "CATALOG_READER"],
  manufacturing: [
    "PRODUCTION_MANAGER",
    "OPERATOR",
    "QUALITY_MANAGER",
    "WORKFORCE_MANAGER",
  ],
  operations: [
    "MAINTENANCE_MANAGER",
    "MAINTENANCE_TECHNICIAN",
    "FACILITIES_MANAGER",
    "LOGISTICS_MANAGER",
    "SECURITY_MANAGER",
    "GATE_OFFICER",
  ],
  payroll: ["WORKFORCE_MANAGER", "PAYROLL_MANAGER", "PAYROLL_APPROVER"],
  finance: ["ACCOUNTANT", "FINANCE_MANAGER", "FINANCE_APPROVER"],
  recycling: ["PRODUCTION_MANAGER", "QUALITY_MANAGER"],
  integration: ["INTEGRATION_MANAGER"],
  reports: ["REPORT_VIEWER", "AI_ANALYST"],
  portal: ["PORTAL_USER"],
};
const workspaceNames: Record<string, string> = {
  catalog: "Catalog",
  manufacturing: "Manufacturing",
  operations: "Factory operations",
  payroll: "Attendance / payroll",
  finance: "Finance / costing",
  recycling: "Recycling",
  integration: "Integration management",
  reports: "Reports / AI",
  portal: "Partner portal",
};
const availableWorkspaces = computed(() =>
  Object.keys(workspaceRoles).filter((w) =>
    workspaceRoles[w].some((r) => session.value.roles.includes(r)),
  ),
);
async function switchWorkspace() {
  prepareWorkspace();
  try {
    await refresh();
  } catch (e) {
    error.value = String(e);
  }
}
function prepareWorkspace() {
  if (workspace.value === "payroll")
    payView.value = session.value.roles.some((r) =>
      ["PAYROLL_MANAGER", "PAYROLL_APPROVER"].includes(r),
    )
      ? "pay"
      : "attendance";
  if (workspace.value === "operations") {
    const scopes: Record<string, string[]> = {
      maintenance: ["MAINTENANCE_MANAGER", "MAINTENANCE_TECHNICIAN"],
      facilities: ["FACILITIES_MANAGER"],
      yard: ["LOGISTICS_MANAGER", "GATE_OFFICER"],
      security: ["SECURITY_MANAGER"],
    };
    if (!scopes[opsSection.value]?.some((r) => session.value.roles.includes(r)))
      opsSection.value =
        Object.keys(scopes).find((w) =>
          scopes[w].some((r) => session.value.roles.includes(r)),
        ) ?? "maintenance";
  }
}
const mode = ref("demo"),
  username = ref("production"),
  password = ref(""),
  token = ref(""),
  connected = ref(false);
let authorization = "";
const busy = ref(false),
  message = ref(""),
  error = ref(""),
  tab = ref("Equipment");
const data = ref<Record<string, Row[]>>({}),
  products = ref<Row[]>([]),
  form = ref<Record<string, string>>({});
const selected = ref(0),
  eventId = ref(crypto.randomUUID()),
  orderDetail = ref<Row | null>(null),
  recipeDetail = ref<Row | null>(null),
  trace = ref<Row | null>(null);
const components = ref([{ materialId: "", kgPerUnit: "0.01" }]);
const operations = ref([{ name: "Molding", minutesPerUnit: "0.01" }]);
const metrics = ref([
  { name: "weight", unit: "g", minimum: "9", maximum: "11" },
]);
const measurements = ref<Record<string, string>>({}),
  settings = ref([{ key: "", value: "" }]);
const calendarDay = ref(new Date().toLocaleDateString("en-CA"));
function bars(machineId: string): Row[] {
  const dayStart = new Date(calendarDay.value + "T00:00:00").getTime();
  const dayEnd = new Date(calendarDay.value + "T23:59:59.999").getTime();
  const entries: Row[] = [
    ...rows("schedule")
      .filter((s) => s.machine_id === machineId && ["SCHEDULED","RUNNING"].includes(rows("orders").find(o=>o.id===s.order_id)?.state))
      .map((s) => ({
        ...s,
        label: rows("orders").find((o) => o.id === s.order_id)?.code ?? "Job",
        kind: "job",
      })),
    ...rows("maintenance")
      .filter((s) => s.machine_id === machineId && !s.cancelled)
      .map((s) => ({ ...s, label: "Maintenance", kind: "maintenance" })),
  ];
  return entries
    .filter(
      (s) =>
        new Date(s.starts_at).getTime() < dayEnd &&
        new Date(s.ends_at).getTime() > dayStart,
    )
    .map((s) => ({
      ...s,
      left:
        (100 * Math.max(0, new Date(s.starts_at).getTime() - dayStart)) /
        (dayEnd - dayStart),
      width:
        (100 *
          (Math.min(dayEnd, new Date(s.ends_at).getTime()) -
            Math.max(dayStart, new Date(s.starts_at).getTime()))) /
        (dayEnd - dayStart),
    }));
}
const tabs = [
  "Equipment",
  "Recipes",
  "Production",
  "Quality",
  "Workforce",
  "Scheduling",
  "Traceability",
];
const f = (key: string, label: string, source?: string): Field => ({
  key,
  label,
  source,
});
const actions: Record<string, Action[]> = {
  Equipment: [
    {
      title: "Register machine",
      role: "Production manager",
      method: "POST",
      path: "/machines",
      fields: [
        f("code", "Machine code"),
        f("name", "Machine name"),
        {
          key: "process",
          label: "Manufacturing process",
          options: ["INJECTION", "EXTRUSION", "BLOW_MOLDING", "RECYCLING"],
        },
        {
          key: "capacityKg",
          label: "Maximum material per job (kg)",
          type: "number",
        },
      ],
    },
    {
      title: "Register mold",
      role: "Production manager",
      method: "POST",
      path: "/molds",
      fields: [
        f("code", "Mold code"),
        f("name", "Mold name"),
        { key: "cavities", label: "Cavities", type: "number" },
      ],
    },
    {
      title: "Approve machine–mold compatibility",
      role: "Production manager",
      method: "PUT",
      path: "/machines/{machine}/molds/{mold}",
      fields: [f("machine", "Machine", "machines"), f("mold", "Mold", "molds")],
    },
    {
      title: "Set mold availability",
      role: "Production manager",
      method: "PUT",
      path: "/molds/{id}/state",
      fields: [
        f("id", "Mold", "molds"),
        {
          key: "state",
          label: "Availability",
          options: ["AVAILABLE", "UNAVAILABLE"],
        },
      ],
    },
    {
      title: "Set machine availability",
      role: "Production manager",
      method: "PUT",
      path: "/machines/{id}/state",
      fields: [
        f("id", "Machine", "machines"),
        {
          key: "state",
          label: "Availability",
          options: ["AVAILABLE", "UNAVAILABLE"],
        },
      ],
    },
  ],
  Recipes: [
    {
      title: "Create immutable recipe version",
      role: "Production manager",
      method: "POST",
      path: "/recipes",
      kind: "recipe",
      fields: [
        f("productId", "Finished product", "finished"),
        {
          key: "process",
          label: "Process",
          options: ["INJECTION", "EXTRUSION", "BLOW_MOLDING", "RECYCLING"],
        },
        { key: "yieldFactor", label: "Yield factor (0–1)", type: "number" },
        f("packaging", "Packaging instructions"),
      ],
    },
  ],
  Production: [
 {title:"Abort job with no good output",role:"Production manager",method:"POST",path:"/orders/{id}/abort",fields:[f("id","Order","orders"),f("reason","Reason for abort")]},

    {
      title: "Create production order",
      role: "Production manager",
      method: "POST",
      path: "/orders",
      fields: [
        f("code", "Order code"),
        f("salesReference", "External sales/demand reference"),
        f("recipeId", "Recipe version", "recipes"),
        f("inspectionPlanId", "Inspection plan version", "plans"),
        {
          key: "plannedQty",
          label: "Planned output in product base unit",
          type: "number",
        },
      ],
    },
    {
      title: "Register incoming material lot",
      role: "Production manager",
      method: "POST",
      path: "/lots",
      fields: [
        f("code", "Lot code"),
        f("materialId", "Raw material (kg)", "materials"),
        f("supplierReference", "External supplier receipt reference"),
        f("location", "Storage location"),
        { key: "receivedKg", label: "Received kilograms", type: "number" },
      ],
    },
    {
      title: "Start assigned job",
      role: "Operator / production manager",
      method: "POST",
      path: "/orders/{id}/start",
      fields: [f("id", "Production order", "orders")],
    },
    {
      title: "Record material consumption",
      role: "Operator / production manager",
      method: "POST",
      path: "/orders/{id}/consumptions",
      kind: "event",
      fields: [
        f("id", "Production order", "orders"),
        f("lotId", "Material lot", "lots"),
        { key: "kg", label: "Actual kilograms consumed", type: "number" },
      ],
    },
    {
      title: "Record output and rejects",
      role: "Operator / production manager",
      method: "POST",
      path: "/orders/{id}/outputs",
      kind: "event",
      fields: [
        f("id", "Production order", "orders"),
        {
          key: "goodQty",
          label: "Candidate-good output (not released)",
          type: "number",
        },
        { key: "rejectQty", label: "Rejected quantity", type: "number" },
        f("reason", "Output note / reject reason"),
      ],
    },
    {
      title: "Record downtime",
      role: "Operator / production manager",
      method: "POST",
      path: "/orders/{id}/downtime",
      fields: [
        f("id", "Production order", "orders"),
        { key: "start", label: "Started", type: "datetime-local" },
        { key: "end", label: "Ended", type: "datetime-local" },
        f("reason", "Downtime reason"),
      ],
    },
    {
      title: "Complete job and create pending batch",
      role: "Operator / production manager",
      method: "POST",
      path: "/orders/{id}/complete",
      fields: [f("id", "Production order", "orders")],
    },
  ],
  Quality: [
    {
      title: "Create inspection plan version",
      role: "Quality manager",
      method: "POST",
      path: "/inspection-plans",
      kind: "plan",
      fields: [f("productId", "Finished product", "finished")],
    },
    {
      title: "Inspect completed batch",
      role: "Quality manager",
      method: "POST",
      path: "/batches/{id}/inspections",
      kind: "measurements",
      fields: [f("id", "Batch", "batches")],
    },
    {
      title: "Release or hold batch",
      role: "Quality manager",
      method: "PUT",
      path: "/batches/{id}/quality",
      fields: [
        f("id", "Batch", "batches"),
        {
          key: "quality",
          label: "Decision",
          options: ["APPROVED", "QUARANTINED", "REJECTED"],
        },
        f("reason", "Decision reason"),
      ],
    },
    {
      title: "Approve or hold incoming lot",
      role: "Quality manager",
      method: "PUT",
      path: "/lots/{id}/quality",
      fields: [
        f("id", "Material lot", "lots"),
        {
          key: "quality",
          label: "Decision",
          options: ["APPROVED", "QUARANTINED", "REJECTED"],
        },
        f("reason", "Decision reason"),
      ],
    },
    {
      title: "Resolve nonconformity",
      role: "Quality manager",
      method: "PUT",
      path: "/nonconformities/{id}/resolve",
      fields: [
        f("id", "Nonconformity", "nonconformities"),
        f("reason", "Corrective action and resolution"),
      ],
    },
  ],
  Workforce: [
    {
      title: "Register employee",
      role: "Workforce manager",
      method: "POST",
      path: "/employees",
      fields: [
        f("code", "Employee code"),
        f("name", "Employee name"),
        f("userSubject", "Identity subject / demo username"),
      ],
    },
    {
      title: "Set machine qualification",
      role: "Workforce manager",
      method: "PUT",
      path: "/employees/{id}/qualifications",
      fields: [
        f("id", "Employee", "employees"),
        f("machineId", "Machine", "machines"),
        { key: "validUntil", label: "Valid through (UTC date)", type: "date" },
      ],
    },
    {
      title: "Create shift",
      role: "Workforce manager",
      method: "POST",
      path: "/shifts",
      fields: [
        f("employeeId", "Employee", "employees"),
        { key: "start", label: "Shift start", type: "datetime-local" },
        { key: "end", label: "Shift end", type: "datetime-local" },
      ],
    },
  ],
  Scheduling: [
 {title:"Cancel maintenance window",role:"Production manager",method:"POST",path:"/maintenance-blocks/{id}/cancel",fields:[f("id","Window","maintenance"),f("reason","Cancellation reason")]},

    {
      title: "Schedule or reschedule job",
      role: "Production manager",
      method: "PUT",
      path: "/orders/{id}/schedule",
      fields: [
        f("id", "Production order", "orders"),
        f("machineId", "Machine", "machines"),
        {
          key: "moldId",
          label: "Mold (required for molding)",
          source: "molds",
          optional: true,
        },
        f("employeeId", "Qualified employee", "employees"),
        f("shiftId", "Covering shift", "shifts"),
        { key: "start", label: "Job start", type: "datetime-local" },
        { key: "end", label: "Job end", type: "datetime-local" },
      ],
    },
    {
      title: "Block machine for maintenance",
      role: "Production manager",
      method: "POST",
      path: "/maintenance-blocks",
      fields: [
        f("machineId", "Machine", "machines"),
        { key: "start", label: "Maintenance start", type: "datetime-local" },
        { key: "end", label: "Maintenance end", type: "datetime-local" },
        f("reason", "Reason"),
      ],
    },
  ],
  Traceability: [
    {
      title: "Pack approved batch into pallet",
      role: "Production manager",
      method: "POST",
      path: "/pallets",
      fields: [
        f("code", "Pallet code"),
        f("batchId", "Released batch", "batches"),
        { key: "qty", label: "Quantity in product base unit", type: "number" },
      ],
    },
    {
      title: "Link external dispatch reference",
      role: "Production manager",
      method: "POST",
      path: "/dispatch-references",
      fields: [
        f("palletId", "Pallet", "pallets"),
        f("shipmentReference", "External shipment reference"),
        f("customerReference", "External customer reference"),
      ],
    },
  ],
};
const action = computed(
  () => actions[tab.value]![selected.value] ?? actions[tab.value]![0]!,
);
function rows(source: string): Row[] {
  if (source === "finished")
    return products.value.filter((p) => p.kind === "FINISHED_GOOD" && p.active);
  if (source === "materials")
    return products.value.filter(
      (p) => p.kind === "RAW_MATERIAL" && p.unit === "KG" && p.active,
    );
  return data.value[source] ?? [];
}
function label(row: Row): string {
  const product = products.value.find((p) => p.id === row.product_id);
  const employee = rows("employees").find((e) => e.id === row.employee_id);
  const order = rows("orders").find((o) => o.id === row.order_id);
  return (
    String(
      row.code ??
        row.name ??
        product?.name ??
        (employee ? employee.name + " · " + row.starts_at : undefined) ??
        order?.code ??
        row.id,
    ).slice(0, 60) +
    (row.revision ? " · v" + row.revision : "") +
    (row.state ? " · " + row.state : "") +
    (row.quality ? " · " + row.quality : "")
  );
}
async function request(
  path: string,
  method = "GET",
  body?: unknown,
  responseType: "json" | "text" = "json",
): Promise<any> {
  const response = await fetch(path, {
    method,
    headers: {
      Authorization: authorization,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let detail = "";
    try {
      const p = await response.json();
      detail = p.detail ?? p.code ?? "";
    } catch {}
    throw new Error(
      `HTTP ${response.status}: ${detail || "Check credentials and role permissions."}`,
    );
  }
  const raw = await response.text();
  if (responseType === "text") return raw;
  return raw.trim() ? JSON.parse(raw) : null;
}
async function refresh() {
  if (workspace.value === "catalog") {
    await request("/api/v1/products?size=1");
    return;
  }
  if (workspace.value === "integration") {
    await request("/api/v1/integration/overview");
    return;
  }
  if (workspace.value === "reports") {
    await request(
      session.value.roles.includes("REPORT_VIEWER")
        ? "/api/v1/integration/reports/device-health"
        : "/api/v1/integration/ai",
    );
    return;
  }
  if (workspace.value === "portal") {
    await request("/api/v1/portal/overview");
    return;
  }
  if (workspace.value === "finance") {
    await request("/api/v1/finance/overview");
    return;
  }
  if (workspace.value === "recycling") {
    await request("/api/v1/finance/recycling-overview");
    return;
  }
  if (workspace.value === "payroll") {
    await request(
      "/api/v1/payroll/" +
        (payView.value === "attendance" ? "attendance-overview" : "overview"),
    );
    return;
  }
  if (workspace.value === "operations") {
    await request("/api/v1/operations/" + opsSection.value + "/overview");
    return;
  }
  data.value = await request(base + "/overview");
  products.value = (await request("/api/v1/products?size=100")).items;
}
async function login() {
  error.value = "";
  busy.value = true;
  try {
    authorization =
      mode.value === "demo"
        ? "Basic " + btoa(username.value + ":" + password.value)
        : "Bearer " + token.value;
    session.value = await request("/api/v1/session");
    if (!availableWorkspaces.value.includes(workspace.value))
      workspace.value = availableWorkspaces.value[0] ?? workspace.value;
    prepareWorkspace();
    await refresh();
    connected.value = true;
    password.value = "";
    token.value = "";
  } catch (e) {
    error.value = String(e);
    authorization = "";
  } finally {
    busy.value = false;
  }
}
function logout() {
  authorization = "";
  session.value = { subject: "", roles: [] };
  connected.value = false;
  data.value = {};
  products.value = [];
  form.value = {};
  orderDetail.value = null;
  trace.value = null;
}
function changeTab(value: string) {
  tab.value = value;
  selected.value = 0;
  reset();
}
function reset() {
  form.value = {};
  error.value = "";
  message.value = "";
  measurements.value = {};
  eventId.value = crypto.randomUUID();
}
const inspectionMetrics = computed(() => {
  const batch = rows("batches").find((r) => r.id === form.value.id);
  const order = rows("orders").find((r) => r.id === batch?.order_id);
  const plan = rows("plans").find((r) => r.id === order?.inspection_plan_id);
  return plan
    ? (JSON.parse(plan.metrics) as {
        name: string;
        unit: string;
        minimum: number;
        maximum: number;
      }[])
    : [];
});
async function submit() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    let path = action.value.path;
    const body: Record<string, unknown> = {};
    for (const field of action.value.fields) {
      const value = form.value[field.key] ?? "";
      if (path.includes("{" + field.key + "}")) {
        path = path.replace("{" + field.key + "}", encodeURIComponent(value));
        continue;
      }
      body[field.key] =
        field.type === "number"
          ? Number(value)
          : field.type === "datetime-local"
            ? new Date(value).toISOString()
            : value === "" && field.optional
              ? null
              : value;
    }
    if (action.value.kind === "recipe") {
      body.components = components.value.map((c) => ({
        materialId: c.materialId,
        kgPerUnit: Number(c.kgPerUnit),
      }));
      body.operations = operations.value.map((o) => ({
        name: o.name,
        minutesPerUnit: Number(o.minutesPerUnit),
      }));
      body.settings = Object.fromEntries(
        settings.value.filter((s) => s.key).map((s) => [s.key, s.value]),
      );
    }
    if (action.value.kind === "plan")
      body.metrics = metrics.value.map((m) => ({
        ...m,
        minimum: Number(m.minimum),
        maximum: Number(m.maximum),
      }));
    if (action.value.kind === "measurements")
      body.values = Object.fromEntries(
        inspectionMetrics.value.map((m) => [
          m.name,
          Number(measurements.value[m.name]),
        ]),
      );
    if (action.value.kind === "event") body.eventId = eventId.value;
    await request(
      base + path,
      action.value.method,
      Object.keys(body).length ? body : undefined,
    );
    await refresh();
    message.value = action.value.title + " completed.";
    eventId.value = crypto.randomUUID();
    if (form.value.id && rows("orders").some((o) => o.id === form.value.id))
      orderDetail.value = await request(base + "/orders/" + form.value.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function inspectRecipe(id: string) {
  try {
    recipeDetail.value = await request(base + "/recipes/" + id);
  } catch (e) {
    error.value = String(e);
  }
}
async function inspectOrder(id: string) {
  try {
    orderDetail.value = await request(base + "/orders/" + id);
  } catch (e) {
    error.value = String(e);
  }
}
async function inspectTrace(id: string, kind: string) {
  try {
    trace.value = await request(base + "/" + kind + "/" + id + "/trace");
  } catch (e) {
    error.value = String(e);
  }
}
const tables: Record<string, { source: string; columns: string[] }[]> = {
  Equipment: [
    {
      source: "machines",
      columns: ["code", "name", "process", "capacity_kg", "state"],
    },
    { source: "molds", columns: ["code", "name", "cavities", "state"] },
  ],
  Recipes: [
    {
      source: "recipes",
      columns: [
        "id",
        "product_id",
        "revision",
        "process",
        "yield_factor",
        "packaging",
      ],
    },
  ],
  Production: [
    {
      source: "orders",
      columns: [
        "code",
        "sales_reference",
        "planned_qty",
        "good_qty",
        "reject_qty",
        "state",
      ],
    },
    {
      source: "lots",
      columns: [
        "code",
        "supplier_reference",
        "received_kg",
        "remaining_kg",
        "quality",
      ],
    },
  ],
  Quality: [
    { source: "batches", columns: ["id", "order_id", "qty", "quality"] },
    {
      source: "nonconformities",
      columns: ["id", "batch_id", "reason", "resolved"],
    },
  ],
  Workforce: [
    { source: "employees", columns: ["code", "name", "active"] },
    { source: "shifts", columns: ["employee_id", "starts_at", "ends_at"] },
  ],
  Scheduling: [
    {
      source: "schedule",
      columns: [
        "order_id",
        "machine_id",
        "mold_id",
        "employee_id",
        "starts_at",
        "ends_at",
      ],
    },
    {
      source: "maintenance",
      columns: ["machine_id", "starts_at", "ends_at", "reason", "cancelled"],
    },
  ],
  Traceability: [{ source: "pallets", columns: ["code", "batch_id", "qty"] }],
};
</script>
<template>
  <div v-if="connected" class="workspace-switch">
    <label
      >Workspace<select v-model="workspace" @change="switchWorkspace">
        <option v-for="w in availableWorkspaces" :key="w" :value="w">
          {{ workspaceNames[w] }}
        </option>
      </select></label
    ><span>{{ session.subject }}</span>
  </div>
  <CatalogWorkspace
    v-if="connected && workspace === 'catalog'"
    :request="request"
    :read-only="!session.roles.includes('CATALOG_MANAGER')"
    @logout="logout"
  />
  <main v-else-if="!connected" class="login">
    <h1>PlastiFlow</h1>
    <p>Industrial ERP · factory workspaces</p>
    <form @submit.prevent="login">
      <label
        >Connection<select v-model="mode">
          <option value="demo">Local demonstration account</option>
          <option value="bearer">OIDC access token</option>
        </select></label
      ><template v-if="mode === 'demo'"
        ><label
          >Account<select v-model="username" @change="accountChanged">
            <option>manager</option>
            <option>reader</option>
            <option>production</option>
            <option>quality</option>
            <option>workforce</option>
            <option>operator</option>
            <option>maintenance</option>
            <option>technician</option>
            <option>facilities</option>
            <option>logistics</option>
            <option>security</option>
            <option>gate</option>
            <option>payroll</option>
            <option>payrollapprover</option>
            <option>accountant</option>
            <option>finance</option>
            <option>financeapprover</option>
            <option>integration</option>
            <option>reports</option>
            <option>customer</option>
            <option>customer2</option>
            <option>supplier</option>
          </select></label
        ><label
          >Password<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            required /></label></template
      ><label v-else
        >Access token<input v-model="token" type="password" required /></label
      ><label
        >Workspace<select v-model="workspace">
          <option value="catalog">Catalog</option>
          <option value="manufacturing">Manufacturing</option>
          <option value="operations">Factory operations</option>
          <option value="payroll">Attendance / payroll</option>
          <option value="finance">Finance / costing</option>
          <option value="recycling">Recycling</option>
          <option value="integration">Integration management</option>
          <option value="reports">Reports / AI</option>
          <option value="portal">Partner portal</option>
        </select></label
      ><label v-if="workspace === 'operations'"
        >Department<select v-model="opsSection">
          <option value="maintenance">Maintenance</option>
          <option value="facilities">Facilities</option>
          <option value="yard">Truck Yard</option>
          <option value="security">Physical Access</option>
        </select></label
      ><label v-if="workspace === 'payroll'"
        >View<select v-model="payView">
          <option value="attendance">Attendance validation</option>
          <option value="pay">Payroll / approval</option>
        </select></label
      ><button :disabled="busy">Open workspace</button>
      <p role="alert" class="error">{{ error }}</p>
    </form>
    <small
      >Development interface. Credentials remain in memory and clear on logout
      or refresh.</small
    >
  </main>
  <IntegrationWorkspace
    :key="workspace"
    v-else-if="['integration', 'reports', 'portal'].includes(workspace)"
    :request="request"
    :view="workspace"
    :roles="session.roles"
    :username="mode === 'demo' ? username : 'OIDC'"
    @logout="logout"
  />
  <FinanceWorkspace
    v-else-if="workspace === 'finance' || workspace === 'recycling'"
    :request="request"
    :recycling-only="workspace === 'recycling'"
    :username="mode === 'demo' ? username : 'OIDC'"
    @logout="logout"
  />
  <PayrollWorkspace
    v-else-if="workspace === 'payroll'"
    :request="request"
    :view="payView"
    :username="mode === 'demo' ? username : 'OIDC'"
    @logout="logout"
  />
  <OperationsWorkspace
    v-else-if="workspace === 'operations'"
    :request="request"
    :initial-section="opsSection"
    :username="mode === 'demo' ? username : 'OIDC'"
    @logout="logout"
  />
  <div v-else class="shell">
    <aside>
      <h2>PlastiFlow</h2>
      <small>Factory manufacturing</small>
      <nav>
        <button
          v-for="t in tabs"
          :key="t"
          :class="{ active: tab === t }"
          @click="changeTab(t)"
        >
          {{ t }}
        </button>
      </nav>
      <button @click="logout">Sign out / switch role</button>
    </aside>
    <main>
      <header>
        <div>
          <small>OPERATIONS WORKSPACE</small>
          <h1>{{ tab }}</h1>
        </div>
        <button @click="refresh().catch((e) => (error = String(e)))">
          Refresh records
        </button>
      </header>
      <p class="notice">
        {{ mode === "demo" ? username : "OIDC" }} account · global prototype ·
        dates entered in your browser timezone, stored in UTC
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <section class="panel">
        <h2>Actions</h2>
        <select v-model="selected" @change="reset">
          <option v-for="(a, i) in actions[tab]" :key="a.title" :value="i">
            {{ a.title }}
          </option>
        </select>
        <p>{{ action.role }}</p>
        <form @submit.prevent="submit">
          <div class="fields">
            <label v-for="field in action.fields" :key="field.key"
              >{{ field.label
              }}<select
                v-if="field.source"
                v-model="form[field.key]"
                :required="!field.optional"
              >
                <option value="">
                  Select {{ field.optional ? "(optional)" : "" }}
                </option>
                <option
                  v-for="row in rows(field.source)"
                  :key="row.id"
                  :value="row.id"
                >
                  {{ label(row) }}
                </option></select
              ><select
                v-else-if="field.options"
                v-model="form[field.key]"
                required
              >
                <option value="">Select</option>
                <option v-for="o in field.options" :key="o">
                  {{ o }}
                </option></select
              ><input
                v-else
                v-model="form[field.key]"
                :type="field.type ?? 'text'"
                :step="field.type === 'number' ? 'any' : undefined"
                :required="!field.optional"
            /></label>
          </div>
          <template v-if="action.kind === 'recipe'"
            ><h3>Material recipe · kg per output unit</h3>
            <div v-for="(c, i) in components" :key="i" class="inline">
              <select v-model="c.materialId" required>
                <option value="">Raw material</option>
                <option
                  v-for="p in rows('materials')"
                  :key="p.id"
                  :value="p.id"
                >
                  {{ p.name }}
                </option></select
              ><input
                v-model="c.kgPerUnit"
                type="number"
                min="0.000001"
                step="any"
                aria-label="Kilograms per unit"
                required
              /><button type="button" @click="components.splice(i, 1)">
                Remove
              </button>
            </div>
            <button
              type="button"
              @click="components.push({ materialId: '', kgPerUnit: '0.01' })"
            >
              Add material
            </button>
            <h3>Routing · ordered operations</h3>
            <div v-for="(o, i) in operations" :key="i" class="inline">
              <input
                v-model="o.name"
                placeholder="Operation name"
                required
              /><input
                v-model="o.minutesPerUnit"
                type="number"
                min="0.000001"
                step="any"
                aria-label="Minutes per output unit"
                required
              /><button type="button" @click="operations.splice(i, 1)">
                Remove
              </button>
            </div>
            <button
              type="button"
              @click="operations.push({ name: '', minutesPerUnit: '0.01' })"
            >
              Add operation
            </button>
            <h3>Approved process settings</h3>
            <div v-for="(s, i) in settings" :key="i" class="inline">
              <input v-model="s.key" placeholder="Parameter and unit" /><input
                v-model="s.value"
                placeholder="Approved value"
              />
            </div>
            <button
              type="button"
              @click="settings.push({ key: '', value: '' })"
            >
              Add setting
            </button></template
          >
          <template v-if="action.kind === 'plan'"
            ><h3>Inspection limits</h3>
            <div v-for="(m, i) in metrics" :key="i" class="inline">
              <input v-model="m.name" placeholder="Metric" required /><input
                v-model="m.unit"
                placeholder="Unit"
                required
              /><input
                v-model="m.minimum"
                type="number"
                step="any"
                aria-label="Minimum"
                required
              /><input
                v-model="m.maximum"
                type="number"
                step="any"
                aria-label="Maximum"
                required
              />
            </div>
            <button
              type="button"
              @click="
                metrics.push({ name: '', unit: '', minimum: '0', maximum: '1' })
              "
            >
              Add metric
            </button></template
          >
          <template v-if="action.kind === 'measurements'"
            ><label v-for="m in inspectionMetrics" :key="m.name"
              >{{ m.name }} ({{ m.unit }}), limits {{ m.minimum }}–{{ m.maximum
              }}<input
                v-model="measurements[m.name]"
                type="number"
                step="any"
                required /></label
          ></template>
          <p v-if="action.kind === 'event'">
            Retry protection is active for this submission. Retrying a failed
            request keeps its event identifier.
          </p>
          <button class="primary" :disabled="busy">
            {{ busy ? "Processing…" : action.title }}
          </button>
        </form>
      </section>
      <section v-if="tab === 'Scheduling'" class="panel">
        <h2>Machine calendar · local day</h2>
        <input v-model="calendarDay" type="date" aria-label="Calendar day" />
        <div class="calendar">
          <div class="calendar-label">Machine</div>
          <div class="hours">
            <span>00:00</span><span>06:00</span><span>12:00</span
            ><span>18:00</span><span>24:00</span>
          </div>
          <template v-for="m in rows('machines')" :key="m.id"
            ><div class="calendar-label">{{ m.code }}</div>
            <div class="lane">
              <div
                v-for="(bar, i) in bars(m.id)"
                :key="i"
                class="bar"
                :class="bar.kind"
                :style="{ left: bar.left + '%', width: bar.width + '%' }"
                :title="bar.label + ' ' + bar.starts_at + ' → ' + bar.ends_at"
              >
                {{ bar.label }}
              </div>
            </div></template
          >
        </div>
      </section>
      <section v-for="table in tables[tab]" :key="table.source" class="panel">
        <h2>{{ table.source.replaceAll("_", " ") }}</h2>
        <Table :rows="rows(table.source)" :columns="table.columns" />
      </section>
      <section v-if="tab === 'Recipes'" class="panel">
        <h2>Recipe version details</h2>
        <select
          @change="inspectRecipe(($event.target as HTMLSelectElement).value)"
        >
          <option value="">Choose recipe version</option>
          <option v-for="r in rows('recipes')" :key="r.id" :value="r.id">
            {{ label(r) }}
          </option></select
        ><template v-if="recipeDetail"
          ><h3>Components</h3>
          <Table
            :rows="recipeDetail.components"
            :columns="['material_id', 'kg_per_unit']"
          />
          <h3>Routing</h3>
          <Table
            :rows="recipeDetail.operations"
            :columns="['sequence_no', 'name', 'minutes_per_unit']"
          />
          <h3>Process settings</h3>
          <p>{{ recipeDetail.settings }}</p>
          <h3>Packaging</h3>
          <p>{{ recipeDetail.packaging }}</p></template
        >
      </section>
      <section v-if="tab === 'Production'" class="panel">
        <h2>Order requirements and actuals</h2>
        <select
          @change="inspectOrder(($event.target as HTMLSelectElement).value)"
        >
          <option value="">Choose order</option>
          <option v-for="o in rows('orders')" :key="o.id" :value="o.id">
            {{ o.code }}
          </option></select
        ><template v-if="orderDetail"
          ><h3>Material requirements (not stock reservations)</h3>
          <Table
            :rows="orderDetail.requirements"
            :columns="[
              'material_id',
              'required_kg',
              'consumed_kg',
              'remaining_required_kg',
              'available_kg',
              'shortage_kg',
            ]" />
          <h3>Actual consumption</h3>
          <Table
            :rows="orderDetail.consumptions"
            :columns="['lot_id', 'kg', 'created_at']" />
          <h3>Output events</h3>
          <Table
            :rows="orderDetail.output_events"
            :columns="['good_qty', 'reject_qty', 'reason', 'created_at']"
        /></template>
      </section>
      <section v-if="tab === 'Traceability'" class="panel">
        <h2>Batch genealogy</h2>
        <select
          @change="
            inspectTrace(($event.target as HTMLSelectElement).value, 'batches')
          "
        >
          <option value="">Choose production batch</option>
          <option v-for="b in rows('batches')" :key="b.id" :value="b.id">
            {{ b.id }}
          </option>
        </select>
        <h3>Forward search from supplier lot</h3>
        <select
          @change="
            inspectTrace(($event.target as HTMLSelectElement).value, 'lots')
          "
        >
          <option value="">Choose material lot</option>
          <option v-for="l in rows('lots')" :key="l.id" :value="l.id">
            {{ l.code }}
          </option></select
        ><template v-if="trace?.source_lots"
          ><h3>
            Supplier lots → production batch → pallets → external shipment
          </h3>
          <Table
            :rows="trace.source_lots"
            :columns="[
              'code',
              'supplier_reference',
              'material_id',
              'kg',
            ]" /><Table
            :rows="trace.recovery_links ?? []"
            :columns="[
              'source_lot_id',
              'output_lot_id',
              'classification',
              'input_kg',
              'recovered_kg',
            ]" /><Table
            :rows="trace.pallets"
            :columns="['code', 'qty']" /><Table
            :rows="trace.dispatch_references"
            :columns="[
              'shipment_reference',
              'customer_reference',
              'pallet_id',
            ]" /></template
        ><template v-if="trace?.consumption_orders">
          <h3>Consumption history, including aborted jobs</h3>
          <Table :rows="trace.consumption_orders" :columns="['code', 'state', 'kg', 'created_at']" />
        </template><template v-if="trace?.downstream_batches"
          ><h3>Downstream production batches</h3>
          <Table
            :rows="trace.downstream_batches"
            :columns="['id', 'order_id', 'qty', 'quality']"
        /></template>
      </section>
      <footer>
        ERP records only. These actions do not operate physical machines. Lists
        show up to 200 records; catalog options show up to 100.
      </footer>
    </main>
  </div>
</template>
