# Programming increments

This checklist is about implementation progress, not a claim that requirements/design rows 1–10 have already been implemented. The first deliverable combines a small foundation with one functional catalog slice.

| Increment | Feature | Result | Status |
|---|---|---|---|
| A | Backend foundation + product catalog | Create, find, rename and deactivate products | Implemented |
| B | Organization + scoped permissions + audit | Factories, departments, warehouses, managers, tracked changes | Next |
| C | Customers and suppliers | Business contacts used by orders | Planned |
| D | Stock ledger and lots | Receipts, transfers, counts and usable balances | Planned |
| E | Purchasing | Request, approve, order and receive | Planned |
| F | Sales orders | Quote, confirm and track order lines | Planned |
| G | Reservations | Reserve available lots with concurrency protection | Planned |
| H | Dispatch and returns | Pick, ship partially, reverse returns with traceability | Planned |
| I | Vue operational screens | Manufacturing screens implemented; supply-chain screens remain pending | Partial |
| J | Supply-chain acceptance and deployment | End-to-end PostgreSQL tests, security review, restore check, pilot | Planned |

Each increment adds to this application. Never replace the accumulated code with an unrelated standalone example. Run existing checks after changes and add tests for the new business invariants.

## Added manufacturing scope

Steps 11–18 now have backend foundations and Vue forms. External sales, supplier and shipment references and minimal incoming lots bridge missing earlier modules. They do not constitute a completed Supply-chain release. Read MANUFACTURING.md before integration or deployment.

## Added factory operations

Steps 19–22 now include maintenance, infrastructure, truck-yard and visitor-access foundations with Vue forms, state transitions and role checks. Read FACTORY_OPERATIONS.md for implemented behavior and pending hardware/commercial integrations.

## Added workforce release

Steps 23–25 now include manual attendance validation, approved leave/overtime, versioned pay rules, bonuses/deductions, reviewed/finalized calculations and printable statements. Country-specific statutory payroll and payment integration remain pending.

## Added financial release

Steps 26–30 now include accounting-period/journal, invoice/settlement, approved-expense, actual-cost and same-material stock-recycling foundations. Their Vue screens connect existing finalized payroll, actual consumption, quality and closed work-order evidence. Read FINANCIAL.md for the implementation table, diagrams and scope boundaries. Statutory financial reporting, credit notes, bank feeds, automated inventory valuation and full supply-chain integration remain pending.

## Added integration release

Steps 31–35 add a device registry, replay-protected event inbox and offline edge bridge, optional structured AI report suggestions with human review, output/device/financial reports and CSV exports, and company-scoped customer/supplier portals. See INTEGRATION.md for the exact programming steps and diagrams. Physical vendor drivers, broker integration, AI invoice extraction, organization scoping, full supply-chain services and real deployment/provider acceptance remain pending.

## Revision 0.7

Catalog UI/audit, signed-token regression coverage, production abort/maintenance calendar recovery, independent payroll adjustment approval, layout/navigation corrections, browser/HTTP checks and CI/image publication are implemented. This does not complete the earlier supply-chain release. REVIEW.md orders the remaining work, assigns programming owners and defines acceptance gates.
