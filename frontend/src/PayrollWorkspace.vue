<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Table from "./Table.vue";
import { payrollActions } from "./payroll-config";
type Row = Record<string, any>;
type Field = {
  key: string;
  label: string;
  source?: string;
  type?: string;
  options?: string[];
};
type Action = {
  title: string;
  path: string;
  fields: Field[];
  kind?: string | null;
};
const props = defineProps<{
  request: (path: string, method?: string, body?: unknown) => Promise<any>;
  view: string;
  username: string;
}>();
const emit = defineEmits<{ logout: [] }>();
const data = ref<Record<string, Row[]>>({}),
  form = ref<Row>({}),
  selected = ref(0),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  detail = ref<Row | null>(null),
  eventId = ref(crypto.randomUUID());
const breaks = ref<{ start: string; end: string }[]>([]),
  rawIds = ref<string[]>([]);
const actions = (payrollActions as Record<string, Action[]>)[props.view] ?? [];
const action = computed(() => actions[selected.value]!);
const columns: Record<string, string[]> = {
  clockEvents: ["employee_id", "kind", "occurred_at", "source"],
  attendance: [
    "id",
    "employee_id",
    "starts_at",
    "ends_at",
    "state",
    "overtime_minutes",
  ],
  absences: ["id", "employee_id", "kind", "starts_at", "ends_at", "state"],
  wages: [
    "employee_id",
    "effective_from",
    "basis",
    "amount",
    "currency",
    "monthly_minutes",
  ],
  rules: [
    "effective_from",
    "night_start",
    "night_end",
    "night_type",
    "night_value",
    "overtime_percent",
    "stacking",
  ],
  periods: ["code", "starts_on", "ends_on", "zone_id", "currency", "state"],
  adjustments: ["employee_id", "kind", "amount", "reason", "state"],
};
function rows(key: string): Row[] {
  return data.value[key] ?? [];
}
function label(r: Row): string {
  const employee = rows("employees").find((e) => e.id === r.employee_id);
  return (
    String(r.code ?? employee?.name ?? r.id) +
    (r.starts_at ? " · " + r.starts_at : "") +
    (r.kind ? " · " + r.kind : "") +
    (r.state ? " · " + r.state : "")
  );
}
async function refresh() {
  data.value = await props.request(
    "/api/v1/payroll/" +
      (props.view === "attendance" ? "attendance-overview" : "overview"),
  );
}
function reset() {
  form.value = {};
  breaks.value = [];
  rawIds.value = [];
  eventId.value = crypto.randomUUID();
  message.value = "";
  error.value = "";
}
onMounted(() => refresh().catch((e) => (error.value = String(e))));
async function submit() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    let path = action.value.path;
    const body: Row = {};
    for (const f of action.value.fields) {
      const v = form.value[f.key];
      if (path.includes("{" + f.key + "}")) {
        path = path.replace("{" + f.key + "}", encodeURIComponent(v));
        continue;
      }
      body[f.key] =
        f.type === "number"
          ? Number(v)
          : f.type === "boolean"
            ? v === "true"
            : f.type === "datetime-local"
              ? new Date(v).toISOString()
              : v;
    }
    if (action.value.kind === "attendance") {
      body.unpaidBreaks = breaks.value.map((b) => ({
        start: new Date(b.start).toISOString(),
        end: new Date(b.end).toISOString(),
      }));
      body.rawEventIds = rawIds.value;
    }
    if (action.value.kind === "event") body.eventId = eventId.value;
    const result = await props.request(
      "/api/v1/payroll" + path,
      "POST",
      Object.keys(body).length ? body : undefined,
    );
    if (path.endsWith("/calculate")) detail.value = result;
    await refresh();
    message.value = action.value.title + " completed.";
    eventId.value = crypto.randomUUID();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function show(id: string) {
  try {
    detail.value = await props.request("/api/v1/payroll/periods/" + id);
  } catch (e) {
    error.value = String(e);
  }
}
function print() {
  window.print();
}
function breakdown(line: Row): Row[] {
  const segments = JSON.parse(line.snapshot).segments;
  return segments.map((s: Row) => ({
    wage_version: s.wage_version.id,
    rule_version: s.rule_version?.id ?? "Paid leave",
    minutes: s.minutes,
    night: s.night,
    overtime: s.overtime,
    base: s.base,
    night_extra: s.night_extra,
    overtime_extra: s.overtime_extra,
  }));
}
</script>
<template>
  <div class="shell">
    <aside class="no-print">
      <h2>PlastiFlow</h2>
      <small>Workforce release</small>
      <nav>
        <strong>{{
          view === "attendance"
            ? "Attendance validation"
            : "Payroll and approvals"
        }}</strong>
      </nav>
      <button @click="emit('logout')">Sign out / switch role</button>
    </aside>
    <main>
      <header>
        <div>
          <small>WORKFORCE</small>
          <h1>
            {{ view === "attendance" ? "Attendance and leave" : "Payroll" }}
          </h1>
        </div>
        <button
          class="no-print"
          @click="refresh().catch((e) => (error = String(e)))"
        >
          Refresh
        </button>
      </header>
      <p class="notice">
        {{ username }} account · payroll includes configured pay and entered
        adjustments; statutory taxes and contributions are not configured.
      </p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="message" role="status" class="success">{{ message }}</p>
      <section class="panel no-print">
        <h2>Actions</h2>
        <select v-model="selected" @change="reset">
          <option v-for="(a, i) in actions" :key="a.title" :value="i">
            {{ a.title }}
          </option>
        </select>
        <form @submit.prevent="submit">
          <div class="fields">
            <label v-for="f in action.fields" :key="f.key"
              >{{ f.label
              }}<select
                v-if="f.source"
                v-model="form[f.key]"
                :multiple="f.type === 'multiple'"
                required
              >
                <option v-if="f.type !== 'multiple'" value="">Select</option>
                <option v-for="r in rows(f.source)" :key="r.id" :value="r.id">
                  {{ label(r) }}
                </option></select
              ><select
                v-else-if="f.type === 'boolean'"
                v-model="form[f.key]"
                required
              >
                <option value="">Select</option>
                <option value="true">Yes</option>
                <option value="false">No</option></select
              ><select v-else-if="f.options" v-model="form[f.key]" required>
                <option value="">Select</option>
                <option v-for="o in f.options" :key="o">{{ o }}</option></select
              ><input
                v-else
                v-model="form[f.key]"
                :type="f.type ?? 'text'"
                :step="f.type === 'number' ? 'any' : undefined"
                required
            /></label>
          </div>
          <template v-if="action.kind === 'attendance'"
            ><h3>Unpaid breaks (whole-minute times)</h3>
            <div v-for="(b, i) in breaks" :key="i" class="inline">
              <input
                v-model="b.start"
                type="datetime-local"
                required
                aria-label="Break start"
              /><input
                v-model="b.end"
                type="datetime-local"
                required
                aria-label="Break end"
              /><button type="button" @click="breaks.splice(i, 1)">
                Remove
              </button>
            </div>
            <button type="button" @click="breaks.push({ start: '', end: '' })">
              Add unpaid break</button
            ><label
              >Original clock evidence (optional)<select
                v-model="rawIds"
                multiple
              >
                <option
                  v-for="e in rows('clockEvents')"
                  :key="e.event_id"
                  :value="e.event_id"
                >
                  {{ e.kind }} · {{ e.occurred_at }}
                </option>
              </select></label
            >
            <p>
              Approval classifies the last specified paid minutes as overtime.
              Raw clock records remain unchanged.
            </p></template
          ><button class="primary" :disabled="busy">
            {{ busy ? "Processing…" : action.title }}
          </button>
        </form>
      </section>
      <section
        v-for="(cols, key) in columns"
        v-show="data[key]"
        :key="key"
        class="panel no-print"
      >
        <h2>{{ key }}</h2>
        <Table :rows="rows(key)" :columns="cols" />
      </section>
      <section v-if="view === 'pay'" class="panel">
        <h2>Pay-period statements</h2>
        <select
          class="no-print"
          @change="show(($event.target as HTMLSelectElement).value)"
        >
          <option value="">Choose period</option>
          <option v-for="p in rows('periods')" :key="p.id" :value="p.id">
            {{ p.code }} · {{ p.state }}
          </option></select
        ><template v-if="detail"
          ><h3>
            {{ detail.code }} · {{ detail.state }} · {{ detail.currency }}
          </h3>
          <p>
            {{ detail.starts_on }} to {{ detail.ends_on }} (end exclusive),
            {{ detail.zone_id }}
          </p>
          <button class="no-print" @click="print">
            Print / save statements as PDF
          </button>
          <article
            v-for="line in detail.lines"
            :key="line.id"
            class="statement"
          >
            <h3>{{ line.name }} · {{ line.code }}</h3>
            <Table
              :rows="[line]"
              :columns="[
                'worked_minutes',
                'night_minutes',
                'overtime_minutes',
                'paid_leave_minutes',
                'base_pay',
                'night_pay',
                'overtime_pay',
                'bonus',
                'deduction',
                'gross',
                'payable',
              ]"
            />
            <p>
              Payable after entered deductions: {{ line.payable }}
              {{ detail.currency }}. Statutory calculations pending.
            </p>
            <h4>Applied version breakdown</h4>
            <Table
              :rows="breakdown(line)"
              :columns="[
                'wage_version',
                'rule_version',
                'minutes',
                'night',
                'overtime',
                'base',
                'night_extra',
                'overtime_extra',
              ]"
            /></article
        ></template>
      </section>
    </main>
  </div>
</template>
<style scoped>
.statement {
  border-top: 2px solid #dae3e7;
  padding-top: 15px;
  margin-top: 25px;
}
@media print {
  .no-print {
    display: none !important;
  }
  .shell {
    display: block;
  }
  main {
    padding: 0;
  }
  .panel {
    border: 0;
    padding: 0;
  }
  .statement {
    break-before: page;
  }
  table {
    font-size: 10px;
  }
}
</style>
