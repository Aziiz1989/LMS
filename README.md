# LMS — Loan Management System

## The Core Idea: Store Facts, Derive State

This system is built on one principle: **if you can compute it, don't store it**.

A fact is something that happened in the real world. State is what you conclude from facts.

```
Fact:    Payment of 50,000 SAR received on Jan 15 via bank transfer FT-9281
Fact:    Contract signed for 1,000,000 SAR principal on Dec 1
Fact:    Installment 3 is due on Mar 31 for 80,000 principal + 12,000 profit

State:   Installment 3 is "partial" (derived — payment allocated 50,000 against 92,000 due)
State:   Outstanding balance is 732,000 (derived — total due minus total allocated)
State:   Contract is "active" (derived — disbursed and outstanding > 0)
```

None of the state lines are stored in the database. Every time you view a contract, the system queries all facts and recomputes everything from scratch: the waterfall runs, allocations are calculated, statuses are derived, balances are summed.

**Why this matters for corrections**: In this model, correcting a mistake means correcting the fact. Retract the wrong payment — the system recomputes, and every derived value (installment statuses, balances, outstanding amounts) is automatically correct on the next read. There is no multi-table rollback, no cascade of UPDATE statements, no reconciliation step.

---

## How Standard Loan Systems Work (and Why We Don't)

Most loan management systems store derived state alongside facts. Here's how the major systems work and why they made that choice:

**Temenos T24 / Transact** stores running balances on accounts. When a payment arrives, T24 updates the account balance, installment status, and arrears counters in the same transaction. This is designed for high-throughput retail banking where sub-millisecond balance lookups matter — a bank processing millions of transactions per day needs `SELECT balance FROM account` to return instantly. The trade-off: when state drifts (and it does), reconciliation batch jobs run nightly to detect and fix discrepancies.

**Mambu** is a cloud-native LMS that stores installment states (`PAID`, `PARTIALLY_PAID`, `LATE`) as first-class fields. It fires webhooks on state transitions and exposes them via API. This is designed for API-first fintechs that build workflows around state changes. The trade-off: corrections require reverse transactions and sometimes manual state fixups through support tickets, because the stored state and the actual payment history can diverge.

**FIS/Fiserv systems (LoanIQ, LaserPro)** maintain stored allocation tables alongside payment records. When a payment is processed, it writes rows into payment allocation, GL entry, and balance tables simultaneously. This is designed for syndicated lending at scale where multiple parties need real-time balance visibility. The trade-off: correcting a payment means rolling back entries across multiple tables, and the correction workflow is complex enough to warrant its own operations team.

**Custom SQL systems** (the most common approach in MENA) typically have an `installments` table with `status`, `paid_amount`, and `paid_date` columns. A payment triggers `UPDATE installments SET status = 'paid', paid_amount = X WHERE ...`. Corrections mean reversing those UPDATEs across rows and hoping the cascade is complete. The trade-off is obvious when it breaks: the `paid_amount` column says 50,000 but the sum of actual payments allocated to that installment is 45,000.

**The common pattern** across all these systems: payment comes in, update installment status, update balance, update arrears counters. They do this because:

1. Read performance — `SELECT * FROM installments WHERE status = 'overdue'` is fast
2. Reporting — aggregation queries work directly on stored fields
3. Familiarity — CRUD on mutable rows is what most development teams know

**What breaks in these systems:**
- **State drift** — The paid flag says "paid" but the amounts don't add up. This happens after corrections, partial reversals, or race conditions in concurrent processing.
- **Correction complexity** — Fixing a wrong payment means rolling back updates across installments, balances, arrears counters, GL entries. Miss one table and you have silent inconsistency.
- **Audit gaps** — Overwritten data is gone. `UPDATE installments SET paid_amount = 45000` destroys the previous value unless you built a separate audit log.
- **Scattered allocation logic** — Payment allocation code appears in payment processing, in reversal processing, in correction processing, in settlement processing. Each copy can diverge.

**Our approach**: We don't store state. We store the payment as a fact. On every read, we run the waterfall — a single pure function — from scratch. Status, paid amounts, outstanding balance, credit balance — all computed. Correction? Retract the payment entity. Next read automatically reflects the correct state. Every fact is preserved in Datomic's immutable history; nothing is overwritten, nothing is lost.

---

## Documents as Snapshots, Not State

External parties need documents: regulators need statements, borrowers need clearance letters, auditors need agreement records. In standard systems, these documents are generated from stored state — and if that state drifts, the document becomes silently wrong.

Our approach: documents are **snapshots of derived state at a point in time**. When we issue a clearance letter, we freeze the settlement calculation as EDN data. When we generate a statement, we freeze the contract state for that period. When a contract agreement is signed, we freeze the terms as they were at signing.

These snapshots are not state — they're facts. The fact is: "on Jan 15, we told the borrower the settlement amount was 450,000." That's what happened. It doesn't change.

**Transparency**: If you run the settlement calculation today and get 460,000, you can see exactly what changed since the snapshot was taken — a new payment arrived, a rate was adjusted, or an error was corrected. In a stored-state system, the old number is simply overwritten and the drift is invisible.

**Catching drift**: Compare any document snapshot to the current live derivation. Any difference means something changed — either legitimately (new payment, rate adjustment) or by mistake (erroneous retraction). The system makes this visible by design. Stored-state systems hide it by design.

**Audit trail**: Snapshots combined with Datomic's immutable history give you a full forensic trail. What did we tell the borrower? What were the facts at that time? What changed since? Every question is answerable.

---

## What We Optimized For (and What We Didn't)

### Made Simple

**Correctness** — There is one waterfall function. It is the single source of truth for how money is allocated to fees and installments. There is no allocation logic in payment processing, no allocation logic in reversal processing, no allocation logic in settlement processing. One function, one answer.

**Corrections** — Retract a fact, state recalculates. A wrong payment? Retract the payment entity — its component inflows cascade-retract automatically, and the next read derives the correct state. No multi-table rollback. No reconciliation.

**Auditability** — Every fact is preserved in Datomic's immutable history. Transaction metadata records who recorded it, when, and why. Document snapshots let you compare what you told someone in the past to what the system says today. The question "what happened?" always has an answer.

**Adding operations** — A new operation is a new fact type plus a thin handler. Record a new kind of event? Write the entity to the database. The waterfall handles allocation automatically. You don't need to update allocation logic in five different places.

**Settlement** — Settlement is a pure function over current facts. It computes outstanding principal, accrued profit, fees, and penalties from the live contract state. There is no stored "settlement amount" that can drift from reality.

### Accepted Costs

**Read latency** — Every read runs the waterfall. There are no cached balances, no materialized views. This is a known, solvable problem with well-understood solutions: bigger server, more RAM, optimized derivation code, and eventually a caching layer if scale demands it. At our current and foreseeable scale, this is not a concern. We chose correctness now over premature optimization.

**Query patterns** — You cannot do `SELECT * FROM installments WHERE status = 'overdue'`. Status isn't stored — it's derived. To find overdue contracts, you load each contract, run the waterfall, and check the derived status. Bulk reporting needs a dedicated read path that iterates contracts and aggregates derived state.

**Learning curve** — The first question every new team member asks is "where is the installment status stored?" The answer — "it isn't, it's computed" — is disorienting. The derivation model requires understanding the waterfall, the fact/state distinction, and the read path before you can reason about the system. This upfront investment pays for itself in reduced debugging and fewer state-consistency bugs.

---

## The Architecture

### Fact Entities (What Happened)

| Namespace | What it records | Level |
|-----------|----------------|-------|
| `payment/*` | Wire transfer received from customer | Alraedah (company) |
| `disbursement/*` | Wire transfer sent out | Alraedah (company) |
| `inflow/*` | Money entering a contract's waterfall | Contract |
| `outflow/*` | Money leaving a contract | Contract |
| `deposit/*` | Collateral movement (separate ledger) | Contract |
| `installment/*` | Scheduled repayment (fact from signing) | Contract |
| `fee/*` | Charge (management, late, processing, insurance) | Contract |

### Pure Functions (What It Means)

**`waterfall.clj`** — Takes fees, installments, and available amount. Returns allocations. Zero database access. Priority: deposit > fees (oldest first) > installments (earliest first, profit before principal).

**`settlement.clj`** — Takes contract state. Returns settlement breakdown (outstanding principal + accrued profit + fees + penalty - credit balance). Zero database access.

### Read Model (Derive State from Facts)

**`contract.clj`** — `contract-state` is the single entry point:

```
query-facts → compute-waterfall-total → waterfall → enrich → totals
```

Every call queries all facts for a contract, computes the net available amount (inflows minus outflows), runs the waterfall, enriches fees and installments with their allocated amounts, derives statuses, and returns the complete state.

### Write Model (Record Facts)

**`operations.clj`** — Thin functions that build entity data, transact to Datomic, and attach transaction metadata (who, when, why). Operations do not compute state. They store facts.

### Edges (Thin Glue)

**`handlers.clj`** — Parse HTTP request, call the operation, re-query state via `contract-state`, pass to view, return response. No business logic.

**`views.clj`** — Pure functions: data in, Hiccup HTML out. No database access, no computation.

### The Flow

```
HTTP Request → Handler (parse) → Operation (write fact) → Datomic
                                                            ↓
HTTP Response ← View (render) ← contract-state (derive) ← query facts
```

### Two-Level Money Model

Money moves at two levels, because they represent different things:

**Company level** — What happened at the bank:
- `payment/*` — wire transfer received
- `disbursement/*` — wire transfer sent

**Contract level** — What happened to the contract's waterfall:
- `inflow/*` — money entering the waterfall (sources: funding, customer payment, deposit offset, settlement from another contract)
- `outflow/*` — money leaving the contract (types: to borrower, to another contract for settlement, refund)

**Why two levels?** A single payment can create inflows on multiple contracts. A disbursement is a bank event; an outflow is a contract event. They change for different reasons — different things.

Payments own their inflows as components (cascade on retraction). Disbursements own their outflows as components.

**Conservation law:**
```
sum(inflows) = sum(outflows) + waterfall-allocations + credit-balance
```

---

## Key Distinctions

Three judgment calls come up repeatedly when extending this system:

### Fact or Derived State?

| Test | Answer |
|------|--------|
| Did it happen in the real world? | Fact — store it |
| Can you compute it from other facts? | Derived — don't store it |

Examples:
- "Payment of 50,000 received" → fact
- "Installment 3 is partially paid" → derived (waterfall allocation)
- "Contract is active" → derived (has disbursement date + outstanding > 0)
- "Rate changed from 20% to 18% on installments 5-12" → fact (contractual terms changed)

### Entity or TX Metadata?

| Test | Answer |
|------|--------|
| Would this exist if we used a different database? | Entity attribute |
| Is it about how/when/who recorded it? | TX metadata |

Examples:
- "Payment of 50,000 via bank transfer FT-123" → entity (business fact)
- "Recorded by user ahmed-k on Jan 16 at 10:23am" → TX metadata (recording fact)

### Retraction or Reversal?

| Test | Answer |
|------|--------|
| Did money actually move in the real world? | Reversal — new negative fact |
| Was it a recording error? (typo, wrong contract, duplicate) | Retraction — `[:db/retractEntity ...]` |
| Have external systems already consumed it? (GL closed, SIMAH reported) | Reversal — even if original was wrong |

---

## Guidelines: Extending the System

### Adding a New Operation

Example: fee waiver, write-off, deposit refund.

1. **Ask: what is the fact?** "Fee was waived on date X by user Y" or "Contract was written off on date X."
2. **Schema**: Add entity attributes in `db.clj` if the fact type doesn't exist yet.
3. **Operation**: Write the fact in `operations.clj`. Keep it thin — build entity data, transact, attach TX metadata via `db/recording-metadata`.
4. **Contract state**: If it affects allocation, check whether the waterfall already handles it. If it's a new allocation concept, extend `waterfall.clj`. If it only affects derived status, extend the derivation in `contract.clj`.
5. **Handler + View**: Parse request → call operation → re-query `contract-state` → pass to view → return SSE response.

### Adding a New Product Type

For products where the schedule shape changes (step-up, grace periods, bullet payments): the waterfall doesn't care — it allocates by priority regardless of schedule shape. These are just different installment sequences.

If the product has fundamentally different allocation rules, that's a waterfall change.

#### Worked Example: Revolving Credit Line

A revolving line differs from a term loan: the borrower can draw down and repay repeatedly within a credit limit. But in our model, a revolving line is not a single contract with multiple drawdowns — it's a **facility with multiple contracts**.

The facility holds the agreement: credit limit, rate, repayment structure, borrower. Each drawdown creates a new contract under that facility. The facility holds enough data to board a new contract.

**Step 1 — What are the facts?**
- Facility approved with limit, rate, repayment terms (already exists: `facility/*` with `facility/limit`, `facility/party`)
- Drawdown of X amount on date Y → a new contract is boarded under the facility
- Repayment on a specific contract (already exists: payment → inflow, per contract)

**Step 2 — Same thing or different thing?**
- Drawdown: is it a new entity type? No — it's boarding a new contract. The facility provides the template (rate, repayment structure), and `board-new-contract` creates the contract with its installment schedule. A drawdown is the act of creating a contract + originating it (funding inflow, disbursement, etc.).
- Repayment: same as today. Payment → inflow → waterfall, on the specific contract.
- Available credit: derived — `facility/limit` minus sum of outstanding balances across all contracts under the facility.
- Each contract works exactly as term loans do: it has its own installments, its own waterfall, its own settlement calculation.

**Step 3 — What changes?**
- **Facility schema**: Extend `facility/*` with agreement details needed to board a contract — rate, repayment structure, tenor, fee configuration. The facility becomes the template.
- **Boarding**: A "drawdown" operation reads facility terms, generates the installment schedule for the drawdown amount and tenor, and calls `board-new-contract`. This is orchestration, not new architecture.
- **Facility-level derivation**: New function: query all contracts under a facility, derive state for each, aggregate to get total outstanding, available credit, facility utilization. This is a new read path but uses existing `contract-state` per contract.

**Step 4 — What stays the same:**
- Each contract: waterfall, settlement, enrichment, status derivation — all unchanged
- Payments, inflows, outflows — unchanged (they're per-contract)
- TX metadata pattern — unchanged
- Retraction/reversal logic — unchanged (retract a contract's payment, that contract recalculates)
- Document snapshots — unchanged (per-contract snapshots)
- Handler/view pattern — unchanged (new facility view that aggregates contract states)

The key insight: a revolving line reuses the entire contract architecture unchanged. It adds **facility-level agreement data** (template for boarding) and **facility-level derivation** (aggregate across contracts). Each drawdown is just a contract. Facts in, derivation out.

### Adding Formal Reports and Documents

When an external party (regulator, auditor, board) requires a formal report — an aging report, a portfolio summary, a regulatory filing — the pattern is the same as clearance letters and statements:

1. **Create a namespace** for the report entity (e.g., `aging-report/*`, `portfolio-summary/*`).
2. **Derive the state** as of the required date using `(d/as-of db target-date)` and `contract-state`. Aggregate across contracts as needed.
3. **Freeze the derivation** as a snapshot (EDN) on the report entity, along with metadata: as-of date, generation date, who requested it.
4. The snapshot is now a fact: "on Feb 12, we generated an aging report as of Dec 31, and this is what it said."

This follows the same principle as document snapshots. The report is not live state — it's a frozen point-in-time derivation. If you regenerate the same report later and get different numbers, the difference is visible and explainable: facts changed between then and now.

### Adjusting Installments

Rate changes, restructuring, step-up activation — these modify the installment schedule:

- `adjust-rates` in `operations.clj` modifies `installment/principal-due`, `installment/profit-due`, and `installment/remaining-principal` on affected installments
- This is a fact mutation — the contractual terms changed. The installment schedule is a fact of what was agreed; when the agreement changes, the facts change.
- TX metadata captures who adjusted, why, and which installments were affected
- Datomic preserves the history naturally — the previous values are not lost, they're in the immutable history. You can always query what the schedule looked like before the adjustment via `(d/history db)`.
- Waterfall automatically reflects the new amounts on the next read
