# Upgrade to 0.7.0

Retain existing `.env`, database volume and Compose project name. All 24 required secrets remain as in 0.6; this revision adds no new demo account. Do not rerun the settings generator over your existing file. Back up and test restoration first; see DEPLOYMENT.md.

V1–V6 are unchanged. Flyway applies SQL V7 and Java V8. V7 adds catalog auditing/indexes, adjustment authorship, production abort metadata and maintenance cancellation status. V8 finds the original production state enum check and adds ABORTED without removing unrelated not-null checks. Full application startup needs the packaged Java migration as well as SQL resources.

Known legacy adjustment authors are recovered from the earliest ADJUSTMENT_REQUESTED audit. Missing authors stay unknown: those entries may be rejected/reentered but cannot be approved. No historical catalog audit is invented. Existing jobs, schedules, stock, attendance and journals are retained. New date checks do not rewrite historical transactions; review earlier payment/reversal dates during staged acceptance.

Rebuild the local demo with `docker compose up --build -d --wait`. The stable JAR filename removes the previous Docker packaging mismatch. For staged production use verified image digests as in DEPLOYMENT.md. Test PostgreSQL migration/restore before touching real records. H2 fresh-install and V6 upgrade tests passed; PostgreSQL/container execution remains pending in this workspace.

The new session endpoint needs no new roles; recognized roles now expose all allowed workspaces. Keep the demo profile local. Automatic browser OIDC flows and general factory/department isolation remain pending.

## Historical increment notes

The notes below describe earlier releases; current behavior and verification are documented above and in VERIFICATION.md.

### Original catalog/manufacturing upgrade

This archive contains the original catalog code plus the manufacturing extension. Keep your existing `.env` and database volume; add the four new manufacturing account passwords from `.env.example`. Rebuild using `docker compose up --build -d`. Flyway retains V1 and applies V2 once. The database user needs schema-migration privileges.

Do not delete the database volume to perform an upgrade. Back up real data before upgrading and test restoration separately. Reuse the same Compose project name if moving the code to another directory so you keep the intended volume.

The frontend is available on localhost:8081. Keycloak installations need the additional manufacturing roles and employee subject associations described in MANUFACTURING.md.

V2 has not been executed on PostgreSQL in this environment; validate the upgrade on a disposable PostgreSQL database before applying it to real data.

## Upgrade to version 0.3

Add the six operations passwords from .env.example; retain the seven existing values. Rebuild the same Compose project. Flyway adds V3 without modifying V1/V2. Read FACTORY_OPERATIONS.md for the new roles and interfaces. PostgreSQL migration and container operation still need validation before real deployment.

## Upgrade to version 0.4

Add PAYROLL_PASSWORD and PAYROLL_APPROVER_PASSWORD from .env.example; retain all existing values and volumes. Flyway applies V4. Configure the new OIDC roles if using bearer authentication. Read WORKFORCE_PAYROLL.md before processing payroll. Statutory taxes/contributions, actual payments and accounting exports remain unimplemented.

## Upgrade to version 0.5

Retain `.env`, the database volume, and the Compose project name. Add ACCOUNTANT_PASSWORD, FINANCE_PASSWORD and FINANCE_APPROVER_PASSWORD from `.env.example`; rebuild. Flyway applies V5 without altering earlier migrations. Configure ACCOUNTANT, FINANCE_MANAGER and FINANCE_APPROVER OIDC roles. Read FINANCIAL.md for manual posting, source references, two-decimal currency support and financial limitations. Existing finalized payroll can now be explicitly posted; it is never posted automatically during upgrade. Validate the PostgreSQL migration and restore process before real deployment.

## Upgrade to version 0.6

Retain all existing secrets and the database volume. Add INTEGRATION_PASSWORD, REPORTS_PASSWORD, DEVICE_PASSWORD, CUSTOMER_PASSWORD, CUSTOMER2_PASSWORD and SUPPLIER_PASSWORD from `.env.example`. Rebuild the same Compose project; V6 is additive and V1–V5 remain unchanged. Add the five OIDC roles in INTEGRATION.md. Devices and portal subjects must be explicitly registered/bound; upgrade does not publish catalog items or grant external access automatically. AI is disabled until its server-side settings are explicitly configured. Validate PostgreSQL migration and backup restoration before real deployment.
