<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Table from "./Table.vue";
import { actions, tables } from "./integration-config";
type Row = Record<string, any>;
const props = defineProps<{
  request: (
    path: string,
    method?: string,
    body?: unknown,
    responseType?: "json" | "text",
  ) => Promise<any>;
  username: string;
  view: string;
  roles: string[];
}>();
defineEmits<{ logout: [] }>();
const tabs = (
  props.view === "portal"
    ? ["Portal"]
    : props.view === "reports"
      ? ["Reports", "AI"]
      : ["Devices", "Partners", "Reports", "AI"]
).filter(
  (t) =>
    (t !== "AI" || props.roles.includes("AI_ANALYST")) &&
    (t !== "Reports" || props.roles.includes("REPORT_VIEWER")),
);
const tab = ref(tabs[0]),
  data = ref<Record<string, any>>({}),
  selected = ref(0),
  form = ref<Record<string, string>>({}),
  error = ref(""),
  message = ref(""),
  busy = ref(false);
const aiData = ref<Row>({ enabled: false, jobs: [] }),
  job = ref<Row | null>(null),
  report = ref<Row | null>(null),
  health = ref<Row | null>(null),
  from = ref(new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10)),
  to = ref(new Date(Date.now() + 86400000).toISOString().slice(0, 10));
const requestKind = ref("SALES_REQUEST"),
  reference = ref(""),
  description = ref(""),
  portalEvent = ref(crypto.randomUUID()),
  aiEvent = ref(crypto.randomUUID()),
  lines = ref([{ productId: "", quantity: "1" }]);
const action = computed(() => actions[tab.value]?.[selected.value]);
const rows = (source: string): Row[] => data.value[source] ?? [];
const label = (row: Row) =>
  `${row.code ?? row.sku ?? row.reference ?? row.principal ?? row.id} ${row.state ?? row.kind ?? ""}`;
const decoded = (text: string | undefined) => (text ? JSON.parse(text) : null);
async function refresh() {
  try {
    if (props.view === "portal") {
      data.value = await props.request("/api/v1/portal/overview");
      requestKind.value =
        data.value.partner.kind === "SUPPLIER"
          ? "SUPPLY_OFFER"
          : "SALES_REQUEST";
    } else {
      if (props.view === "integration")
        data.value = await props.request("/api/v1/integration/overview");
      if (props.roles.includes("REPORT_VIEWER"))
        health.value = await props.request(
          "/api/v1/integration/reports/device-health",
        );
      if (props.roles.includes("AI_ANALYST"))
        aiData.value = await props.request("/api/v1/integration/ai");
    }
  } catch (e) {
    error.value = String(e);
  }
}
onMounted(refresh);
function changeTab(value: string) {
  tab.value = value;
  selected.value = 0;
  form.value = {};
  error.value = "";
  message.value = "";
}
async function work(task: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await task();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function submit() {
  await work(async () => {
    if (!action.value) return;
    let path = action.value.path;
    const body: Row = {};
    for (const field of action.value.fields) {
      const value = form.value[field.key] ?? "";
      if (path.includes("{" + field.key + "}")) {
        path = path.replace("{" + field.key + "}", encodeURIComponent(value));
        continue;
      }
      body[field.key] =
        field.key === "accepted"
          ? value === "true"
          : !value && field.optional
            ? null
            : value;
    }
    const result = await props.request(
      "/api/v1/integration" + path,
      action.value.method ?? "POST",
      Object.keys(body).length ? body : undefined,
    );
    await refresh();
    if (result?.state === "FAILED")
      error.value = `Event blocked: ${result.error_code}. Review the binding or business record before retrying.`;
    else
      message.value = result?.state
        ? `Event ${result.state}`
        : action.value.title + " completed.";
  });
}
const reportQuery = () =>
  "?from=" +
  encodeURIComponent(from.value) +
  "&to=" +
  encodeURIComponent(to.value);
async function loadReport() {
  await work(async () => {
    report.value = await props.request(
      "/api/v1/integration/reports/production" + reportQuery(),
    );
  });
}
async function exportReport() {
  await work(async () => {
    const csv = await props.request(
      "/api/v1/integration/reports/production.csv" + reportQuery(),
      "GET",
      undefined,
      "text",
    );
    const url = URL.createObjectURL(
      new Blob([csv], { type: "text/csv;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "production.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
}
async function prepareAi() {
  await work(async () => {
    job.value = await props.request("/api/v1/integration/ai/jobs", "POST", {
      eventId: aiEvent.value,
      from: from.value,
      to: to.value,
    });
    aiEvent.value = crypto.randomUUID();
    await refresh();
    message.value =
      "Prepared report snapshot. Review the data below before running the provider.";
  });
}
async function aiAction(path: string, body?: unknown) {
  await work(async () => {
    if (!job.value) return;
    const id = job.value.id;
    await props.request(
      "/api/v1/integration/ai/jobs/" + id + "/" + path,
      "POST",
      body,
    );
    job.value = await props.request("/api/v1/integration/ai/jobs/" + id);
    await refresh();
    if (job.value?.state === "FAILED")
      error.value =
        "AI generation failed. The prepared snapshot remains available for a bounded retry.";
    else message.value = "AI job " + job.value?.state;
  });
}
const reviewNotes = ref("");
async function submitPortal() {
  await work(async () => {
    await props.request("/api/v1/portal/requests", "POST", {
      eventId: portalEvent.value,
      kind: requestKind.value,
      reference: reference.value,
      description: description.value,
      lines: lines.value
        .filter((l) => l.productId)
        .map((l) => ({ productId: l.productId, quantity: Number(l.quantity) })),
    });
    portalEvent.value = crypto.randomUUID();
    reference.value = "";
    description.value = "";
    lines.value = [{ productId: "", quantity: "1" }];
    await refresh();
    message.value = "Request submitted for factory review.";
  });
}
</script>
<template>
  <div class="shell">
    <aside>
      <h2>PlastiFlow</h2>
      <small>{{
        view === "portal" ? "Partner workspace" : "Factory integration"
      }}</small>
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
      <button @click="$emit('logout')">Sign out / switch role</button>
    </aside>
    <main>
      <header>
        <div>
          <small>FACTORY CONNECTIONS</small>
          <h1>{{ tab }}</h1>
        </div>
        <button @click="refresh">Refresh records</button>
      </header>
      <p class="notice">
        {{ username }} ·
        {{
          view === "portal"
            ? "Your company records only. Requests await factory review."
            : "Device events are evidence. AI suggestions require human review."
        }}
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <template v-if="action"
        ><section class="panel">
          <h2>Manager actions</h2>
          <select v-model="selected" @change="form = {}">
            <option v-for="(a, i) in actions[tab]" :key="a.title" :value="i">
              {{ a.title }}
            </option>
          </select>
          <p>{{ action.hint }}</p>
          <form @submit.prevent="submit">
            <div class="fields">
              <label v-for="field in action.fields" :key="field.key"
                >{{ field.label
                }}<select
                  v-if="field.source"
                  v-model="form[field.key]"
                  :required="!field.optional"
                >
                  <option value="">Select</option>
                  <option
                    v-for="row in rows(field.source)"
                    :key="row.id ?? row.principal"
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
                  <option v-for="option in field.options" :key="option">
                    {{ option }}
                  </option></select
                ><input
                  v-else
                  v-model="form[field.key]"
                  :required="!field.optional"
              /></label>
            </div>
            <button class="primary" :disabled="busy">{{ action.title }}</button>
          </form>
        </section>
        <section v-for="table in tables[tab]" :key="table.source" class="panel">
          <h2>{{ table.source.replaceAll("_", " ") }}</h2>
          <Table :rows="rows(table.source)" :columns="table.columns" /></section
      ></template>
      <template v-if="tab === 'Reports' || tab === 'AI'"
        ><section class="panel">
          <h2>Production output · UTC date range</h2>
          <div class="fields">
            <label>From<input v-model="from" type="date" /></label
            ><label>To (exclusive)<input v-model="to" type="date" /></label>
          </div>
          <p>
            Quantities stay grouped by SKU and unit. This report covers actual
            output events in up to 93 days.
          </p>
          <button
            v-if="roles.includes('REPORT_VIEWER')"
            :disabled="busy"
            @click="loadReport"
          >
            Load report</button
          ><button
            v-if="roles.includes('REPORT_VIEWER')"
            :disabled="busy"
            @click="exportReport"
          >
            Download CSV</button
          ><template v-if="report"
            ><p>
              {{ report.output_events }} output events · {{ report.from }} →
              {{ report.to }}
            </p>
            <Table
              :rows="report.products"
              :columns="['sku', 'name', 'unit', 'good_qty', 'reject_qty']"
          /></template></section
      ></template>
      <section v-if="tab === 'Reports' && health" class="panel">
        <h2>Device health and inbox</h2>
        <Table
          :rows="health.devices"
          :columns="['code', 'kind', 'active', 'last_seen', 'last_applied']"
        /><Table :rows="health.eventStates" :columns="['state', 'count']" />
      </section>
      <template v-if="tab === 'AI'"
        ><section class="panel">
          <h2>AI report assistance</h2>
          <p>
            {{
              aiData.enabled
                ? "Provider enabled. Running a draft sends the displayed production aggregate to the configured AI model."
                : "Provider is disabled. Draft snapshots work locally; enable the provider on the server to obtain AI suggestions."
            }}
          </p>
          <button :disabled="busy" @click="prepareAi">
            Prepare draft snapshot</button
          ><label
            >Existing draft<select
              @change="
                job =
                  aiData.jobs.find(
                    (r: Row) =>
                      r.id === ($event.target as HTMLSelectElement).value,
                  ) ?? null
              "
            >
              <option value="">Select draft</option>
              <option v-for="row in aiData.jobs" :key="row.id" :value="row.id">
                {{ row.id }} · {{ row.state }} · {{ row.created_by }}
              </option>
            </select></label
          ><Table
            :rows="aiData.jobs"
            :columns="[
              'id',
              'created_by',
              'state',
              'attempts',
              'model',
              'error_code',
              'review',
              'reviewed_by',
            ]"
          />
        </section>
        <section v-if="job" class="panel">
          <h2>Draft {{ job.id }} · {{ job.state }}</h2>
          <p>
            Prepared range {{ decoded(job.input_snapshot).from }} →
            {{ decoded(job.input_snapshot).to }} ·
            {{ decoded(job.input_snapshot).output_events }} events
          </p>
          <Table
            :rows="decoded(job.input_snapshot).products"
            :columns="['sku', 'name', 'unit', 'good_qty', 'reject_qty']"
          /><button
            :disabled="
              busy ||
              !aiData.enabled ||
              !['QUEUED', 'FAILED'].includes(job.state)
            "
            @click="aiAction('run')"
          >
            Send snapshot to AI provider</button
          ><button
            v-if="job.state === 'RUNNING'"
            :disabled="busy"
            @click="aiAction('recover')"
          >
            Recover interrupted job (after 2 minutes)</button
          ><template v-if="job.output"
            ><h3>{{ decoded(job.output).summary }}</h3>
            <Table
              :rows="decoded(job.output).suggestions"
              :columns="['observation', 'recommendation']"
            />
            <p>
              Acceptance records review only. Apply any approved business change
              through its normal workflow.
            </p>
            <template v-if="view === 'integration' && !job.review"
              ><label
                >Review notes<input v-model="reviewNotes" required /></label
              ><button
                :disabled="busy || !reviewNotes"
                @click="
                  aiAction('review', { accepted: true, notes: reviewNotes })
                "
              >
                Accept suggestion</button
              ><button
                :disabled="busy || !reviewNotes"
                @click="
                  aiAction('review', { accepted: false, notes: reviewNotes })
                "
              >
                Reject suggestion
              </button></template
            ></template
          >
        </section></template
      >
      <template v-if="tab === 'Portal' && data.partner"
        ><section class="panel">
          <h2>{{ data.partner.name }} · {{ data.partner.kind }}</h2>
          <h3>Published catalog</h3>
          <Table
            :rows="rows('products')"
            :columns="['sku', 'name', 'kind', 'unit']"
          />
        </section>
        <section class="panel">
          <h2>Submit a request</h2>
          <form @submit.prevent="submitPortal">
            <label
              >Request type<select v-model="requestKind">
                <option v-if="data.partner.kind === 'CUSTOMER'">
                  SALES_REQUEST
                </option>
                <option v-else>SUPPLY_OFFER</option>
                <option>DELIVERY_NOTICE</option>
              </select></label
            ><label
              >Your unique reference<input
                v-model="reference"
                maxlength="80"
                required /></label
            ><label
              >Description / delivery evidence<textarea
                v-model="description"
                maxlength="1000"
                required
              />
            </label>
            <h3>Products (optional for delivery notices)</h3>
            <div v-for="(line, i) in lines" :key="i" class="inline">
              <select
                v-model="line.productId"
                aria-label="Product"
                :required="requestKind !== 'DELIVERY_NOTICE'"
              >
                <option value="">Product</option>
                <option
                  v-for="row in rows('products')"
                  :value="row.id"
                  :key="row.id"
                >
                  {{ row.sku }} · {{ row.name }} ({{ row.unit }})
                </option></select
              ><input
                v-model="line.quantity"
                type="number"
                step="any"
                min="0.000001"
                aria-label="Quantity"
                required
              /><button type="button" @click="lines.splice(i, 1)">
                Remove
              </button>
            </div>
            <button
              type="button"
              @click="lines.push({ productId: '', quantity: '1' })"
            >
              Add product</button
            ><button class="primary" :disabled="busy">Submit for review</button>
          </form>
        </section>
        <section class="panel">
          <h2>Your requests</h2>
          <Table
            :rows="rows('requests')"
            :columns="[
              'id',
              'kind',
              'reference',
              'description',
              'state',
              'reply',
              'created_at',
            ]"
          />
        </section>
        <section class="panel">
          <h2>Your posted invoices</h2>
          <Table
            :rows="rows('invoices')"
            :columns="[
              'code',
              'currency',
              'net',
              'tax',
              'total',
              'outstanding',
              'due_on',
            ]"
          /></section
      ></template>
      <footer>
        {{
          view === "portal"
            ? "Acknowledgement does not confirm pricing, stock reservation or truck booking."
            : "HTTPS device-agent API with optional vendor bridges. Reports and AI cannot control equipment or approve payroll."
        }}
        Lists show up to 200 records.
      </footer>
    </main>
  </div>
</template>
