<script setup lang="ts">
import { onMounted, ref } from "vue";
const props = defineProps<{
  request: (path: string, method?: string, body?: unknown) => Promise<any>;
  readOnly: boolean;
}>();
defineEmits<{ logout: [] }>();
const data = ref<any>({ items: [], page: 0, totalPages: 0, totalItems: 0 }),
  page = ref(0),
  query = ref(""),
  kind = ref(""),
  error = ref(""),
  message = ref(""),
  busy = ref(false);
const kinds = ["RAW_MATERIAL", "FINISHED_GOOD", "PACKAGING", "SPARE_PART"],
  units = ["KG", "PIECE", "METER", "LITER"];
const form = ref({ sku: "", name: "", kind: "RAW_MATERIAL", unit: "KG" }),
  edit = ref<any | null>(null);
async function refresh() {
  error.value = "";
  try {
    const params = new URLSearchParams({
      page: String(page.value),
      size: "20",
      q: query.value,
    });
    if (kind.value) params.set("kind", kind.value);
    data.value = await props.request("/api/v1/products?" + params);
  } catch (e) {
    error.value = String(e);
  }
}
async function create() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await props.request("/api/v1/products", "POST", form.value);
    form.value = { sku: "", name: "", kind: "RAW_MATERIAL", unit: "KG" };
    page.value = 0;
    await refresh();
    message.value = "Product created.";
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
async function update() {
  if (!edit.value) return;
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await props.request("/api/v1/products/" + edit.value.id, "PUT", {
      version: edit.value.version,
      name: edit.value.name,
      active: edit.value.active,
    });
    edit.value = null;
    await refresh();
    message.value = "Product updated.";
  } catch (e) {
    error.value = String(e);
  } finally {
    busy.value = false;
  }
}
onMounted(refresh);
</script>
<template>
  <div class="shell">
    <aside>
      <h2>PlastiFlow</h2>
      <small>Product catalog</small
      ><button @click="$emit('logout')">Sign out / switch role</button>
    </aside>
    <main>
      <header>
        <div>
          <small>PRODUCT MASTER DATA</small>
          <h1>Catalog</h1>
        </div>
        <button @click="refresh">Refresh records</button>
      </header>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <section class="panel">
        <h2>Find products</h2>
        <form
          @submit.prevent="
            page = 0;
            refresh();
          "
        >
          <div class="fields">
            <label>SKU or name<input v-model="query" /></label
            ><label
              >Product kind<select v-model="kind">
                <option value="">All kinds</option>
                <option v-for="k in kinds" :key="k">{{ k }}</option>
              </select></label
            >
          </div>
          <button>Search</button>
        </form>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>SKU</th>
                <th>Name</th>
                <th>Kind</th>
                <th>Unit</th>
                <th>Active</th>
                <th v-if="!readOnly">Action</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in data.items" :key="row.id">
                <td>{{ row.sku }}</td>
                <td>{{ row.name }}</td>
                <td>{{ row.kind }}</td>
                <td>{{ row.unit }}</td>
                <td>{{ row.active ? "Yes" : "No" }}</td>
                <td v-if="!readOnly">
                  <button @click="edit = { ...row }">Edit {{ row.sku }}</button>
                </td>
              </tr>
              <tr v-if="!data.items.length">
                <td colspan="6">No matching products.</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          {{ data.totalItems }} products · Page {{ page + 1 }} of
          {{ Math.max(1, data.totalPages) }}
        </p>
        <button
          :disabled="page === 0"
          @click="
            page--;
            refresh();
          "
        >
          Previous</button
        ><button
          :disabled="page + 1 >= data.totalPages"
          @click="
            page++;
            refresh();
          "
        >
          Next
        </button>
      </section>
      <section v-if="!readOnly" class="panel">
        <h2>Create product</h2>
        <form @submit.prevent="create">
          <div class="fields">
            <label
              >SKU<input
                v-model="form.sku"
                maxlength="40"
                pattern="[A-Z0-9][A-Z0-9._-]*"
                required /></label
            ><label
              >Product name<input
                v-model="form.name"
                maxlength="160"
                required /></label
            ><label
              >Kind<select v-model="form.kind">
                <option v-for="k in kinds" :key="k">{{ k }}</option>
              </select></label
            ><label
              >Base unit<select v-model="form.unit">
                <option v-for="u in units" :key="u">{{ u }}</option>
              </select></label
            >
          </div>
          <button class="primary" :disabled="busy">Create product</button>
        </form>
      </section>
      <section v-if="edit && !readOnly" class="panel">
        <h2>Edit {{ edit.sku }}</h2>
        <form @submit.prevent="update">
          <label
            >Name<input v-model="edit.name" maxlength="160" required /></label
          ><label
            >Availability<select v-model="edit.active">
              <option :value="true">Active</option>
              <option :value="false">Inactive</option>
            </select></label
          >
          <p>
            SKU, kind and unit stay fixed so existing records retain their
            meaning.
          </p>
          <button :disabled="busy">Save product</button
          ><button type="button" @click="edit = null">Cancel</button>
        </form>
      </section>
      <footer>
        Catalog records describe products. Stock receipts, reservations and the
        full warehouse ledger remain separate workflows.
      </footer>
    </main>
  </div>
</template>
