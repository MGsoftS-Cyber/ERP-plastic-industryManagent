<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Table from "./Table.vue";
import { operationsActions, operationsTables } from "./ops-config";
type Row = Record<string, any>;
type Field = {
  key: string;
  label: string;
  source?: string;
  type?: string;
  options?: string[];
  optional?: boolean;
};
type Action = {
  title: string;
  method: string;
  path: string;
  fields: Field[];
  event: boolean;
};
const props = defineProps<{
  request: (path: string, method?: string, body?: unknown) => Promise<any>;
  initialSection: string;
  username: string;
}>();
const emit = defineEmits<{ logout: [] }>();
const section = ref(props.initialSection),
  data = ref<Record<string, Row[]>>({}),
  products = ref<Row[]>([]),
  form = ref<Row>({}),
  selected = ref(0),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  detail = ref<Row | null>(null),
  eventId = ref(crypto.randomUUID());
const names: Record<string, string> = {
  maintenance: "Maintenance",
  facilities: "Facilities",
  yard: "Truck Yard",
  security: "Physical Access",
};
const actionSets = operationsActions as Record<string, Action[]>;
const tableSets = operationsTables as Record<
  string,
  { source: string; columns: string[] }[]
>;
const action = computed(
  () =>
    actionSets[section.value]![selected.value] ??
    actionSets[section.value]![0]!,
);
const accountMenus: Record<string, string[]> = {
  maintenance: ["maintenance"],
  technician: ["maintenance"],
  facilities: ["facilities"],
  logistics: ["yard"],
  security: ["security"],
  gate: ["yard", "security"],
};
const menus = computed(
  () =>
    accountMenus[props.username] ?? [
      "maintenance",
      "facilities",
      "yard",
      "security",
    ],
);
function rows(source: string): Row[] {
  if (source === "spares")
    return products.value.filter((p) => p.kind === "SPARE_PART" && p.active);
  if (source === "docks")
    return (data.value.spaces ?? []).filter((s) => s.kind === "DOCK");
  return data.value[source] ?? [];
}
function identifier(row: Row): string {
  return String(row.id ?? row.product_id ?? row.code);
}
function label(row: Row): string {
  return (
    String(row.code ?? row.reference ?? row.name ?? row.id) +
    (row.state ? " · " + row.state : "")
  );
}
async function refresh() {
  data.value = await props.request(
    "/api/v1/operations/" + section.value + "/overview",
  );
  products.value = (await props.request("/api/v1/products?size=100")).items;
}
async function change(s: string) {
  section.value = s;
  selected.value = 0;
  reset();
  try {
    await refresh();
  } catch (e) {
    error.value = String(e);
  }
}
function reset() {
  form.value = {};
  eventId.value = crypto.randomUUID();
  message.value = "";
  error.value = "";
  detail.value = null;
}
onMounted(() => refresh().catch((e) => (error.value = String(e))));
async function submit() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    let path = action.value.path;
    const body: Row = {};
    for (const field of action.value.fields) {
      const value = form.value[field.key];
      if (path.includes("{" + field.key + "}")) {
        path = path.replace(
          "{" + field.key + "}",
          encodeURIComponent(String(value)),
        );
        continue;
      }
      body[field.key] =
        field.type === "multiple"
          ? value
          : field.type === "number"
            ? Number(value)
            : field.type === "boolean"
              ? value === "true"
              : field.type === "datetime-local"
                ? new Date(value).toISOString()
                : !value && field.optional
                  ? null
                  : value;
    }
    if (action.value.event) body.eventId = eventId.value;
    await props.request(
      "/api/v1/operations" + path,
      action.value.method,
      Object.keys(body).length ? body : undefined,
    );
    await refresh();
    message.value = action.value.title + " completed.";
    eventId.value = crypto.randomUUID();
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function loadDetail(id: string) {
  try {
    const resource =
      section.value === "maintenance"
        ? "work-orders"
        : section.value === "yard"
          ? "truck-visits"
          : "visitors";
    detail.value = await props.request(
      "/api/v1/operations/" + resource + "/" + id,
    );
  } catch (e) {
    error.value = String(e);
  }
}
const detailSource = computed(() =>
  section.value === "maintenance"
    ? "workOrders"
    : section.value === "yard"
      ? "trucks"
      : "visitors",
);
function occupant(space: Row): Row | undefined {
  return rows("trucks").find(
    (t) =>
      t.space_id === space.id &&
      ["WAITING", "AT_DOCK", "HANDLING", "READY_EXIT"].includes(t.state),
  );
}
</script>
<template>
  <div class="shell">
    <aside>
      <h2>PlastiFlow</h2>
      <small>Factory operations</small>
      <nav>
        <button
          v-for="s in menus"
          :key="s"
          :class="{ active: section === s }"
          @click="change(s)"
        >
          {{ names[s] }}
        </button>
      </nav>
      <button @click="emit('logout')">Sign out / switch role</button>
    </aside>
    <main>
      <header>
        <div>
          <small>FACTORY OPERATIONS</small>
          <h1>{{ names[section] }}</h1>
        </div>
        <button @click="refresh().catch((e) => (error = String(e)))">
          Refresh
        </button>
      </header>
      <p class="notice">
        {{ username }} account · permissions enforced by the API · dates entered
        in browser timezone
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <section v-if="section === 'yard'" class="panel">
        <h2>Parking bays and docks</h2>
        <div class="space-grid">
          <article
            v-for="s in rows('spaces')"
            :key="s.id"
            :class="['space-card', { occupied: occupant(s) }]"
          >
            <strong>{{ s.code }}</strong>
            <p>{{ s.kind }}</p>
            <span>{{ occupant(s)?.reference ?? "Free" }}</span
            ><small>{{ occupant(s)?.state ?? "Available" }}</small>
          </article>
        </div>
      </section>
      <section class="panel">
        <h2>Actions</h2>
        <select v-model="selected" @change="reset">
          <option
            v-for="(a, i) in actionSets[section]"
            :key="a.title"
            :value="i"
          >
            {{ a.title }}
          </option>
        </select>
        <p>
          Use the authorized manager or assigned technician account. Restricted
          actions return an access error.
        </p>
        <form @submit.prevent="submit">
          <div class="fields">
            <label v-for="field in action.fields" :key="field.key"
              >{{ field.label
              }}<select
                v-if="field.source"
                v-model="form[field.key]"
                :multiple="field.type === 'multiple'"
                :required="!field.optional"
              >
                <option v-if="field.type !== 'multiple'" value="">
                  Select {{ field.optional ? "(optional)" : "" }}
                </option>
                <option
                  v-for="row in rows(field.source)"
                  :key="identifier(row)"
                  :value="identifier(row)"
                >
                  {{ label(row) }}
                </option></select
              ><select
                v-else-if="field.type === 'boolean'"
                v-model="form[field.key]"
                required
              >
                <option value="">Select</option>
                <option value="true">Yes</option>
                <option value="false">No</option></select
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
                :step="
                  field.type === 'number'
                    ? 'any'
                    : field.type === 'datetime-local'
                      ? 1
                      : undefined
                "
                :required="!field.optional"
            /></label>
          </div>
          <p v-if="action.event">
            Exact retries preserve this submission's event identifier.
          </p>
          <button class="primary" :disabled="busy">
            {{ busy ? "Processing…" : action.title }}
          </button>
        </form>
      </section>
      <section v-for="t in tableSets[section]" :key="t.source" class="panel">
        <h2>{{ t.source }}</h2>
        <Table :rows="rows(t.source)" :columns="t.columns" />
      </section>
      <section
        v-if="['maintenance', 'yard', 'security'].includes(section)"
        class="panel"
      >
        <h2>Record details and history</h2>
        <select
          @change="loadDetail(($event.target as HTMLSelectElement).value)"
        >
          <option value="">Choose record</option>
          <option v-for="r in rows(detailSource)" :key="r.id" :value="r.id">
            {{ label(r) }}
          </option></select
        ><template v-if="detail && section === 'maintenance'"
          ><h3>Repair notes and cost</h3>
          <p>{{ detail.repair_notes ?? "Not submitted" }}</p>
          <p>{{ detail.release_notes ?? "Awaiting approval" }}</p>
          <strong>{{ detail.total_cost }} {{ detail.currency }}</strong
          ><Table
            :rows="detail.parts"
            :columns="[
              'product_id',
              'quantity',
              'unit_cost',
              'currency',
            ]" /><Table
            :rows="detail.labor"
            :columns="['hours', 'hourly_rate', 'created_at']" /></template
        ><template v-if="detail && section === 'yard'"
          ><h3>
            Visit history · net load {{ detail.net_kg ?? "not available" }} kg
          </h3>
          <Table
            :rows="detail.weights"
            :columns="['kind', 'kg', 'measured_at']" /><Table
            :rows="detail.events"
            :columns="['state', 'notes', 'actor', 'created_at']" /></template
        ><template v-if="detail && section === 'security'"
          ><h3>Approved zones</h3>
          <Table :rows="detail.zones" :columns="['code', 'name']" />
          <h3>Access history</h3>
          <Table
            :rows="detail.access_events"
            :columns="['zone_id', 'direction', 'actor', 'created_at']"
        /></template>
      </section>
      <footer>
        Records and approvals only. No physical gates, cameras or machine safety
        controls are operated. Lists are bounded to 200 records.
      </footer>
    </main>
  </div>
</template>
<style scoped>
.space-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}
.space-card {
  padding: 18px;
  border: 1px solid #c9d9d0;
  background: #eff7f2;
  border-radius: 6px;
}
.space-card.occupied {
  background: #f9eee0;
  border-color: #e2caab;
}
.space-card small {
  display: block;
  margin-top: 10px;
}
</style>
