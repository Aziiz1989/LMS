
## The Insight

Every previous iteration of this design asked the same question: **how should the system handle origination?** Outflows. Waterfall runs. Contract attributes. Snapshots. Each answer found a better home for origination within the system's architecture. Each was wrong — not in its conclusion, but in its question.

The system doesn't handle origination. The system doesn't handle repayment. The system doesn't handle anything. The system stores facts from the world and answers questions about those facts. Everything in between is a pure function. There is nothing else.

---

## Diagnosis

The original design struggled because it tried to model origination as a **process** — something the system must execute, with inputs and outputs and state transitions. This created a cascade of design questions: Are fee deductions outflows or inflows? Should the waterfall run at origination? Where do allocation results live?

These questions dissolve once you recognize what the system actually does:

1. **Facts arrive from the world.** A contract is signed. Money is received. Money is sent. A human decides which contract a payment applies to. A customer authorizes a deposit release.

2. **Questions are asked.** What's the balance? Is this contract delinquent? What did the customer pay? What's the funding breakdown?

3. **Pure functions answer the questions.** Given these facts as of this point in time, here is the answer.

There are no event handlers. No lifecycle phases. No processing pipelines. No state machines. The operations team has workflows. The finance team has a close process. The collection team has escalation rules. Those live in the business. The system is a quiet room full of facts, waiting for questions.

---

## Two Levels of Fact

The system must distinguish two boundaries, because the world has two boundaries:

| Level | Boundary | What crosses it |
|-------|----------|-----------------|
| **Alraedah** | Company bank accounts | Wire transfers — auditable, external, real |
| **Contract** | Contractual obligations | Allocation decisions — human judgments about which contract gets what |

A customer sends 10,000. That's a fact at the Alraedah level: money arrived. Operations decides 6,000 goes to contract A and 4,000 to contract B. Those are facts at the contract level: humans decided where the money goes.

The payment is one fact. The inflows are separate facts. No function can derive an inflow from a payment — a human made that decision. Both must be stored.

The same applies outward. A disbursement to a merchant is a fact at the Alraedah level. The outflow linking it to a contract is a fact at the contract level.

---

## What the System Stores

Facts from the world, as they arrive, whenever they arrive:

**A contract was signed.** The contract contains its own terms: principal, fee amount, deposit amount, installment schedule, prepayment terms, merchant details, step-up rate structure. These are properties the contract was born with. The fee amount isn't "allocated at origination" — it's part of what the contract *is*.

**Money was received** (Alraedah level). A wire hit the bank account. Amount, sender, reference, effective date.

**Money was allocated inward** (contract level). Someone decided this payment — or part of it — applies to this contract. Amount, source, effective date. This is an **inflow**: a fact about a human decision, not a processing step.

**Money was sent** (Alraedah level). A wire left the bank account. Amount, recipient, reference, effective date.

**Money was allocated outward** (contract level). This disbursement was for this contract. Amount, type, effective date. This is an **outflow**: a fact about where money went, not a processing step.

**A document was authorized.** A customer signed a deposit release. A refinance was approved. An early settlement was agreed. These produce facts — new inflows, new outflows — that the world decided.

**A snapshot was taken.** Finance closed the month. A statement was generated. A regulatory report was filed. These freeze computed state into immutable facts with legal or regulatory authority.

---

## What the System Computes

Pure functions answer questions about facts. They have no side effects, no triggers, no lifecycle awareness. You can ask about now, about last Tuesday (via Datomic's `as-of`), or hypothetically with synthetic facts.

**What's the funding breakdown?** Read the contract terms. Fee amount, deposit amount, prepayment, settlement — they're properties, not computations. The merchant amount is `principal - fees - deposit - prepayment - settlement`. That's arithmetic over contract attributes, not a waterfall run.

**What's the obligation schedule?** A function of contract terms: principal, profit rate, tenure, step-up structure. Produces the installment schedule with amounts and due dates.

**What's the status of each obligation?** This is the waterfall — a lens over contract-level inflows, the obligation schedule, and time. Given everything that has flowed into this contract, how has it been allocated across obligations by priority? The waterfall doesn't "run" when a payment arrives. It runs when someone asks a question.

**Is this contract delinquent?** A lens over obligation status and the current date.

**What's the ECL staging?** A lens over the same facts with different optics.

**What's the recognized profit?** A lens over inflows, obligations, and IFRS 9 rules.

The waterfall solves **scarcity**: a customer pays 5,000 against 20,000 in obligations, and priority rules determine who gets what. At origination, there is no scarcity to resolve — the contract terms already specify every amount. The waterfall's domain begins when the first uncertain event arrives: a customer payment where the algorithm has a genuine question to answer.

---

## Schema

### Time Model

Every entity gets `tx/instant` automatically — system time when the fact was recorded. Business dates represent when the event is effective for accounting purposes. A payment processed Sunday but value-dated Friday has `tx/instant` on Sunday and `:payment/effective-date` on Friday.

### Alraedah Level (Bank Boundaries)

```clojure
;; Money received — a wire hit Alraedah's bank account
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

;; Money sent — a wire left Alraedah's bank account
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

### Contract Level (Allocation Decisions)

```clojure
;; Inflow: someone decided money applies to this contract
;; Customer inflows are components of payments (cascade on retraction).
;; Funding, deposit, and settlement inflows exist independently.
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
 :db/doc         "Origin: :customer, :funding, :deposit, :settlement"}
{:db/ident       :inflow/source-contract
 :db/valueType   :db.type/ref
 :db/doc         "Source contract when source is :settlement"}

;; Outflow: money left this contract to an external party
;; Merchant outflows are components of disbursements (cascade on retraction).
;; Settlement outflows exist independently.
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
 :db/doc         "What this outflow represents: :merchant, :settlement, :excess-return"}
{:db/ident       :outflow/target-contract
 :db/valueType   :db.type/ref
 :db/doc         "Target contract when type is :settlement"}
```

### What Changed from the Original Schema

Outflow types shrank. `:fee`, `:deposit`, and `:prepayment` are gone. Fees, deposits, and prepaid installments are **contract terms** — properties the contract was born with. They aren't money leaving the contract. They're the contract describing how its principal is structured.

Outflows now mean one thing: **money that left this contract to an external party.** Merchant disbursement. Settlement to another contract. Excess returned to the customer. That's it.

### Component Relationships

| Entity | Bank parent | Component? |
|--------|-------------|------------|
| Inflow `:customer` | Payment | Yes — retract payment, inflow cascades |
| Inflow `:funding` | None | Independent — contract-level fact |
| Inflow `:deposit` | None | Independent — authorized by signed document |
| Inflow `:settlement` | None | Independent — caused by another contract |
| Outflow `:merchant` | Disbursement | Yes — retract disbursement, outflow cascades |
| Outflow `:settlement` | None | Independent — inter-contract allocation |
| Outflow `:excess-return` | Disbursement | Yes — money returned to customer |

---

## Origination: Not an Event

Origination isn't something that happens to a contract. Origination is the contract coming into existence.

Two things happen in the world. Exactly two:

1. **The signed contract enters the system** — an external fact containing its own terms.
2. **Money goes to the merchant** — a disbursement crossing Alraedah's bank boundary, with an outflow linking it to the contract.

There is nothing else to process. The fee amount is in the contract. The deposit amount is in the contract. The prepayment terms are in the contract. These are properties, not events. They don't need to be flowed, allocated, or snapshotted. They need to be **read**.

### The Funding Breakdown Is Not a Waterfall Run

Before signing, the LOS computes a funding breakdown from contract terms to generate the contract document. This is a pure function:

```clojure
(defn compute-funding-breakdown [contract-terms]
  (let [{:keys [principal fee-amount deposit-amount
                settlement-amount prepayment-amount]} contract-terms
        merchant-amount (- principal fee-amount deposit-amount
                           settlement-amount prepayment-amount)]
    {:principal    principal
     :to-fees      fee-amount
     :to-deposit   deposit-amount
     :to-settlement settlement-amount
     :to-prepayment prepayment-amount
     :to-merchant  merchant-amount}))
```

This function is arithmetic over contract attributes. It is used by the LOS to print the contract document. After signing, the contract carries these values as terms. The system reads them. The function exists for validation and contract generation — not as a system process.

### What Gets Recorded

After signing, the system records facts:

```clojure
(defn record-origination-facts [signed-contract]
  (let [{:keys [contract-id funding-date merchant-name
                wire-reference]} signed-contract
        merchant-amount (compute-merchant-amount signed-contract)]

    (cond->
      [;; Fact: money enters this contract (funding)
       {:inflow/id             (uuid)
        :inflow/amount         (:principal signed-contract)
        :inflow/effective-date funding-date
        :inflow/source         :funding
        :inflow/contract       contract-id}

       ;; Fact: money was sent to merchant (bank level + contract level)
       {:disbursement/id             (uuid)
        :disbursement/amount         merchant-amount
        :disbursement/effective-date funding-date
        :disbursement/recipient      merchant-name
        :disbursement/reference      wire-reference
        :disbursement/outflows
        [{:outflow/id             (uuid)
          :outflow/amount         merchant-amount
          :outflow/effective-date funding-date
          :outflow/type           :merchant
          :outflow/contract       contract-id}]}]

      ;; Refinance: settlement facts on both contracts
      (:old-contract-id signed-contract)
      (into
        [{:outflow/id              (uuid)
          :outflow/amount          (:settlement-amount signed-contract)
          :outflow/effective-date  funding-date
          :outflow/type            :settlement
          :outflow/contract        contract-id
          :outflow/target-contract (:old-contract-id signed-contract)}

         {:inflow/id              (uuid)
          :inflow/amount          (:settlement-amount signed-contract)
          :inflow/effective-date  funding-date
          :inflow/source          :settlement
          :inflow/contract        (:old-contract-id signed-contract)
          :inflow/source-contract contract-id}]))))
```

No outflows for fees, deposits, or prepayments. No waterfall run. No conservation invariant to check at execution time — the contract wouldn't have been generated if the numbers didn't add up.

### The Conservation Invariant

At origination, the books balance by construction:

```
principal = fee-amount + deposit-amount + prepayment-amount + settlement-amount + merchant-amount
```

This is verified at contract generation time, before the customer signs. It's a precondition on the contract terms, not a runtime assertion. The signed contract carries the proof.

---

## The Waterfall: A Lens, Not a Mechanism

The waterfall is a pure function that answers a question:

*Given this contract's terms and all inflows up to this point in time, what is the status of each obligation?*

It doesn't "run" when a payment arrives. It runs when someone asks. The payment is a fact. The question comes later — maybe immediately, maybe at month-end, maybe never for some contracts.

```clojure
(defn obligation-status [db contract-id as-of-date]
  (let [contract    (pull-contract db contract-id)
        schedule    (compute-schedule contract)
        inflows     (query-inflows db contract-id as-of-date)
        outflows    (query-outflows db contract-id as-of-date)]
    (allocate-to-obligations schedule inflows outflows as-of-date)))
```

The waterfall sees inflows. It doesn't care about source. A customer payment, a funding inflow, a deposit release, a settlement receipt — all are inflows. The algorithm allocates to obligations by priority. Source is provenance metadata, not a branching condition.

At origination, the funding inflow enters and the waterfall allocates it to obligations. Fees are satisfied first (highest priority). Then deposit. Then prepaid installments if applicable. Whatever remains is available for future installments — but we already know that residual equals the merchant amount because the contract terms guarantee it. The waterfall confirms what the contract already says. This is validation, not discovery.

During repayment, the waterfall does real work. A customer pays 5,000 against 20,000 in obligations. Priority rules resolve the scarcity. Different rules would produce different results. That's genuine computation.

The waterfall doesn't know the difference. Same function, same code path. The distinction between "predetermined" and "uncertain" is a property of the inputs, not the mechanism.

### Three Inflow Sources, One Lens

| Source | Trigger | Bank boundary crossed? |
|--------|---------|----------------------|
| `:funding` | Origination | No (internal — principal enters contract) |
| `:customer` | Repayment | Yes (payment entity as parent) |
| `:deposit` | Early settlement authorization | No (internal release) |
| `:settlement` | Refinance from another contract | No (inter-contract) |

---

## Snapshots and Corrections

### Why Allocations Are Not Stored

When the waterfall allocates an inflow — 3,200 to principal, 1,800 to profit — the result is not stored. The allocation is deterministic given three inputs: the inflow amount, the obligation schedule, and the algorithm. Storing creates a second source of truth. Correcting requires reversing the inflow *and* every allocation record. If allocations are computed, reverse the inflow and the waterfall naturally recalculates.

### When Computation Crystallizes

Computed results become facts at specific business moments — when they are communicated with legal or regulatory authority. At these moments, the computation result has standing independent of the function that produced it.

The system takes snapshots at business-meaningful boundaries:

- **Month-end close**: finance requests a snapshot of each contract's state
- **Statement generation**: captures balances, payments, allocations for the customer
- **Regulatory reporting**: SAMA/IFRS submissions capture portfolio state
- **Quarter-end close**: financial books close with authoritative figures

A snapshot is a new entity containing the complete computed state at that moment. Between snapshots, the system has one source of truth: facts plus functions.

Note: the signed contract is **not** a snapshot. A snapshot is what the system produces — waterfall output frozen at a business moment. The signed contract is what the world produces — an agreement between parties. The system serves the contract, not the other way around.

### Correction Policies

When facts change after a snapshot, the recomputed state may differ. This variance is surfaced, not suppressed:

**Finance (quarterly)**: the snapshot is the closed book. Corrections flow into the next quarter as adjustments. The original snapshot is never modified.

**Customer statements**: corrections trigger a new statement referencing the original.

Three things:

1. **The lens** — always computes current truth from facts
2. **Snapshots** — periodic photographs of the lens output, tied to business moments
3. **Corrections** — new documents referencing a prior snapshot, explaining the delta

The correction policy is per-consumer, not per-contract.

---

## Readers

The core system stores facts and answers questions. It does not maintain materialized views or pre-computed aggregations.

| Reader | Reads | Asks |
|--------|-------|------|
| **Operations** | Facts | "What's the state of this contract right now?" |
| **Finance** | Snapshots | "What was the authoritative state at month-end?" |
| **Analytics** | Facts + snapshots | "What are the trends across the portfolio?" |

Operations calls the lens directly. Finance reads snapshots. Analytics builds its own read models from the transaction log and snapshots using the same shared computation library.

### Shared Computation Library

The waterfall, loan status, ECL staging, profit recognition — these are pure functions in a shared library. Same code in the LMS service and the analytics pipeline. Alignment by construction.

```
core-lib/
  waterfall.clj          ;; inflows + obligations → allocations
  loan-status.clj        ;; facts → status
  ecl.clj                ;; facts → staging + provision
  profit-recognition.clj ;; facts → recognized profit
  funding.clj            ;; contract terms → breakdown (validation/generation)

lms-service/
  depends on core-lib
  reads/writes Datomic
  serves operations

analytics-pipeline/
  depends on core-lib
  reads Datomic tx log or snapshots
  writes to data warehouse
```

---

## Queries

Every question has one obvious answer path.

**How much did the customer pay?** Query payments (Alraedah level). No filtering.

**How much was disbursed?** Query disbursements (Alraedah level). No filtering.

**What's the funding breakdown?** Read the contract terms. Compute merchant amount as the residual.

**What settled the old loan?** Query the old contract's inflows with `:source :settlement`.

**What's the deposit status?** Contract terms show deposit collected. Query inflows with `:source :deposit` for any release.

**What's the balance on this contract?** Ask the waterfall lens with today's date.

**What was the balance on January 31st?** Ask the waterfall lens with Datomic's `as-of` January 31st. Or read the January snapshot.

**Why did this installment get 3,200?** Ask the waterfall lens — it computed the allocation based on priority rules.

**Why is the fee amount 100K?** Read the contract. The contract says so.

---

## Comparison: Before and After

| Concern | Original Design | This Design |
|---------|----------------|-------------|
| Fee settlement | Outflow entity at origination | Contract term — read it |
| Deposit collection | Outflow entity at origination | Contract term — read it |
| Prepayment | Not addressed, then outflow | Contract term — read it |
| Merchant disbursement | Outflow + disbursement | Same — real money left |
| Refi settlement | Symmetric inflow/outflow pair | Same — real inter-contract movement |
| Funding breakdown | Query outflows on funding date | Read contract terms |
| Waterfall at origination | Debated: run or don't run | Lens works, but answers are predetermined by terms |
| Bank reconciliation | Separate level | Same |
| "Total customer payments" | Query payments | Same |
| New origination obligation | New outflow type | New contract term |
| Deposit release | Inflow with `:source :deposit` | Same |
| Origination as concept | Event to be handled | Facts to be recorded — contract + disbursement |
| System lifecycle | Phases: origination → repayment → settlement | None — facts arrive, questions are asked |
| Allocation storage | Debated: store vs. compute | Compute on demand, snapshot at business moments |

---

## What This Design Does Not Do

It does not eliminate business complexity. The operations team still has origination workflows. The finance team still has close processes. The collection team still has escalation rules. Those complexities are real and live in the business — not in the system.

It does not solve GL integration. Journal entries are a derivation from facts — a pure function in the shared computation library.

It does not guarantee analytics performance. Compute-on-demand means portfolio queries require running the lens across many contracts or reading snapshots. The analytics pipeline handles its own performance.

But the system is simple. Facts go in. Questions come out. Everything in between is a pure function. Origination isn't a phase. Repayment isn't a phase. They're human narratives about sequences of facts. The system doesn't know about them.

---

## Decision

Adopt the facts-and-lenses model:

- **Alraedah level**: `payment/*` and `disbursement/*` for bank boundary crossings — facts about money moving in the world
- **Contract level**: `inflow/*` and `outflow/*` for allocation decisions — facts about where money goes, decided by humans
- **Contract terms**: fees, deposits, prepayments are properties of the signed contract — read, not processed
- **Outflows mean money leaving**: `:merchant`, `:settlement`, `:excess-return` only — no outflows for fees, deposits, or prepayments
- **The waterfall is a lens**: pure function over inflows and obligations, asked on demand, never triggered
- **Component ownership**: payments own customer inflows, disbursements own merchant outflows — retraction cascades
- **Snapshots**: computation crystallizes at business moments into immutable facts; the signed contract is not a snapshot — it's the world's fact
- **Corrections**: new facts referencing prior snapshots, per-consumer policies
- **Time model**: `tx/instant` for system time, `/effective-date` for business dates
- **Analytics**: separate reader, shared computation library, not a concern of the core system

The cost is discipline: teams must understand that the system has no lifecycle, only facts and questions. The benefit is a system where every question has one obvious answer, every fact lives at one level, and no machinery exists where no machinery is needed.