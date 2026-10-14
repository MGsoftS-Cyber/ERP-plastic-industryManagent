# PlastiFlow ERP · 0.7.0

Accumulated Java/Vue application for plastics factories: catalog, manufacturing, maintenance, facilities, truck yard, access, attendance/payroll, accounting/costing, recycling, device integration, reports, optional AI and partner portals.

This is a **modular monolith**: domain services share one backend and transactional database. One Vue client provides department workspaces. The offline device bridge is a separate edge process. This is not an independently deployed microservices ERP.

Version 0.7 fixes confirmed bugs, adds catalog screens/auditing, improves navigation/layout, expands regression coverage and introduces CI/image publication. Read [review and remaining requirements](docs/REVIEW.md), [verification results](docs/VERIFICATION.md), [deployment and CI/CD](docs/DEPLOYMENT.md), and [upgrade instructions](docs/UPGRADE.md).

## Current scope

| Release | Included | Remaining work |
|---|---|---|
| Supply chain 1–10 | Product catalog, search, versioned edits, deactivation and audit | Full parties, purchasing, sales, stock ledger, reservations, picking and returns |
| Manufacturing 11–18 | Equipment/molds, recipes, jobs, execution, quality, traceability, workforce and scheduling | Full warehouse/demand integration and richer planning |
| Factory operations 19–22 | Maintenance, spares, infrastructure inspections, truck visits and access | Vendor integrations, transport systems and physical safety controls |
| Workforce 23–25 | Clock evidence, approved attendance/absence/overtime, wages, manager-set night premiums and reviewed payroll | Country-specific statutory payroll and payment execution |
| Finance 26–30 | Journals, invoices/payments, approved expenses, production cost and recycling | Statutory reporting, credit notes, bank integration and automated inventory valuation |
| Integration 31–35 | Device inbox/outbox, projections, optional AI review, reports/CSV and company-scoped portals | Vendor devices, production browser SSO and real provider acceptance |

## Local demonstration

Requires Docker Compose and Python 3. The generator creates 24 random passwords in a local `.env`: one database password and 23 demo accounts. It never overwrites existing settings.

```bash
python3 scripts/prepare_demo_env.py
docker compose up --build -d --wait
```

Open **http://localhost:8081**, select an account and use its password from your local `.env`. Account mappings are in the module guides and `src/main/java/com/plastiflow/security/DemoSecurityConfiguration.java`. There are 22 human UI accounts and one device account. Keep this demonstration stack local. Stop with `docker compose down`; retain the database volume.

The API binds to localhost:8080; PostgreSQL is internal to Compose. API and Nginx images run as non-root users and have healthchecks. No paid AI request is needed for tests or demonstrations.

## Build and verify

The verification environment uses Java 17, Maven 3.9.11, Node 24 and Python 3.12. Java 17 is the minimum backend target.

```bash
mvn -B -ntp verify
python3 -m unittest discover -s scripts -p 'test_*.py'
cd frontend
npm ci
npm audit --audit-level=high
npm run build
npx playwright install --with-deps chromium
cd ..
mvn -B -ntp test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
python3 scripts/run_acceptance.py --browser
python3 scripts/run_acceptance.py --demo
```

The helper starts/stops a disposable H2 API with synthetic credentials. Browser tests use Vite on localhost:4173. CI additionally targets PostgreSQL and packaged containers. See VERIFICATION.md for actual results and limits.

## Production and design

The default backend uses signed bearer JWTs. Configure database settings, `OIDC_ISSUER_URI` and `OIDC_AUDIENCE` (default `plastiflow-api`). Roles in `realm_access.roles` are described in the module guides. The API verifies signature, expiry, issuer and audience. `/api/v1/session` supplies roles for workspace navigation. The frontend currently supports manual bearer-token entry; automatic OIDC login, refresh and logout remain pending. Never use the `demo` profile in production.

The production Compose template requires verified image references and uses a loopback frontend port for your TLS reverse proxy. General multi-factory/department data isolation remains pending; partner portals have company scoping. This is a tested development release, not a complete production ERP.

Read [architecture](docs/DESIGN.md), [roadmap](docs/ROADMAP.md), [manufacturing](docs/MANUFACTURING.md), [operations](docs/FACTORY_OPERATIONS.md), [workforce/payroll](docs/WORKFORCE_PAYROLL.md), [finance](docs/FINANCIAL.md), and [integration](docs/INTEGRATION.md).

Backend source: `src/main/java/com/plastiflow`; Vue client: `frontend`; SQL migrations: V1–V7. V8 is a Java Flyway migration in `src/main/java/db/migration`. V1–V6 retain their prior bytes. Maven produces the stable `target/plastiflow.jar` filename.
