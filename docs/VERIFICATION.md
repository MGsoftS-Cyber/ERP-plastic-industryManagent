# Verification · 0.7.0 · 14 October 2026

| Check executed locally | Result |
|---|---|
| Java 17 Maven verify and executable JAR packaging | 110 tests; zero failures, errors or skips |
| Python offline bridge | 8 tests passed |
| Vue TypeScript and Vite production build | Passed |
| npm audit, including development dependencies | Zero reported vulnerabilities at review time |
| Actual Chromium browser interaction | 27 tests passed |
| Accumulated integration HTTP example | Passed on fresh disposable H2 API |
| GitHub workflow Actionlint 1.7.12 | Passed |
| V1–V6 compared to v0.6 ZIP | Byte-for-byte unchanged |
| V6→V8 upgrade test | Preserves data, recovers identified payroll authors, ignores unrelated state checks |
| Desktop/mobile screenshots | Inspected; corrected stretched navigation and cramped table headers |

The backend tests apply Flyway migrations in H2 PostgreSQL compatibility mode. Coverage includes catalog concurrency/audits, signed JWT verification, module permissions, stock consumption, job/quality/scheduling transitions, abort/repair behavior, operations, attendance/premiums/independent payroll approval, balanced accounting and costing, recycling, device replay/freshness, company portal isolation, report permissions and AI ownership/response validation.

New signature tests use locally generated RSA tokens and a local discovery/JWKS HTTP server to test actual signature, issuer, audience and expiry checks. Existing role-mapping tests also use a test decoder. Neither provisions a deployed identity provider.

The 27 Chromium tests log in all 22 human demonstration accounts, exercise catalog create/edit, expense approval with an empty response, device binding and failed projection feedback, portal submission/company isolation/mobile width, and invalid credentials. This is meaningful smoke/workflow coverage, not exhaustive browser coverage of every form. Chromium 153.0.8010.0 ran headless locally using a supplied executable; CI installs Playwright's Chromium.

The HTTP example executes earlier manufacturing/operations/payroll/financial examples, then verifies one raw clock projection after replay, no automatic attendance approval, company request/invoice isolation, report/CSV output and a locally queued AI snapshot. No live AI call, payment, machinery or physical device was operated. AI tests use local fixtures/mock providers.

## Repeat the local checks

Use the commands in README.md. The helper starts the API and browser/example subprocesses together and always terminates the API. Synthetic credentials are isolated in test resources. An optional `PLAYWRIGHT_CHROMIUM_EXECUTABLE` selects an installed executable; omit it for normal Playwright use. Optional `PLASTIFLOW_VISUAL_QA_DIR` writes desktop/mobile QA screenshots.

## Checks configured but not executed here

The GitHub workflow adds a PostgreSQL 17 test matrix, disposable Compose runtime, packaged HTTP smoke tests, frontend proxy/health checks and optional verified GHCR image publication. Docker/PostgreSQL tools were unavailable locally and no repository was connected for remote execution. Those checks remain pending; their workflow passed static lint only.

Also pending: deployed OIDC login/device identities, real vendor hardware, live AI generation, statutory financial/payroll acceptance, Java dependency scanning, load/penetration tests, TLS/monitoring acceptance and backup restoration. Successful local tests do not prove the complete ERP is production-ready or free of all defects. REVIEW.md lists implementation gaps and DEPLOYMENT.md provides the staging/backup procedure.
