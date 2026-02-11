## The Challenge

At origination, a contract's principal disperses to multiple destinations: fee settlement, deposit collection, old loan payoff, merchant disbursement. The current system conflates principal with disbursement, losing visibility into where funds actually went.

The deeper problem: every proposed solution mixes two distinct concerns — **what crossed Alraedah's bank boundaries** versus **what entered or left a contract's accounting**. This conflation creates the design tension the original document struggled to resolve.

---

## Diagnosis

The system must answer two fundamentally different questions:

1. **Bank reconciliation**: What money entered and left Alraedah's bank accounts?
2. **Contract accounting**: What money entered and left each contract's waterfall?

These questions operate at different levels:

| Level | Boundary | Facts |
|-------|----------|-------|
| Alraedah | Company bank accounts | Wire transfers — auditable, external, real |
| Contract | Contractual obligations | Allocations — internal accounting decisions |

The original document's three options (fee attribute, funding-payment, principal-allocation) all failed because they tried to solve a contract-level problem while entangled with Alraedah-level concepts. The word "payment" carried bank-level semantics into contract-level modeling. The result: every option either corrupted payment semantics or required dual-source enrichment.

**The kernel of the problem**: No clear separation between levels. Once you name something "payment," it inherits expectations about bank reconciliation. Once you call something "disbursement," it implies a wire transfer. The vocabulary itself creates the complection.

---

## Bad Strategy: What to Avoid

### Bad Strategy #1: Unified Primitives (Movement Model)

The movement model proposes one primitive — money moving between accounts — with perspectives derived through queries.

**Why it's seductive**: One concept. Symmetric by construction. Matches double-entry bookkeeping.

**Why it fails**:

- **Incidental complexity**: Accounts don't exist in signed contracts, customer communications, or operations vocabulary. They exist solely to make movements make sense. That's scaffolding to hold up scaffolding.
- **Hidden differences**: Payments and disbursements have different attributes, different lifecycles, different regulations, different correction models. Forcing them into one primitive doesn't unify them — it hides the differences behind classification functions.
- **Premature generality**: Solves problems we don't have (general ledger) while adding machinery to problems we do have (origination accounting).

Rumelt would call this "mistaking goals for strategy." The goal (unified model) is not a strategy. A strategy must address why the current approach fails and what specific actions resolve it.

### Bad Strategy #2: Payment with Channel Discriminator

Option B proposed recording funding deductions as payments with `channel: "funding-deduction"`.

**Why it's seductive**: Waterfall stays unchanged. One namespace. Familiar concept.

**Why it fails**:

- **Semantic corruption**: "Payment" means money received from outside. A funding deduction is internal. Calling it a payment with a channel flag is a lie that every downstream consumer must remember to filter.
- **The auditor test**: "How much did the customer pay?" now requires filtering. Forget once, overstate collections.
- **Vocabulary erosion**: Once "payment" can mean "not actually a payment," the term loses meaning. New team members can't trust the schema.

This is fluff disguised as simplicity. The word "payment" is doing no work — the channel attribute carries all the meaning. You've hidden complexity in an attribute rather than eliminated it.

### Bad Strategy #3: Fee Attributes for Settlement

Option A proposed storing `fee/deducted-from-funding` on fee entities.

**Why it's seductive**: No new namespaces. Fee "knows" how it was settled. Correction is localized.

**Why it fails**:

- **Complects charge with settlement**: A fee is what was charged. How it was settled is a different concern with different reasons to change.
- **Dual-source enrichment**: Every consumer of fee status must combine waterfall allocation + deducted attribute. Forget once, fee shows partially paid.
- **Breaks waterfall authority**: The waterfall says "20K paid." The attribute says "44.7K also paid." Authority is split.

This is failure to face the challenge. The challenge is "how does the waterfall know fees are settled?" This answer says "don't use the waterfall for part of it." That's avoidance, not resolution.

---

## Good Strategy

### Guiding Policy

**Separate levels. Name things by what they are. Let each level own its own vocabulary.**

Two levels, two sets of concepts:

| Level | Inbound | Outbound |
|-------|---------|----------|
| Alraedah (bank) | `payment/*` — wire received | `disbursement/*` — wire sent |
| Contract (accounting) | `inflow/*` — money entering waterfall | `outflow/*` — money leaving waterfall |

The link between levels is explicit: a payment owns its inflows as components — retract the payment, the inflows cascade. A disbursement owns its outflows as components. But not all inflows have payments and not all outflows have disbursements. Funding, deposit, and settlement events at the contract level exist independently.

**The waterfall treats all inflows uniformly.** It does not branch on source. It receives an amount and allocates to obligations by priority. Source is provenance metadata, not a branching condition.

### Why This Works

**Payments stay pure.** Sum all payments = money received by Alraedah. Bank reconciliation works. Auditor gets correct answer. No filtering.

**Disbursements stay pure.** Sum all disbursements = money sent by Alraedah. Bank reconciliation works. No semantic stretching.

**Inflows are contract-level.** The waterfall sees inflows. It doesn't care about source — it allocates to obligations by priority. Source is metadata, not identity.

**Outflows are contract-level.** Fee settlement, deposit collection, merchant disbursement, old loan settlement — all outflows from this contract's perspective. Type distinguishes them.

**Refi becomes symmetric.** Settlement from new contract to old contract:
- New contract: `outflow` with `:type :settlement`, `:target-contract old`
- Old contract: `inflow` with `:source :settlement`, `:source-contract new`

One transaction, two facts, each contract has a complete local view.

**No reverse queries.** Funding breakdown queries this contract's outflows. Old loan settlement queries the inflow. Everything local.

### Proof: Deposit Release

A customer has paid down their loan to where the remaining balance equals the deposit amount. They sign a document authorizing Alraedah to apply the deposit to settle the loan.

This creates an inflow:

```clojure
{:inflow/id       (uuid)
 :inflow/amount   deposit-amount
 :inflow/source   :deposit
 :inflow/contract contract-id}
```

No payment entity — no money crossed Alraedah's bank boundary. No disbursement — no wire was sent. The bank level sees nothing. The contract level sees money arriving at the waterfall from the deposit.

The waterfall allocates this inflow identically to a customer payment or a funding inflow. It doesn't branch on source. It applies to whatever obligations remain — installments, fees, whatever the priority rules dictate.

This scenario slots in without bending any existing concept. That's the test for a simple design: new scenarios don't require going back and changing anything.

### Three inflow sources, one waterfall

| Source | Trigger | Documented in | Bank boundary crossed? |
|--------|---------|---------------|----------------------|
| `:funding` | Origination | Contract | No (internal allocation) |
| `:customer` | Repayment | Bank transfer | Yes (payment entity) |
| `:deposit` | Early settlement authorization | Signed customer document | No (internal release) |
| `:settlement` | Refinance from another contract | New contract | No (inter-contract) |

The waterfall is a pure function of inflows and obligation schedule. Source is never a branching condition.

---

## Schema

### Time Model

Every entity in Datomic gets `tx/instant` automatically — the system time when the fact was recorded. This is always available and never needs explicit attributes.

Business dates represent **when the event is effective for accounting purposes**, which may differ from when it was recorded. A payment processed on Sunday but value-dated Friday has `tx/instant` on Sunday and `:payment/effective-date` on Friday. Origination outflows share the contract's funding date. A deposit release is effective on the date the customer authorized it.

Business dates are explicit attributes. System time is free infrastructure.

### Alraedah Level (Bank Boundaries)

```clojure
;; Money received from outside — a wire hit Alraedah's bank account
{:db/ident       :payment/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity}
{:db/ident       :payment/amount
 :db/valueType   :db.type/bigdec}
{:db/ident       :payment/effective-date
 :db/valueType   :db.type/instant
 :db/doc         "Business date: when the bank value-dated this transfer"}
{:db/ident       :payment/sender
 :db/valueType   :db.type/string}
{:db/ident       :payment/reference
 :db/valueType   :db.type/string}
{:db/ident       :payment/inflows
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/isComponent true
 :db/doc         "Contract-level inflows caused by this payment. Retract payment, inflows cascade."}

;; Money sent to outside — a wire left Alraedah's bank account
{:db/ident       :disbursement/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity}
{:db/ident       :disbursement/amount
 :db/valueType   :db.type/bigdec}
{:db/ident       :disbursement/effective-date
 :db/valueType   :db.type/instant
 :db/doc         "Business date: when the bank value-dated this transfer"}
{:db/ident       :disbursement/recipient
 :db/valueType   :db.type/string}
{:db/ident       :disbursement/reference
 :db/valueType   :db.type/string}
{:db/ident       :disbursement/outflows
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/isComponent true
 :db/doc         "Contract-level outflows caused by this disbursement. Retract disbursement, outflows cascade."}
```

### Contract Level (Contractual Accounting)

```clojure
;; Money entering this contract's waterfall
;; Customer inflows are components of payments (cascade on retraction).
;; Funding, deposit, and settlement inflows exist independently — no bank-level parent.
{:db/ident       :inflow/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity}
{:db/ident       :inflow/amount
 :db/valueType   :db.type/bigdec}
{:db/ident       :inflow/effective-date
 :db/valueType   :db.type/instant
 :db/doc         "Business date: when this inflow takes accounting effect"}
{:db/ident       :inflow/contract
 :db/valueType   :db.type/ref}
{:db/ident       :inflow/source
 :db/valueType   :db.type/keyword
 :db/doc         "Origin of funds entering the waterfall"}
{:db/ident       :inflow/source-contract
 :db/valueType   :db.type/ref
 :db/doc         "Link to source contract when source is :settlement"}

;; Money leaving this contract's waterfall
;; Merchant outflows are components of disbursements (cascade on retraction).
;; Fee, deposit, and settlement outflows exist independently — no bank-level parent.
{:db/ident       :outflow/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity}
{:db/ident       :outflow/amount
 :db/valueType   :db.type/bigdec}
{:db/ident       :outflow/effective-date
 :db/valueType   :db.type/instant
 :db/doc         "Business date: when this outflow takes accounting effect"}
{:db/ident       :outflow/contract
 :db/valueType   :db.type/ref}
{:db/ident       :outflow/type
 :db/valueType   :db.type/keyword
 :db/doc         "What this outflow represents: :fee, :deposit, :settlement, :merchant, :excess-return"}
{:db/ident       :outflow/target-contract
 :db/valueType   :db.type/ref
 :db/doc         "Link to target contract when type is :settlement"}
```

### Component Relationships

Not all inflows have bank-level parents. Not all outflows have bank-level parents. The component relationship applies only where a bank boundary crossing caused the contract-level event:

| Entity | Bank parent | Component? |
|--------|-------------|------------|
| Inflow `:customer` | Payment | Yes — retract payment, inflow cascades |
| Inflow `:funding` | None | Independent — contract-level fact |
| Inflow `:deposit` | None | Independent — authorized by signed document |
| Inflow `:settlement` | None | Independent — caused by another contract |
| Outflow `:merchant` | Disbursement | Yes — retract disbursement, outflow cascades |
| Outflow `:fee` | None | Independent — internal allocation |
| Outflow `:deposit` | None | Independent — internal allocation |
| Outflow `:settlement` | None | Independent — inter-contract allocation |

---

## Origination Flow

Origination happens in two stages. Before signing, the funding breakdown is computed to generate the contract document. After signing, the actual facts are recorded. No entities are created before signing — a plan is a computation, not a fact.

### Stage 1: Compute for Contract Generation

A pure function computes the funding breakdown from contract terms. This produces the numbers printed in the contract document. No database writes.

```clojure
(defn compute-funding-breakdown [contract-terms]
  (let [principal         (:principal contract-terms)
        fee-amount        (:fee-amount contract-terms)
        deposit-amount    (:deposit-amount contract-terms)
        settlement-amount (:settlement-amount contract-terms 0)
        merchant-amount   (- principal fee-amount deposit-amount settlement-amount)]
    {:principal         principal
     :to-fees           fee-amount
     :to-deposit        deposit-amount
     :to-settlement     settlement-amount
     :to-merchant       merchant-amount}))
```

This function lives in the shared computation library. It is used by:
- Contract generation (LOS) — to print correct values in the contract document
- Analytics — for what-if scenarios and simulations
- Validation — to verify execution matches the signed contract

### Stage 2: Execute After Signing

After the customer signs, the signed contract document is the source of truth for execution. The values come from the signed contract — not recomputed from terms that might have changed between generation and signing.

```clojure
(defn execute-funding [db signed-contract]
  (let [{:keys [principal fee-amount deposit-amount
                settlement-amount merchant-amount]} (:contract/funding-breakdown signed-contract)

        effective       (:contract/funding-date signed-contract)
        contract-id     (:contract/id signed-contract)
        disbursement-id (uuid)]

    (cond->
      [;; Bank level: wire to merchant, with outflow as component
       {:disbursement/id             disbursement-id
        :disbursement/amount         merchant-amount
        :disbursement/effective-date effective
        :disbursement/recipient      (:contract/merchant-name signed-contract)
        :disbursement/reference      (:wire-reference signed-contract)
        :disbursement/outflows
        [{:outflow/id             (uuid)
          :outflow/amount         merchant-amount
          :outflow/effective-date effective
          :outflow/type           :merchant
          :outflow/contract       contract-id}]}

       ;; Contract level: principal enters the waterfall (independent — no bank parent)
       {:inflow/id             (uuid)
        :inflow/amount         principal
        :inflow/effective-date effective
        :inflow/source         :funding
        :inflow/contract       contract-id}

       ;; Contract level: fees settled from funding (independent)
       {:outflow/id             (uuid)
        :outflow/amount         fee-amount
        :outflow/effective-date effective
        :outflow/type           :fee
        :outflow/contract       contract-id}

       ;; Contract level: deposit collected (independent)
       {:outflow/id             (uuid)
        :outflow/amount         deposit-amount
        :outflow/effective-date effective
        :outflow/type           :deposit
        :outflow/contract       contract-id}]

      ;; Refinance: settlement (conditional)
      (:old-contract-id signed-contract)
      (into
        [;; This contract: money leaves to settle old contract (independent)
         {:outflow/id              (uuid)
          :outflow/amount          settlement-amount
          :outflow/effective-date  effective
          :outflow/type            :settlement
          :outflow/contract        contract-id
          :outflow/target-contract (:old-contract-id signed-contract)}

         ;; Old contract: money arrives from new contract (independent)
         {:inflow/id              (uuid)
          :inflow/amount          settlement-amount
          :inflow/effective-date  effective
          :inflow/source          :settlement
          :inflow/contract        (:old-contract-id signed-contract)
          :inflow/source-contract contract-id}]))))
```

### The Two-Stage Pattern

```
contract terms ──→ compute-funding-breakdown (pure function, shared library)
                         │
                         ▼
                   generate contract document (values printed for customer)
                         │
                         ▼
                   customer signs ──→ signed contract (fact, stored in Datomic)
                         │
                         ▼
                   execute-funding (creates inflows, outflows, disbursement)
                         uses values FROM the signed contract
```

The signed contract is the source of truth for execution. If terms changed between generation and signing (fee adjustment, updated settlement balance on old loan), the contract would be regenerated before signing. Once signed, execution uses exactly what the customer agreed to. The computation function is called once for generation; the signed document carries the values forward.

### The Conservation Invariant

At origination, the books balance:

```
inflow(funding) = outflow(fee) + outflow(deposit) + outflow(settlement) + outflow(merchant)
```

Principal in, everything out. Zero residual. This is an assertable invariant — if it doesn't hold, something is wrong.

Over the life of the contract, the invariant evolves:

```
sum(all inflows) >= sum(all waterfall allocations)
```

The contract closes when obligations reach zero. Inflows accumulate from funding, customer payments, deposit releases, and settlement receipts. The waterfall allocates each one as it arrives according to the same priority rules regardless of source.

---

## Waterfall

The waterfall is a pure function of inflows and obligation schedule. It does not branch on source.

```clojure
(defn compute-waterfall [db contract-id]
  (let [inflows  (query-inflows db contract-id)
        outflows (query-outflows db contract-id)]
    {:total-in  (sum :inflow/amount inflows)
     :total-out (sum :outflow/amount outflows)
     :available (- (sum :inflow/amount inflows)
                   (sum :outflow/amount outflows))}))
```

During origination, available is zero — everything was allocated. During repayment, customer payments create inflows that the waterfall allocates to installment obligations. At early settlement, a deposit release creates an inflow allocated to whatever remains.

---

## Computation, Snapshots, and Corrections

### Why Allocations Are Not Stored

When the waterfall allocates an inflow — 3,200 to principal, 1,800 to profit — the question arises: should we store the allocation result?

No. The allocation is deterministic given three inputs: the inflow amount, the obligation schedule at that point in time, and the algorithm. Storing the result creates a second source of truth that can diverge from recomputation. Correcting a stored allocation (e.g., reversing a bounced payment) requires reversing the inflow *and* every allocation record it produced. If allocations are computed, you reverse the inflow and the waterfall naturally recalculates.

The waterfall algorithm is the authority. If there's ever a question about what should have happened, recompute from the algorithm, the inflow, and the schedule as-of (available through Datomic's time-travel queries). The algorithm is a pure function — same inputs always produce the same outputs.

### When Computation Crystallizes: Snapshots

Computation results become facts at specific business moments — when they are communicated with legal or regulatory authority. A customer statement, a SAMA report, a merchant settlement notice. At these moments, the computation result has contractual standing independent of the function that produced it.

Rather than storing every allocation, the system takes **snapshots** at business-meaningful boundaries:

- **Month-end close**: finance requests a snapshot of each contract's state
- **Statement generation**: the customer's statement captures balances, payments, allocations
- **Regulatory reporting**: SAMA/IFRS submissions capture portfolio state
- **Quarter-end close**: financial books close with authoritative figures

A snapshot is a new entity in its own namespace, containing the complete computed state at that moment — the same pattern used for contractual documents. The waterfall computes, the result is saved as an immutable fact under a document namespace, and that snapshot becomes the authoritative record of what was communicated or reported.

Between snapshots, the system has one source of truth: facts plus the algorithm. Recomputation is free, corrections are simple, and the waterfall function can be updated without migrating stored state.

### Correction Policies

When facts change after a snapshot — a late-arriving payment, a bug fix, a data correction — the recomputed state may differ from the last snapshot. This variance is surfaced, not suppressed. Different consumers handle the variance differently:

**Finance (quarterly)**: the snapshot is the closed book. Corrections flow into the next quarter as adjustments. The original snapshot is never modified.

**Merchant statements**: corrections trigger a new statement referencing the original, reflecting the business date of the corrected event. The original statement remains as-is.

In both cases: the original snapshot is immutable, the correction is a new fact that references the old snapshot, and the waterfall's recomputed state shows the "true" current position. The delta between recomputation and the last snapshot is the variance that drives correction actions.

Three things, not two:

1. **The waterfall** — always computes current truth from facts
2. **Snapshots** — periodic photographs of waterfall output, tied to business moments
3. **Corrections** — new documents that reference a prior snapshot and explain the delta

The correction policy is per-consumer, not per-contract. The same variance triggers different actions for different consumers.

### Reconciliation via Snapshots

Snapshots serve as reconciliation checkpoints. When anything changes in the system — a bug fix in the waterfall algorithm, a late payment recorded retroactively, a data correction — the system can recompute using Datomic's `as-of` queries and diff against the relevant snapshot.

If the recomputed result matches the snapshot: nothing to do. If it differs: a variance exists that needs review. The snapshot tells you what was *communicated*. The recomputation tells you what *should have been*. The delta is a business decision — correct forward, issue an amendment, or accept the variance.

---

## Readers and Analytics

### The Core System's Boundary

The core system stores facts and computes state on demand. It does not maintain materialized views, denormalized tables, or pre-computed aggregations for downstream consumers. Analytics, dashboards, and reporting are separate readers with their own pipelines, their own performance requirements, and their own data shapes. They are not a concern of this system.

### Three Readers

| Reader | Reads | Needs | Latency |
|--------|-------|-------|---------|
| **Operations** | Facts + live computation | Current state of any contract | Real-time (derive on read) |
| **Finance/Reporting** | Snapshots | Authoritative state at business moments | Periodic (month-end, quarter-end) |
| **Analytics** | Facts + snapshots | Aggregations, trends, portfolio metrics | Near-real-time to batch |

Operations calls the waterfall function directly — it needs the live answer for this contract right now. Finance consumes snapshots — they need the closed-book answer. Analytics reads whatever serves its purpose — raw facts for trend analysis, snapshots for portfolio reporting.

### Shared Computation Library

The waterfall function, loan status function, ECL staging, profit recognition — these are pure functions in a shared library. They take facts as input and return results. They have no side effects, no database writes, no awareness of who is calling them.

```
core-lib/
  waterfall.clj          ;; inflows + obligations → allocations
  loan-status.clj        ;; facts → status
  ecl.clj                ;; facts → staging + provision
  profit-recognition.clj ;; facts → recognized profit

lms-service/
  depends on core-lib
  reads/writes Datomic
  serves operations

analytics-pipeline/
  depends on core-lib    ;; same functions, same artifact
  reads Datomic tx log or snapshots
  writes to data warehouse
```

The analytics pipeline uses the same waterfall function as the LMS. Same code, same version, same result. Alignment is guaranteed by construction, not by reconciliation.

If real-time or near-real-time analytics is needed, the pipeline subscribes to Datomic's transaction log and triggers computation on relevant events. If batch is sufficient, the pipeline reads facts periodically and recomputes. Either way, the core system is unaware.

### Schema Discipline

The core system's schema is designed for operational and regulatory correctness — not for query convenience of downstream analytics. No denormalized attributes for dashboard performance. No materialized views maintained for reporting. No schema changes driven by analytics requirements. Analytics builds its own read models from the transaction log and snapshots.

Snapshots reduce the analytics burden significantly: a pipeline that needs "principal balance outstanding on January 31st" reads the January snapshot directly, without replaying the waterfall for every contract. The domain intelligence stays in the core library; the analytics pipeline reshapes and aggregates without understanding allocation rules.

---

## Queries

Every question has one obvious query at one level.

**How much did the customer pay?** Query payments (Alraedah level). No filtering.

**How much was disbursed?** Query disbursements (Alraedah level). No filtering.

**What's the funding breakdown?** Query this contract's outflows at origination:

```clojure
(defn funding-breakdown [db contract-id]
  (let [outflows (query-outflows-by-date db contract-id funding-date)]
    {:principal   (:contract/principal contract)
     :to-fees     (sum-by-type outflows :fee)
     :to-deposit  (sum-by-type outflows :deposit)
     :to-settlement (sum-by-type outflows :settlement)
     :to-merchant (sum-by-type outflows :merchant)}))
```

**What settled the old loan?** Query the old contract's inflows with `:source :settlement`.

**What's the deposit status?** Query outflows of type `:deposit` (money collected) and inflows with `:source :deposit` (money released back).

---

## Comparison: Before and After

| Concern | Original Options | This Strategy |
|---------|------------------|---------------|
| Fee settlement visibility | Dual-source or semantic corruption | Outflow with `:type :fee` at origination |
| Refi settlement | Reverse query via `tx/source-contract` | Symmetric inflow/outflow pair |
| Bank reconciliation | Mixed with contract accounting | Separate level: `payment/*`, `disbursement/*` |
| Waterfall sources | 4 namespaces to compose | 1 namespace: `inflow/*`, source-agnostic |
| "Total customer payments" | Filter required | Query payments (Alraedah level) |
| Funding breakdown | Cross-namespace queries | Local: outflows on funding date |
| Deposit release | Not addressed | Inflow with `:source :deposit`, no schema change |
| New money source | New namespace + waterfall change | New `:source` keyword value |
| System vs business time | Ambiguous date attributes | `tx/instant` for system time, `/effective-date` for business date |
| Allocation storage | Store every result (parallel ledger) | Compute on demand, snapshot at business moments |
| Corrections | Reverse stored allocations | Reverse inflow, recompute, diff against snapshot |
| Analytics | Entangled with core schema | Separate reader, shared computation library |
| Regulatory reporting | Query live state | Query snapshots (authoritative, closed-book) |
| Level coupling | Inflow references payment (upward) | Payment owns inflow as component (downward cascade) |
| Payment retraction | Manual cleanup of related entities | Component cascade — retract payment, inflows retract automatically |
| Origination timing | All entities created at once | Two stages: compute for contract, execute after signing |

---

## What This Strategy Does Not Do

It does not eliminate all complexity. Origination still creates multiple entities. Refi still requires a two-sided record. Corrections still require understanding which level to correct. Snapshot timing is a business decision that must be coordinated with finance and operations.

It does not solve GL integration. SAMA and IFRS 9 require journal entries that this model does not produce directly. Journal entry generation is a derivation from contract-level facts — a pure function from inflows, outflows, and snapshots to debit/credit pairs. This function belongs in the shared computation library, not in the core schema.

It does not guarantee analytics performance. The compute-on-demand model means portfolio-level queries require either running the waterfall across many contracts or reading snapshots. The shared computation library ensures correctness; the analytics pipeline is responsible for its own performance through caching, pre-computation, and data warehouse design.

But the complexity is **placed correctly**. Bank-level facts live in bank-level entities. Contract-level facts live in contract-level entities. Derived state lives in pure functions. Communicated state lives in snapshots. Analytics lives outside the boundary. The vocabulary is honest. Queries match questions. Each level can be understood independently. New scenarios — like deposit release — slot in without bending existing concepts.

That's what good strategy does: it doesn't make problems disappear. It clarifies where problems live so you can solve them in the right place.

---

## Decision

Adopt the two-level model with compute-on-demand, periodic snapshots, and two-stage origination:

- **Alraedah level**: `payment/*` and `disbursement/*` for bank boundary crossings
- **Contract level**: `inflow/*` and `outflow/*` for contractual accounting
- **Component ownership**: payments own customer inflows, disbursements own merchant outflows — retraction cascades automatically. Funding, deposit, and settlement entities at the contract level exist independently with no bank-level parent.
- **Waterfall**: source-agnostic pure function over inflows and obligations, never stores allocation results
- **Two-stage origination**: compute funding breakdown for contract generation (pure function, no writes), execute after signing using values from the signed contract (creates all entities)
- **Time model**: `tx/instant` for system time (free), `/effective-date` for business dates (explicit)
- **Outflow types**: `:fee`, `:deposit`, `:settlement`, `:merchant`, `:excess-return` — each meaning one thing
- **Snapshots**: computation crystallizes into immutable facts at business moments (month-end, statements, regulatory reports), stored under document namespaces
- **Corrections**: new facts referencing prior snapshots, with per-consumer correction policies (next-quarter for finance, correcting statement for merchants)
- **Analytics**: separate reader, not a concern of the core system — pipelines build their own read models from the transaction log and snapshots, using the same shared computation library as the LMS

The cost is vocabulary change and discipline: teams must learn "inflow" and "outflow" as contract-level terms, trust computation over stored state between snapshots, and resist the temptation to add schema for analytics convenience. The benefit is a model where every question has one obvious query, every fact lives at one level, no concept is stretched beyond its meaning, corrections are simple because derived state is never stored — only communicated state is, and no entities exist before signing because plans are computations, not facts.