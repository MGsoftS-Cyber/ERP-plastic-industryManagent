export type Field = {
  key: string;
  label: string;
  source?: string;
  options?: string[];
  optional?: boolean;
  type?: string;
};
export type Action = {
  title: string;
  path: string;
  fields: Field[];
  method?: string;
  kind?: string;
  hint?: string;
};
const f = (key: string, label: string, source?: string): Field => ({
  key,
  label,
  source,
});
const o = (key: string, label: string, options: string[]): Field => ({
  key,
  label,
  options,
});
export const actions: Record<string, Action[]> = {
  Devices: [
    {
      title: "Register device",
      path: "/devices",
      fields: [
        f("code", "Unique code"),
        f("name", "Name"),
        o("kind", "Device type", ["ATTENDANCE", "CAMERA", "SENSOR", "SCALE"]),
        f("principal", "Dedicated identity-provider subject (demo: device)"),
        {
          ...f("targetId", "Camera or machine UUID (only camera/sensor)"),
          optional: true,
        },
        { ...f("metric", "Sensor metric"), optional: true },
        { ...f("unit", "Sensor unit"), optional: true },
      ],
      hint: "Attendance uses per-person bindings; scales use per-visit bindings. Only one active agent can update each camera.",
    },
    {
      title: "Bind attendance identity",
      path: "/devices/{id}/identities",
      fields: [
        f("id", "Attendance device", "device"),
        f("externalPerson", "Device person identifier (no biometric template)"),
        f("employeeId", "Employee", "employees"),
      ],
    },
    {
      title: "Bind scale visit",
      path: "/devices/{id}/visits",
      fields: [
        f("id", "Scale device", "device"),
        f("visitId", "Truck visit", "visits"),
      ],
    },
    {
      title: "Apply or retry device event",
      path: "/events/{id}/apply",
      fields: [f("id", "Inbox event", "event")],
      hint: "FAILED means domain validation blocked the event; correct the binding or business record before retrying. Raw clocks do not approve attendance.",
    },
    {
      title: "Revoke device",
      path: "/devices/{id}/revoke",
      fields: [f("id", "Device", "device")],
    },
  ],
  Partners: [
    {
      title: "Register partner",
      path: "/partners",
      fields: [
        f("code", "Unique code"),
        f("name", "Company name"),
        o("kind", "Partner type", ["CUSTOMER", "SUPPLIER"]),
        f("partyReference", "Exact party reference used by existing invoices"),
      ],
    },
    {
      title: "Bind portal account",
      path: "/partners/{id}/users",
      fields: [
        f("id", "Partner", "partner"),
        f(
          "principal",
          "Identity-provider subject (demo: customer, customer2, supplier)",
        ),
      ],
    },
    {
      title: "Revoke partner access",
      path: "/partners/{id}/revoke",
      fields: [f("id", "Partner", "partner")],
    },
    {
      title: "Revoke one portal account",
      path: "/partner-users/{principal}/revoke",
      fields: [f("principal", "Account subject")],
    },
    {
      title: "Publish catalog product",
      path: "/published-products/{id}",
      fields: [f("id", "Product", "products")],
    },
    {
      title: "Unpublish catalog product",
      path: "/published-products/{id}",
      method: "DELETE",
      fields: [f("id", "Published product", "published")],
    },
    {
      title: "Decide partner request",
      path: "/portal-requests/{id}/decision",
      fields: [
        f("id", "Request", "portal_request"),
        o("accepted", "Decision", ["true", "false"]),
        f("notes", "Reply visible to the partner"),
      ],
      hint: "Acknowledgement records the review; it does not reserve stock, create a sale or schedule a truck.",
    },
  ],
};
export const tables: Record<string, { source: string; columns: string[] }[]> = {
  Devices: [
    {
      source: "device",
      columns: [
        "id",
        "code",
        "kind",
        "principal",
        "target_id",
        "active",
        "last_seen",
        "last_applied",
      ],
    },
    {
      source: "identity",
      columns: ["device_id", "external_person", "employee_id"],
    },
    { source: "scale_visit", columns: ["device_id", "visit_id"] },
    {
      source: "event",
      columns: [
        "id",
        "device_id",
        "sequence_no",
        "occurred_at",
        "state",
        "attempts",
        "error_code",
      ],
    },
  ],
  Partners: [
    {
      source: "partner",
      columns: ["id", "code", "name", "kind", "party_reference", "active"],
    },
    { source: "partner_user", columns: ["principal", "partner_id", "active"] },
    { source: "published", columns: ["id", "sku", "name"] },
    {
      source: "portal_request",
      columns: [
        "id",
        "partner_id",
        "kind",
        "reference",
        "description",
        "state",
        "reply",
      ],
    },
  ],
};
