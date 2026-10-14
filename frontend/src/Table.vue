<script setup lang="ts">
export type Row = Record<string, unknown>;
defineProps<{ rows: Row[]; columns: string[] }>();
function display(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "boolean") return v ? "Yes" : "No";
  return String(v);
}
</script>
<template>
  <div class="table-scroll">
    <table>
      <thead>
        <tr>
          <th v-for="c in columns" :key="c">{{ c.replaceAll("_", " ") }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in rows" :key="String(row.id ?? i)">
          <td v-for="c in columns" :key="c">
            <span :class="c === 'state' || c === 'quality' ? 'status' : ''">{{
              display(row[c])
            }}</span>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="columns.length">No records yet.</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
