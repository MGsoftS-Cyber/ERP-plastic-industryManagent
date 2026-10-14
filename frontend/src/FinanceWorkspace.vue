<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Table from "./Table.vue";
import { actions, tables } from "./finance-config";
type Row = Record<string, any>;
const props = defineProps<{
  request: (
    path: string,
    method?: string,
    body?: unknown,
    responseType?: "json" | "text",
  ) => Promise<any>;
  username: string;
  recyclingOnly: boolean;
}>();
defineEmits<{ logout: [] }>();
const tabs = props.recyclingOnly
  ? ["Recycling"]
  : ["Accounting", "Invoices", "Expenses", "Costing"];
const tab = ref(tabs[0]),
  selected = ref(0),
  data = ref<Record<string, Row[]>>({}),
  form = ref<Record<string, string>>({}),
  error = ref(""),
  message = ref(""),
  busy = ref(false),
  eventId = ref(crypto.randomUUID());
const journalLines = ref([
  { accountId: "", debit: "0", credit: "0" },
  { accountId: "", debit: "0", credit: "0" },
]);
const allocations = ref<{ kind: string; sourceId: string; amount: string }[]>(
  [],
);
const inspected = ref<Row | null>(null),
  trial = ref<Row[]>([]),
  payLines = ref<Row[]>([]),
  payPeriod = ref("");
const action = computed(() => actions[tab.value][selected.value]);
const rows = (source: string) => data.value[source] ?? [];
const label = (row: Row) =>
  `${row.code ?? row.name ?? row.reference ?? row.id} ${row.state ?? row.kind ?? ""}`;
function reset() {
  form.value = {};
  error.value = "";
  message.value = "";
  eventId.value = crypto.randomUUID();
  allocations.value = [];
  journalLines.value = [
    { accountId: "", debit: "0", credit: "0" },
    { accountId: "", debit: "0", credit: "0" },
  ];
}
function changeTab(value: string) {
  tab.value = value;
  selected.value = 0;
  inspected.value = null;
  reset();
}
async function refresh() {
  try {
    data.value = await props.request(
      "/api/v1/finance/" +
        (props.recyclingOnly ? "recycling-overview" : "overview"),
    );
  } catch (e) {
    error.value = String(e);
  }
}
onMounted(refresh);
async function submit() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    let path = action.value.path;
    const body: Row = {};
    for (const field of action.value.fields) {
      const value = form.value[field.key] ?? "";
      if (path.includes("{" + field.key + "}")) {
        path = path.replace("{" + field.key + "}", encodeURIComponent(value));
        continue;
      }
      body[field.key] =
        field.type === "number"
          ? Number(value)
          : !value && field.optional
            ? null
            : value;
    }
    if (action.value.kind === "journal") {
      body.eventId = eventId.value;
      body.lines = journalLines.value.map((l) => ({
        accountId: l.accountId,
        debit: Number(l.debit),
        credit: Number(l.credit),
      }));
    }
    if (action.value.kind === "event") body.eventId = eventId.value;
    if (action.value.kind === "cost")
      body.allocations = allocations.value.map((a) => ({
        ...a,
        amount: Number(a.amount),
      }));
    const result = await props.request(
      "/api/v1/finance" + path,
      "POST",
      Object.keys(body).length ? body : undefined,
    );
    await refresh();
    message.value =
      action.value.title +
      " completed." +
      (result?.outputLotId
        ? " Recovered lot " + result.outputLotId + " awaits quality approval."
        : "");
    eventId.value = crypto.randomUUID();
    if (result?.journal) inspected.value = result;
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function inspect(path: string) {
  try {
    inspected.value = await props.request("/api/v1/finance" + path);
  } catch (e) {
    error.value = String(e);
  }
}
async function loadTrial(id: string) {
  trialPeriod.value = id;
  if (!id) return;
  try {
    trial.value = await props.request(
      "/api/v1/finance/periods/" + id + "/trial-balance",
    );
  } catch (e) {
    error.value = String(e);
  }
}
async function loadPay() {
  if (!payPeriod.value) return;
  try {
    payLines.value = await props.request(
      "/api/v1/finance/payroll/" + payPeriod.value + "/lines",
    );
  } catch (e) {
    error.value = String(e);
  }
}
const trialPeriod = ref("");
async function exportTrial() {
  if (!trialPeriod.value) return;
  try {
    const csv = await props.request(
      "/api/v1/integration/reports/financial/" + trialPeriod.value + ".csv",
      "GET",
      undefined,
      "text",
    );
    const url = URL.createObjectURL(
      new Blob([csv], { type: "text/csv;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "financial.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = String(e);
  }
}
const debits = computed(() =>
  journalLines.value.reduce((sum, l) => sum + Number(l.debit || 0), 0),
);
const credits = computed(() =>
  journalLines.value.reduce((sum, l) => sum + Number(l.credit || 0), 0),
);
</script>
<template>
  <div class="shell">
    <aside>
      <h2>PlastiFlow</h2>
      <small>{{
        recyclingOnly ? "Material recovery" : "Finance and costing"
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
          <small>FINANCE</small>
          <h1>{{ tab }}</h1>
        </div>
        <button @click="refresh">Refresh records</button>
      </header>
      <p class="notice">
        {{ username }} · Posted journals and cost snapshots are immutable.
        Settlements record evidence; they do not transfer money.
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <section class="panel">
        <h2>Actions</h2>
        <select v-model="selected" @change="reset">
          <option v-for="(a, i) in actions[tab]" :value="i" :key="a.title">
            {{ a.title }}
          </option>
        </select>
        <p>Required role: {{ action.role }}</p>
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
                <option v-for="opt in field.options" :key="opt">
                  {{ opt }}
                </option></select
              ><input
                v-else
                v-model="form[field.key]"
                :type="field.type ?? 'text'"
                :step="field.type === 'number' ? 'any' : undefined"
                :required="!field.optional"
            /></label>
          </div>
          <template v-if="action.kind === 'journal'"
            ><h3>Debit and credit lines</h3>
            <div v-for="(line, i) in journalLines" :key="i" class="inline">
              <select v-model="line.accountId" required aria-label="Account">
                <option value="">Account</option>
                <option
                  v-for="row in rows('account')"
                  :value="row.id"
                  :key="row.id"
                >
                  {{ label(row) }}
                </option></select
              ><label
                >Debit<input
                  v-model="line.debit"
                  type="number"
                  min="0"
                  step="0.01"
                  required /></label
              ><label
                >Credit<input
                  v-model="line.credit"
                  type="number"
                  min="0"
                  step="0.01"
                  required /></label
              ><button type="button" @click="journalLines.splice(i, 1)">
                Remove
              </button>
            </div>
            <button
              type="button"
              @click="
                journalLines.push({ accountId: '', debit: '0', credit: '0' })
              "
            >
              Add line
            </button>
            <p>
              Debit {{ debits.toFixed(2) }} · Credit {{ credits.toFixed(2) }} ·
              Difference {{ (debits - credits).toFixed(2) }}
            </p></template
          >
          <template v-if="action.kind === 'cost'"
            ><h3>Traceable cost allocations (optional)</h3>
            <p>
              Materials use actual consumption and recorded lot valuations.
              These allocations add posted expenses or finalized gross payroll
              to the analytic cost; they create no accounting entry.
            </p>
            <label
              >Finalized payroll period<select
                v-model="payPeriod"
                @change="loadPay"
              >
                <option value="">
                  Choose to look up pay-line IDs (accountant only)
                </option>
                <option
                  v-for="row in rows('payPeriods')"
                  :key="row.id"
                  :value="row.id"
                >
                  {{ label(row) }}
                </option>
              </select></label
            ><Table
              :rows="payLines"
              :columns="['id', 'employee_id', 'gross', 'payable', 'deduction']"
            />
            <div v-for="(allocation, i) in allocations" :key="i" class="inline">
              <select
                v-model="allocation.kind"
                aria-label="Allocation source type"
              >
                <option>EXPENSE</option>
                <option>PAYROLL</option></select
              ><select
                v-if="allocation.kind === 'EXPENSE'"
                v-model="allocation.sourceId"
                aria-label="Expense"
                required
              >
                <option value="">Posted expense</option>
                <option
                  v-for="row in rows('expense').filter(
                    (r) => r.state === 'POSTED',
                  )"
                  :value="row.id"
                  :key="row.id"
                >
                  {{ label(row) }}
                </option></select
              ><input
                v-else
                v-model="allocation.sourceId"
                placeholder="Finalized pay-line UUID"
                required
                aria-label="Payroll source"
              /><input
                v-model="allocation.amount"
                type="number"
                min="0.01"
                step="0.01"
                aria-label="Allocation amount"
                required
              /><button type="button" @click="allocations.splice(i, 1)">
                Remove
              </button>
            </div>
            <button
              type="button"
              @click="
                allocations.push({ kind: 'EXPENSE', sourceId: '', amount: '0' })
              "
            >
              Add allocation
            </button></template
          >
          <p v-if="action.kind === 'event' || action.kind === 'journal'">
            A retry keeps the same event identifier until a request succeeds.
          </p>
          <button class="primary" :disabled="busy">
            {{ busy ? "Processing…" : action.title }}
          </button>
        </form>
      </section>
      <section v-for="table in tables[tab]" :key="table.source" class="panel">
        <h2>{{ table.source.replaceAll("_", " ") }}</h2>
        <Table :rows="rows(table.source)" :columns="table.columns" />
      </section>
      <section v-if="tab === 'Accounting'" class="panel">
        <h2>Trial balance · through selected period end</h2>
        <select @change="loadTrial(($event.target as HTMLSelectElement).value)">
          <option value="">Accounting period</option>
          <option v-for="row in rows('period')" :value="row.id" :key="row.id">
            {{ label(row) }}
          </option></select
        ><button :disabled="!trialPeriod" @click="exportTrial">
          Download financial CSV</button
        ><Table
          :rows="trial"
          :columns="['code', 'name', 'kind', 'debit', 'credit', 'balance']"
        />
        <h3>Journal details</h3>
        <select
          @change="
            inspect('/journals/' + ($event.target as HTMLSelectElement).value)
          "
        >
          <option value="">Choose journal</option>
          <option v-for="row in rows('journal')" :key="row.id" :value="row.id">
            {{ label(row) }}
          </option></select
        ><template v-if="inspected?.lines"
          ><p>
            {{ inspected.journal.reference }} ·
            {{ inspected.journal.currency }} ·
            {{ inspected.journal.description }}
          </p>
          <Table
            :rows="inspected.lines"
            :columns="['code', 'name', 'debit', 'credit']"
        /></template>
      </section>
      <section v-if="tab === 'Costing'" class="panel">
        <h2>Cost evidence</h2>
        <label
          >Cost snapshot<select
            @change="
              inspected =
                rows('cost').find(
                  (r) => r.id === ($event.target as HTMLSelectElement).value,
                ) ?? null
            "
          >
            <option value="">Choose snapshot</option>
            <option v-for="row in rows('cost')" :value="row.id" :key="row.id">
              {{ row.order_id }}
            </option>
          </select></label
        ><template v-if="inspected?.snapshot"
          ><p>
            Accepted units: {{ inspected.output_qty }} · Total:
            {{ inspected.total }} {{ inspected.currency }}
          </p>
          <Table
            :rows="JSON.parse(inspected.snapshot).materials"
            :columns="[
              'lotId',
              'kg',
              'unitCost',
              'unroundedCost',
              'evidence',
            ]" /><Table
            :rows="JSON.parse(inspected.snapshot).allocations"
            :columns="['kind', 'sourceId', 'amount']"
        /></template>
      </section>
      <section v-if="tab === 'Recycling'" class="panel">
        <h2>Recovered-lot provenance</h2>
        <select
          @change="
            inspect(
              '/recovery-trace/' + ($event.target as HTMLSelectElement).value,
            )
          "
        >
          <option value="">Recovered lot</option>
          <option
            v-for="row in rows('recovery')"
            :value="row.output_lot_id"
            :key="row.id"
          >
            {{ row.output_lot_id }}
          </option></select
        ><template v-if="inspected?.recovery"
          ><Table
            :rows="inspected.ancestors"
            :columns="[
              'source_lot_id',
              'source_lot_code',
              'supplier_reference',
              'classification',
              'input_kg',
              'recovered_kg',
              'loss_kg',
              'disposition_reference',
            ]"
          />
          <p>
            Output lot {{ inspected.outputLot.code }} · Quality
            {{ inspected.outputLot.quality }}
          </p></template
        >
      </section>
      <footer>
        Two-decimal currencies only. Tax amounts are entered explicitly.
        Country-specific taxes, credit notes, bank feeds, and automatic
        inventory valuation remain future integrations. Lists show up to 200
        records.
      </footer>
    </main>
  </div>
</template>
