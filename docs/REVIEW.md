# Application review · 0.7.0 · 14 October 2026

This revision preserves earlier modules and addresses confirmed defects. Passing the checks does not establish that every ERP requirement or production environment is complete.

| Area | Finding and revision |
|---|---|
| Packaging | Obsolete Docker JAR filename fixed using stable Maven finalName and matching Docker copy |
| HTTP client | Successful 200/204 responses without bodies no longer trigger JSON parse errors; CSV stays text |
| Catalog | Added create/search/paginated edit/deactivate screens, reader permissions and transactional audit snapshots |
| Navigation | Added authenticated session roles and selector for all allowed department workspaces; views remount on switching |
| Reports/AI | Refresh and navigation respect separate report and AI permissions |
| Factory workflow | Zero-good-output jobs can be aborted by a manager with a reason/time; consumption, rejects and scheduling evidence remain |
| Output protection | Jobs with candidate-good output cannot be aborted; complete and inspect that output |
| Scheduling | Only active jobs reserve resources; audited maintenance-window cancellation retains history |
| Workforce | Job start and shift creation reject inactive employees |
| Payroll | Bonus/deduction author is stored; combined roles cannot approve their own entries; unknown legacy authors cannot be approved |
| Accounting | Journal reversal/payment dates cannot precede their source posting |
| Costing | Approved batch is locked during cost capture |
| Event replay | Matching historical clock/spare-receipt replays survive employee/product deactivation |
| Camera replacement | Replacement device inherits camera freshness, preventing stale status overwrite |
| Offline bridge | New outbox files use mode 0600; indexed delivery lookup |
| UI | Compact navigation, textarea/focus styles, responsive fields, mobile table scrolling, improved printing and reset feedback |
| Delivery | SHA-pinned Actions, PostgreSQL/H2 tests, browser/HTTP/bridge checks, container startup/proxy checks, gated GHCR publication and dependency updates |

An abort does **not** replenish consumed kilograms. Reconcile unused material/scrap through a future warehouse return workflow. Canceling a calendar window does not close a maintenance work order or remove its machine hold. ERP records do not establish physical lockout or safe machine operation.

110 backend tests, 27 Chromium browser checks and 8 bridge tests passed. Vue type checking/build, npm audit, the accumulated HTTP example and local Actionlint passed. Desktop and 390-pixel mobile screenshots were inspected. See VERIFICATION.md for scope and limits.

V7 adds audit/indexes, adjustment authorship and abort/cancellation fields. V8 replaces the original unnamed production-state constraint to permit ABORTED. Upgrade coverage starts at V6, preserves records and recovers only identified adjustment authors. Earlier migration files remain unchanged.

## Requirements for a complete application

| Priority | Remaining work | Programming owner | Acceptance requirement |
|---|---|---|---|
| 1 | Factory/department/warehouse scope and manager assignments | Backend + frontend | Cross-factory reads/writes denied |
| 1 | Complete supply chain 1–10: parties, buying, receipts, ledger, sales, reservations, shipment and returns | Backend + frontend | Concurrent movements cannot overspend stock; balances reconcile |
| 1 | Run PostgreSQL/container CI and staged upgrade/restore | DevOps + backend | Green CI, preserved data and completed restore drill |
| 1 | Browser OIDC login/refresh/logout, identity provisioning, secrets and TLS | Full-stack + DevOps | Real session lifecycle, role revocation and device isolation accepted |
| 2 | Statutory payroll/accounting, payments and corrections | Backend + domain specialists | Jurisdiction-approved examples and reconciled postings |
| 2 | Vendor fingerprint readers, cameras/scales and optional broker | Integration + factory IT | Offline/reconnect/replacement acceptance on each selected device |
| 2 | Detailed action permissions, confirmations, saved filters, large-data pagination and richer selectors | Frontend | Real operators complete assigned work on representative volumes |
| 2 | Expanded BI, load tests, monitoring, audit retention/export | Backend + DevOps | Defined performance targets and exercised alerts |
| 3 | Live AI provider, evaluation data, spending controls and approval experience | AI/backend + managers | Useful evaluated suggestions without unauthorized writes |

Some administrative screens remain generic forms. No Java vulnerability scan, external penetration test, real hardware operation or live AI generation was performed. npm audit concerns the frontend dependency tree at this review date. Production deployment and statutory compliance remain unverified.
