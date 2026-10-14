export const operationsActions = {
  maintenance: [
    {
      title: "Register technician",
      method: "POST",
      path: "/technicians",
      fields: [
        {
          key: "code",
          label: "Technician code",
        },
        {
          key: "name",
          label: "Name",
        },
        {
          key: "userSubject",
          label: "Identity subject / demo username",
        },
      ],
      event: false,
    },
    {
      title: "Create work order",
      method: "POST",
      path: "/work-orders",
      fields: [
        {
          key: "code",
          label: "Work-order code",
        },
        {
          key: "machineId",
          label: "Machine",
          source: "machines",
          optional: true,
        },
        {
          key: "assetId",
          label: "Facility asset",
          source: "assets",
          optional: true,
        },
        {
          key: "kind",
          label: "Work kind",
          options: ["BREAKDOWN", "PREVENTIVE", "FACILITY"],
        },
        {
          key: "description",
          label: "Reported problem / work instructions",
        },
        {
          key: "currency",
          label: "Cost currency (e.g. DZD)",
        },
      ],
      event: false,
    },
    {
      title: "Assign technician",
      method: "PUT",
      path: "/work-orders/{id}/assignment",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "technicianId",
          label: "Technician",
          source: "technicians",
        },
      ],
      event: false,
    },
    {
      title: "Start repair",
      method: "POST",
      path: "/work-orders/{id}/start",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
      ],
      event: false,
    },
    {
      title: "Submit repair for review",
      method: "POST",
      path: "/work-orders/{id}/submit",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Approve return to service",
      method: "POST",
      path: "/work-orders/{id}/release",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Create preventive plan",
      method: "POST",
      path: "/maintenance-plans",
      fields: [
        {
          key: "machineId",
          label: "Machine",
          source: "machines",
        },
        {
          key: "intervalDays",
          label: "Interval in days",
          type: "number",
        },
        {
          key: "nextDue",
          label: "Next due date",
          type: "date",
        },
        {
          key: "instructions",
          label: "Maintenance instructions",
        },
        {
          key: "currency",
          label: "Currency",
        },
      ],
      event: false,
    },
    {
      title: "Generate due preventive work",
      method: "POST",
      path: "/maintenance-plans/{id}/generate",
      fields: [
        {
          key: "id",
          label: "Preventive plan",
          source: "plans",
        },
      ],
      event: false,
    },
    {
      title: "Book maintenance window",
      method: "POST",
      path: "/work-orders/{id}/windows",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "start",
          label: "Start",
          type: "datetime-local",
        },
        {
          key: "end",
          label: "End",
          type: "datetime-local",
        },
        {
          key: "reason",
          label: "Window reason",
        },
      ],
      event: false,
    },
    {
      title: "Receive spare parts",
      method: "POST",
      path: "/parts/receipts",
      fields: [
        {
          key: "productId",
          label: "Spare-part product",
          source: "spares",
        },
        {
          key: "quantity",
          label: "Quantity in product unit",
          type: "number",
        },
        {
          key: "unitCost",
          label: "Unit cost in work-order currency",
          type: "number",
        },
        {
          key: "currency",
          label: "Currency",
        },
      ],
      event: true,
    },
    {
      title: "Record spare usage",
      method: "POST",
      path: "/work-orders/{id}/parts",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "productId",
          label: "Spare-part product",
          source: "spares",
        },
        {
          key: "quantity",
          label: "Quantity in product unit",
          type: "number",
        },
        {
          key: "unitCost",
          label: "Unit cost in work-order currency",
          type: "number",
        },
      ],
      event: true,
    },
    {
      title: "Record labor",
      method: "POST",
      path: "/work-orders/{id}/labor",
      fields: [
        {
          key: "id",
          label: "Work order",
          source: "workOrders",
        },
        {
          key: "hours",
          label: "Repair hours",
          type: "number",
        },
        {
          key: "hourlyRate",
          label: "Hourly cost in work-order currency",
          type: "number",
        },
      ],
      event: true,
    },
  ],
  facilities: [
    {
      title: "Register area",
      method: "POST",
      path: "/areas",
      fields: [
        {
          key: "code",
          label: "Area code",
        },
        {
          key: "name",
          label: "Area name",
        },
      ],
      event: false,
    },
    {
      title: "Register infrastructure asset",
      method: "POST",
      path: "/assets",
      fields: [
        {
          key: "code",
          label: "Asset code",
        },
        {
          key: "areaId",
          label: "Area",
          source: "areas",
        },
        {
          key: "name",
          label: "Asset name",
        },
        {
          key: "kind",
          label: "Type (electrical, water, ventilation\u2026)",
        },
      ],
      event: false,
    },
    {
      title: "Record asset inspection",
      method: "POST",
      path: "/assets/{id}/inspections",
      fields: [
        {
          key: "id",
          label: "Asset",
          source: "assets",
        },
        {
          key: "passed",
          label: "Inspection passed?",
          type: "boolean",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Register service contract",
      method: "POST",
      path: "/service-contracts",
      fields: [
        {
          key: "assetId",
          label: "Asset",
          source: "assets",
        },
        {
          key: "supplierReference",
          label: "External supplier / contract reference",
        },
        {
          key: "startsOn",
          label: "Contract starts",
          type: "date",
        },
        {
          key: "endsOn",
          label: "Contract ends",
          type: "date",
        },
        {
          key: "amount",
          label: "Contract amount",
          type: "number",
        },
        {
          key: "currency",
          label: "Currency",
        },
      ],
      event: false,
    },
  ],
  yard: [
    {
      title: "Create parking bay or dock",
      method: "POST",
      path: "/yard/spaces",
      fields: [
        {
          key: "code",
          label: "Space code",
        },
        {
          key: "kind",
          label: "Space type",
          options: ["PARKING", "DOCK"],
        },
      ],
      event: false,
    },
    {
      title: "Create truck appointment",
      method: "POST",
      path: "/truck-visits",
      fields: [
        {
          key: "reference",
          label: "Visit reference",
        },
        {
          key: "carrier",
          label: "Carrier",
        },
        {
          key: "driver",
          label: "Driver name",
        },
        {
          key: "tractorPlate",
          label: "Tractor plate",
        },
        {
          key: "trailerPlate",
          label: "Trailer plate",
        },
        {
          key: "purpose",
          label: "Purpose",
          options: ["DELIVERY", "COLLECTION"],
        },
        {
          key: "documentReference",
          label: "External purchase / shipment reference",
        },
        {
          key: "plannedStart",
          label: "Expected arrival",
          type: "datetime-local",
        },
        {
          key: "plannedEnd",
          label: "Expected departure",
          type: "datetime-local",
        },
      ],
      event: false,
    },
    {
      title: "Book dock interval",
      method: "PUT",
      path: "/truck-visits/{id}/booking",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "spaceId",
          label: "Dock",
          source: "docks",
        },
        {
          key: "start",
          label: "Start",
          type: "datetime-local",
        },
        {
          key: "end",
          label: "End",
          type: "datetime-local",
        },
      ],
      event: false,
    },
    {
      title: "Register truck arrival",
      method: "POST",
      path: "/truck-visits/{id}/arrive",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Check driver and gate documents",
      method: "POST",
      path: "/truck-visits/{id}/gate-check",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "accepted",
          label: "Accept entry?",
          type: "boolean",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Assign bay or booked dock",
      method: "PUT",
      path: "/truck-visits/{id}/space",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "spaceId",
          label: "Space",
          source: "spaces",
        },
      ],
      event: false,
    },
    {
      title: "Start loading / unloading",
      method: "POST",
      path: "/truck-visits/{id}/handling",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Record weight ticket",
      method: "POST",
      path: "/truck-visits/{id}/weights",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "kind",
          label: "Weight type",
          options: ["TARE", "GROSS"],
        },
        {
          key: "kg",
          label: "Measured kilograms",
          type: "number",
        },
        {
          key: "measuredAt",
          label: "Measurement time",
          type: "datetime-local",
        },
      ],
      event: true,
    },
    {
      title: "Finish handling and check documents",
      method: "POST",
      path: "/truck-visits/{id}/finish",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "documentsChecked",
          label: "Documents checked?",
          type: "boolean",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Confirm departure",
      method: "POST",
      path: "/truck-visits/{id}/depart",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Cancel scheduled visit",
      method: "POST",
      path: "/truck-visits/{id}/cancel",
      fields: [
        {
          key: "id",
          label: "Truck visit",
          source: "trucks",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
  ],
  security: [
    {
      title: "Register access zone",
      method: "POST",
      path: "/zones",
      fields: [
        {
          key: "code",
          label: "Zone code",
        },
        {
          key: "name",
          label: "Zone name",
        },
      ],
      event: false,
    },
    {
      title: "Register badge",
      method: "POST",
      path: "/badges",
      fields: [
        {
          key: "code",
          label: "Badge code",
        },
      ],
      event: false,
    },
    {
      title: "Schedule visitor / contractor",
      method: "POST",
      path: "/visitors",
      fields: [
        {
          key: "reference",
          label: "Visitor reference",
        },
        {
          key: "name",
          label: "Visitor name",
        },
        {
          key: "company",
          label: "Company",
        },
        {
          key: "hostReference",
          label: "Factory host reference",
        },
        {
          key: "validFrom",
          label: "Valid from",
          type: "datetime-local",
        },
        {
          key: "validTo",
          label: "Valid until",
          type: "datetime-local",
        },
        {
          key: "zoneIds",
          label: "Authorized zones (multiple selection)",
          source: "zones",
          type: "multiple",
        },
      ],
      event: false,
    },
    {
      title: "Check in and issue badge",
      method: "POST",
      path: "/visitors/{id}/check-in",
      fields: [
        {
          key: "id",
          label: "Visitor",
          source: "visitors",
        },
        {
          key: "badge",
          label: "Badge",
          source: "badges",
        },
        {
          key: "safetyBriefed",
          label: "Safety briefing completed?",
          type: "boolean",
        },
      ],
      event: false,
    },
    {
      title: "Record zone entry / exit",
      method: "POST",
      path: "/visitors/{id}/access",
      fields: [
        {
          key: "id",
          label: "Visitor",
          source: "visitors",
        },
        {
          key: "zoneId",
          label: "Zone",
          source: "zones",
        },
        {
          key: "direction",
          label: "Direction",
          options: ["ENTER", "EXIT"],
        },
      ],
      event: true,
    },
    {
      title: "Check out after zone exit",
      method: "POST",
      path: "/visitors/{id}/check-out",
      fields: [
        {
          key: "id",
          label: "Visitor",
          source: "visitors",
        },
        {
          key: "badgeReturned",
          label: "Badge returned?",
          type: "boolean",
        },
      ],
      event: false,
    },
    {
      title: "Cancel expected visitor",
      method: "POST",
      path: "/visitors/{id}/cancel",
      fields: [
        {
          key: "id",
          label: "Visitor",
          source: "visitors",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Report security incident",
      method: "POST",
      path: "/incidents",
      fields: [
        {
          key: "zoneId",
          label: "Zone",
          source: "zones",
          optional: true,
        },
        {
          key: "severity",
          label: "Severity",
          options: ["LOW", "MEDIUM", "HIGH"],
        },
        {
          key: "description",
          label: "Incident details",
        },
      ],
      event: false,
    },
    {
      title: "Resolve incident",
      method: "POST",
      path: "/incidents/{id}/resolve",
      fields: [
        {
          key: "id",
          label: "Incident",
          source: "incidents",
        },
        {
          key: "notes",
          label: "Notes / approval reason",
        },
      ],
      event: false,
    },
    {
      title: "Register surveillance camera",
      method: "POST",
      path: "/cameras",
      fields: [
        {
          key: "code",
          label: "Camera code",
        },
        {
          key: "zoneId",
          label: "Zone",
          source: "zones",
        },
        {
          key: "name",
          label: "Camera name",
        },
        {
          key: "vmsReference",
          label: "Video-management camera identifier",
        },
      ],
      event: false,
    },
    {
      title: "Update camera status",
      method: "PUT",
      path: "/cameras/{id}/state",
      fields: [
        {
          key: "id",
          label: "Camera",
          source: "cameras",
        },
        {
          key: "state",
          label: "Reported device status",
          options: ["ONLINE", "OFFLINE", "MAINTENANCE"],
        },
      ],
      event: false,
    },
  ],
};
export const operationsTables = {
  maintenance: [
    {
      source: "workOrders",
      columns: [
        "code",
        "kind",
        "state",
        "description",
        "technician_id",
        "currency",
      ],
    },
    {
      source: "technicians",
      columns: ["code", "name"],
    },
    {
      source: "plans",
      columns: ["machine_id", "next_due", "interval_days", "instructions"],
    },
    {
      source: "partStock",
      columns: ["sku", "name", "quantity", "unit"],
    },
  ],
  facilities: [
    {
      source: "areas",
      columns: ["code", "name"],
    },
    {
      source: "assets",
      columns: ["code", "name", "kind", "state"],
    },
    {
      source: "contracts",
      columns: [
        "supplier_reference",
        "starts_on",
        "ends_on",
        "amount",
        "currency",
      ],
    },
    {
      source: "workOrders",
      columns: ["code", "state", "description"],
    },
  ],
  yard: [
    {
      source: "trucks",
      columns: [
        "reference",
        "carrier",
        "tractor_plate",
        "purpose",
        "state",
        "space_id",
        "planned_start",
      ],
    },
    {
      source: "bookings",
      columns: ["visit_id", "space_id", "starts_at", "ends_at"],
    },
  ],
  security: [
    {
      source: "visitors",
      columns: [
        "reference",
        "name",
        "company",
        "host_reference",
        "badge",
        "state",
        "valid_to",
      ],
    },
    {
      source: "incidents",
      columns: ["severity", "description", "state", "resolution"],
    },
    {
      source: "cameras",
      columns: ["code", "name", "vms_reference", "state"],
    },
  ],
};
