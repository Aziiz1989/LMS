# Disbursement & Funding Allocation

## Problem Statement

The current system models disbursement as equal to principal: when a contract is boarded with principal = 750,000, the disbursement recorded is 750,000. In reality, the actual amount sent to the merchant is the principal **less** several deductions:

```
Principal (مبلغ التمويل)                    750,000
  - Admin fees (الرسوم الإدارية)             64,687.50    ← in contract
  - Other costs (تكاليف أخرى)                 2,000.00    ← in contract
  - Security deposit                         50,000.00    ← deducted same as fees
  - Old loan settlement (refi)              100,000.00    ← separate signed document
= Actual disbursement to merchant           533,312.50    ← wire transfer
```

The contract documents the principal (مبلغ التمويل) and the net financing amount (صافي مبلغ التمويل = principal - fees). The net financing amount is a contractual fact — it appears on the signed contract and should be stored, not just derived.

**Fees and deposit are both pre-funding obligations.** They must be satisfied before the loan is funded. Both are deducted from the principal the same way ("just take it from my loan"). The only difference is what they ARE — fees are revenue, deposit is collateral — not how they're settled.

The customer may **pre-pay part or all of the fees and deposit** during the application process (prior to boarding). Only the remaining balance is deducted from the principal at origination. Pre-payments are tracked in an external application system and arrive as input data at boarding time.

**When pre-payments exceed final obligations** (e.g., customer pre-paid based on a larger loan that was later reduced by committee), the excess must be returned. Two options exist:

1. Refund the excess separately, then proceed with normal origination
2. Include the excess in the disbursement wire (one transfer instead of two)

In option 2, the disbursement wire includes both principal funds AND customer excess — these are different facts recorded as separate disbursement entities.

---

## Decision Framework

From CLAUDE.md, the rules that apply:

| Rule | Application |
|------|-------------|
| **#1 Store facts, derive state** | Each deduction is a fact. The funding breakdown is derived. |
| **#2 Same thing or different thing?** | Fee deduction, deposit collection, customer payment, and merchant disbursement change for different reasons → different things. |
| **#3 What is it?** | Name each concept by what it IS, not by which existing concept it resembles. |
| **#6 What are the facts?** | Fee charged. Deposit collected. Payment received. Disbursement made. Principal allocated. |
| **#8 One namespace per concept** | If two things have different rules and different reasons to exist → different namespaces. |

The key test for any approach: **does it complect two concepts that should be separate?**

- "Complect" (per Rich Hickey): to braid together things that are independently simple, making the result complex. The opposite of composing simple things.

---

## The Core Design Tension

The waterfall is the single source of truth for "what's owed and what's paid." Fees are paid through the waterfall — customer payments flow in, the waterfall allocates to fees by due-date priority.

But origination fees are **not** paid by customer payments. They're deducted from the principal before disbursement. No money enters the contract to pay them.

**How does the waterfall know origination fees are settled?**

Three approaches follow. Each resolves this tension differently.

---

## Option A: Fee Attribute (`fee/deducted-from-funding`)

Store the deducted amount on the fee entity. The waterfall processes fees with their reduced effective amount. Enrichment combines both sources.

### How it works

```clojure
;; At boarding — fee knows its settlement:
{:fee/id (uuid)
 :fee/type :admin
 :fee/amount 64687.50M
 :fee/deducted-from-funding 44687.50M}  ;; ← customer pre-paid 20K

;; Waterfall receives adjusted fee:
;; effective = 64687.50 - 44687.50 = 20000
;; Customer payment of 20K → allocated to fee → fee "waterfall-paid" = 20K

;; Enrichment combines sources:
;; fee-paid = deducted (44687.50) + waterfall (20000) = 64687.50
;; fee-status = :paid
```

### What it complects

**The charge with its settlement method.** A fee is "what was charged." How it was settled is a different concern — it changes for different reasons (fee amount changes when pricing changes; settlement method changes when origination terms change). Putting both on one entity braids them together.

### Trade-offs

| | |
|---|---|
| **Preserves** | Payment purity. Sum all payments = money received from customer. No filtering needed. |
| **Taxes** | Every consumer of fee status. UI, reports, settlement calculation, overdue checks — all must combine waterfall allocation + deducted amount. Forget once → fee shows as partially paid. |
| **Breaks** | Waterfall as single source of truth. The waterfall says "20K paid." The attribute says "44.7K also paid." Authority is split. |
| **Edge case** | Correction: if fee amount changes, update one entity (the fee). Deducted amount is co-located. Simple. |
| **Edge case** | Full deduction (no customer pre-payment): waterfall sees fee with effective amount 0. Must filter out zero-amount fees or handle gracefully. |

---

## Option B: Funding-Deduction Payment (`payment/channel "funding-deduction"`)

Record the funding deduction as a payment entity with a distinguishing channel. The waterfall processes all payments uniformly. No fee changes needed.

### How it works

```clojure
;; At boarding — two payments, different sources:
{:payment/id (uuid)
 :payment/amount 20000M
 :payment/channel "customer"
 :payment/contract [:contract/id cid]
 :payment/date pre-payment-date
 :payment/reference "APP-123-FEE-PREPAYMENT"}

{:payment/id (uuid)
 :payment/amount 44687.50M
 :payment/channel "funding-deduction"
 :payment/contract [:contract/id cid]
 :payment/date origination-date
 :payment/reference "FUNDING-FEE-SETTLEMENT"}

;; Fee is unchanged:
{:fee/id (uuid) :fee/type :admin :fee/amount 64687.50M}

;; Waterfall input: 20K + 44.7K = 64.7K
;; Allocates to fee → fee paid in full
;; No special enrichment needed
```

### What it complects

**Customer payments with internal allocations.** A payment means "money received from the customer." A funding deduction means "money allocated from the principal." These have different sources, different audit trails, and different meanings. Putting both in `payment/*` braids them together.

### Trade-offs

| | |
|---|---|
| **Preserves** | Waterfall as single source of truth. Fee logic unchanged. Enrichment unchanged. |
| **Taxes** | Every consumer of payment totals. Customer statements, regulatory reports, dashboards — all must filter by channel. Forget once → overstate collections. |
| **Breaks** | Payment semantics. "Total payments" includes money that was never received. An auditor asking "how much did the customer pay?" gets the wrong answer without filtering. |
| **Edge case** | Correction: if fee changes from 64.7K to 60K, must retract the funding-deduction payment AND create a new one. Two entities to correct for one business change. |
| **Edge case** | `contract-state` showing "total-payments-received: 64.7K" immediately after boarding, before customer has paid anything. Technically correct but misleading. |

---

## Option C: Principal Allocation (recommended)

The funding deduction is **not** a payment (no money came from outside). It's **not** a fee attribute (the fee doesn't know how it was settled). It's its own thing: money from the principal that was allocated to settle obligations at origination.

Give it its own name. Don't stretch an existing concept.

### How it works

Fees and deposit are both pre-funding obligations. The settlement mechanism is the same (customer pays or deducted from principal). But they interact with the system differently:

- **Fee deductions** must flow through the waterfall, because the waterfall is the authority on fee payment status
- **Deposit deductions** do NOT flow through the waterfall — deposits are tracked by deposit entities and `compute-deposit-held`

Therefore: `principal-allocation` covers the fee deductions. Deposit deductions are recorded as `deposit/type :received` directly.

```clojure
;; Principal-allocation — fees deducted from funding (waterfall source):
{:principal-allocation/id       (uuid)
 :principal-allocation/amount   46687.50M  ;; admin fee remainder (44.7K) + other costs (2K)
 :principal-allocation/date     origination-date
 :principal-allocation/contract [:contract/id cid]}

;; Customer fee pre-payment — a real payment:
{:payment/amount 20000M
 :payment/contract [:contract/id cid]
 :payment/reference "APP-123-FEE-PREPAYMENT"}

;; Deposit — recorded as what it IS (collateral received), regardless of source:
{:deposit/type :received :deposit/amount 10000M :deposit/source :customer}
{:deposit/type :received :deposit/amount 40000M :deposit/source :funding}

;; Fee — unchanged, just a charge:
{:fee/id (uuid) :fee/type :admin :fee/amount 64687.50M}

;; compute-waterfall-total adds principal-allocation as a fourth source:
;;   payments (20K) + principal-allocations (46.7K) - refunds + offsets
;;   = 66.7K → waterfall allocates to fees → all fees paid ✓
```

**Customer deposit pre-payments are deposits, not payments.** A customer who pre-pays 10K towards their deposit during application — that's collateral received from the customer. It's `deposit/type :received, source :customer`. It doesn't enter the waterfall because deposits aren't waterfall obligations.

### Schema

```clojure
{:db/ident       :principal-allocation/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity
 :db/cardinality :db.cardinality/one
 :db/doc         "Unique identifier for principal allocation"}

{:db/ident       :principal-allocation/amount
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one
 :db/doc         "Amount allocated from principal to settle waterfall obligations (SAR).
                  Always positive. Covers fees/costs deducted from principal at
                  origination. Deposit deductions are handled by deposit entities."}

{:db/ident       :principal-allocation/date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "Business date of allocation (typically origination date)."}

{:db/ident       :principal-allocation/contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "Contract whose principal was allocated."}

{:db/ident       :principal-allocation/reference
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "External reference or description."}
```

### What it does NOT complect

- **Payment stays payment.** Sum all payments = money received from customer. No filtering.
- **Fee stays fee.** It's a charge. Doesn't know how it was settled. The waterfall figures it out.
- **Deposit stays deposit.** Collateral received, regardless of source. Not a waterfall item.
- **Waterfall stays the single source of truth.** It sees all money sources and allocates to all obligations. No dual-path enrichment.
- **Principal allocation is what it IS.** Money from the principal allocated to settle waterfall obligations. Named by what it is, not by where it goes.

### Trade-offs

| | |
|---|---|
| **Preserves** | Payment purity AND waterfall as single source of truth. Both existing concepts stay clean. |
| **Costs** | New namespace (~5 attributes). New source in `compute-waterfall-total`. New query in `query-facts`. |
| **Pattern** | Follows existing pattern exactly. `compute-waterfall-total` already composes 3 sources (payments, refund disbursements, deposit offsets). Adding a 4th is the same pattern. |
| **Edge case** | Correction: if fee amount changes, update the fee entity AND the principal-allocation entity. Two entities, but they're different facts (charge vs. allocation). |
| **Edge case** | Partial customer pre-payment of fees: customer pays 20K (payment), funding covers 44.7K (principal-allocation). Waterfall sees 66.7K. Allocates naturally. |
| **Edge case** | Customer pre-payment of deposit: recorded as `deposit/type :received, source :customer`. Not a payment. Doesn't enter waterfall. |
| **Edge case** | Full deduction (no customer pre-payment): principal-allocation = full fee amount. Waterfall allocates from it. |
| **Edge case** | No deduction (fee paid entirely by customer): no principal-allocation entity. Waterfall allocates from customer payments. |

---

## Comparison Matrix

| Dimension | A: Fee attribute | B: Funding payment | C: Principal allocation |
|---|---|---|---|
| Complects | Fee + settlement | Payment + internal allocation | Nothing |
| Waterfall changes | Filter/adjust fees | None | Add 4th source (same pattern) |
| Fee changes | New attribute + dual enrichment | None | None |
| Payment changes | None | Semantic broadening | None |
| New concept | No | No | Yes (~5 attributes) |
| Partial fee pre-payment | Awkward (amount math) | Natural | Natural |
| Deposit deduction | Not addressed | Not addressed | Handled by deposit entity |
| Customer excess return | Not addressed | Not addressed | Separate disbursement entity |
| "Total customer payments" | Clean (just sum) | Needs filter | Clean (just sum) |
| Fee "paid" status | Dual-source | Single-source (waterfall) | Single-source (waterfall) |
| Correction scope | 1 entity (fee) | 2 entities (fee + payment) | 2 entities (fee + allocation) |
| Follows existing pattern | No (new enrichment path) | Partially (stretches payment) | Yes (same as deposit-offset) |

---

## Recommendation

**Option C: Principal Allocation.**

The cost is one new namespace. The benefit is zero complection — every existing concept stays simple, and the new concept composes with them through the existing waterfall pattern.

This is the same pattern the system already uses for deposit offsets: a separate entity type that feeds into `compute-waterfall-total` as another money source. The waterfall doesn't care where money comes from — it allocates to obligations by priority. Adding principal allocations as a source is a one-line change to `compute-waterfall-total`.

---

## Full Origination Model (with Option C)

### Pre-funding obligations

Fees and deposit are both pre-funding obligations — they must be satisfied before the loan is funded. The settlement mechanism is the same:

1. Customer may pre-pay part or all during application (tracked externally)
2. Remaining balance is deducted from principal at origination ("take it from my loan")

The difference is how they enter the system:

| Obligation | Customer pre-payment recorded as | Funding deduction recorded as |
|---|---|---|
| Fee | `payment/*` (flows through waterfall) | `principal-allocation/*` (flows through waterfall) |
| Deposit | `deposit/type :received, source :customer` | `deposit/type :received, source :funding` |

Fees must flow through the waterfall because the waterfall is the authority on fee payment status. Deposits don't — they're tracked by `compute-deposit-held`.

### Customer excess (pre-payment > final obligations)

When the customer pre-pays during application based on initial terms, and the committee later approves a smaller loan, the pre-payment may exceed final obligations.

```
Example:
  Applied for: 1M loan. Fees 5% = 50K. Customer pre-pays 50K.
  Approved:    smaller loan. Fees = 5K, Deposit = 10K.
  Excess:      50K - 5K - 10K = 35K owed back to customer.
```

Two operational choices:

**Option 1: Refund first.** Return 35K to customer separately, then proceed with normal origination. Two wire transfers.

**Option 2: Include in disbursement.** Don't refund. Add the 35K to the disbursement wire. One transfer. The disbursement wire now contains funds from two sources: principal and customer excess.

In option 2, these are **different facts** recorded as **separate disbursement entities** even though they travel in one wire:

```clojure
;; From principal — loan funding:
{:disbursement/type :funding
 :disbursement/amount net-from-principal
 :disbursement/contract [:contract/id cid]
 :disbursement/reference "WT-001"}

;; Customer excess being returned via the same wire:
{:disbursement/type :excess-return
 :disbursement/amount 35000M
 :disbursement/contract [:contract/id cid]
 :disbursement/reference "WT-001"}  ;; same wire reference
```

`:excess-return` is distinct from `:refund`:
- `:refund` = money returned to customer that **reduces the waterfall** (e.g., overpayment refund during loan life). Affects `compute-waterfall-total`.
- `:excess-return` = application-level excess being returned. **Does not affect the waterfall** — the excess was never in the waterfall. It came from the external application system.

### Entities at origination (complete example)

```
CONTRACT:
  contract/principal              750,000       ← مبلغ التمويل
  contract/net-disbursement       683,312.50    ← صافي مبلغ التمويل (contractual fact)

FEES (charges):
  fee/* admin                      64,687.50    ← الرسوم الإدارية
  fee/* other-costs                 2,000.00    ← تكاليف أخرى

PRINCIPAL ALLOCATION (fees deducted from funding → flows through waterfall):
  principal-allocation/*           46,687.50    ← 44,687.50 admin + 2,000 other

CUSTOMER FEE PRE-PAYMENTS (from application → flows through waterfall):
  payment/*                        20,000.00    ← customer pre-paid part of admin fee

DEPOSIT (collateral — does NOT flow through waterfall):
  deposit/* :received source:cust  10,000.00    ← customer pre-paid part of deposit
  deposit/* :received source:fund  40,000.00    ← deposit deducted from funding

REFI (backed by separate signed documents):
  payment/* on old contract       100,000.00    ← old loan settlement (tx/source-contract)
  deposit/* :transfer              30,000.00    ← deposit moved from old to new

DISBURSEMENT (money out):
  disbursement/* :funding         533,312.50    ← principal funds → merchant
  disbursement/* :excess-return         0.00    ← (if any customer excess returned via wire)
```

### Waterfall flow

```
compute-waterfall-total:
  + customer payments:        20,000.00   (fee pre-payment)
  + principal allocations:    46,687.50   (fees deducted from funding)
  - refund disbursements:          0.00
  + deposit offsets:               0.00
  = total:                    66,687.50

Waterfall allocates (by due-date):
  → fee (admin, 64,687.50):  64,687.50  ← paid in full
  → fee (other, 2,000):       2,000.00  ← paid in full
  → installment 1:                0.00  ← nothing remaining
```

Deposits handled separately by `compute-deposit-held`:
```
deposit-held = received(10K + 40K) - refunded(0) - offset(0) + transfers-in(30K) = 80K
```

### Derived: funding breakdown

```clojure
(defn funding-breakdown
  "Derive how principal was allocated at origination.
   Pure derivation from facts. Returns breakdown + balance check."
  [db contract-id]
  ;; Queries all facts for this contract, then computes:
  ;;
  ;; {:principal               750000M
  ;;  :fee-deductions           46687.50M  (sum of principal-allocation entities)
  ;;  :deposit-from-funding     40000M     (deposits with source :funding)
  ;;  :old-loan-settlement     100000M     (payments on other contracts with
  ;;                                        tx/source-contract = this contract)
  ;;  :merchant-disbursement   533312.50M  (disbursements type :funding)
  ;;  :excess-returned              0M     (disbursements type :excess-return)
  ;;  :total-allocated         720000M
  ;;  :balanced?               true}       (total-allocated + customer-prepayments
  ;;                                        = principal + customer-prepayments)
  )
```

### Origination workflow (operations sequence)

For a new loan:
1. `board-contract` — contract + schedule + fees (atomic)
2. `record-payment` — customer fee pre-payment, if any (back-dated from application)
3. `receive-deposit` — customer deposit pre-payment, if any (source `:customer`)
4. `record-principal-allocation` — funding allocated to settle fees
5. `receive-deposit` — deposit from funding (source `:funding`)
6. `record-disbursement` — actual amount to merchant (type `:funding`)
7. `record-disbursement` — customer excess return, if any (type `:excess-return`)

For a refi (additional steps between 5 and 6):
- `transfer-deposit` — collateral moved from old to new (backed by signed doc)
- `record-payment` on old contract — settlement (with `tx/source-contract`, backed by signed doc)

Each step is a separate Datomic transaction. They may occur on different dates.

---

## Disbursement Types (updated)

```
disbursement/type:
  :funding        — loan disbursement to merchant (from principal)
  :refund         — money returned to customer (reduces waterfall)
  :excess-return  — application excess returned to customer (does NOT affect waterfall)
```

In `compute-waterfall-total`, only `:refund` is subtracted. `:funding` and `:excess-return` are historical records that don't affect the waterfall.

---

## Related Schema Changes

Beyond the `principal-allocation/*` namespace:

```clojure
;; Store net disbursement as contractual fact (on the signed contract):
{:db/ident       :contract/net-disbursement
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one
 :db/doc         "Net financing amount (صافي مبلغ التمويل) from signed contract.
                  Contractual fact — what was agreed after fee deductions.
                  Derivable as principal - fees, but stored because it appears on the
                  signed contract and serves as reconciliation reference."}

;; Track deposit source (business fact):
{:db/ident       :deposit/source
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "Source of deposit funds: :funding (from principal) or :customer."}

;; Link inter-contract payments (already planned in settlement-and-fund-flows.md):
{:db/ident       :tx/source-contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "Contract that funded this payment. Links contracts via fund flows."}
```

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/lms/db.clj` | Add `principal-allocation/*` schema, `contract/net-disbursement`, `deposit/source`, `tx/source-contract` |
| `src/lms/contract.clj` | Query principal-allocations in `query-facts`, add to `compute-waterfall-total`, add `funding-breakdown` derivation |
| `src/lms/operations.clj` | Add `record-principal-allocation`, add `record-excess-return`, enhance `receive-deposit` with source, enhance `record-payment` with source-contract, update `record-disbursement` docs |
| `src/lms/waterfall.clj` | No changes (pure function, unchanged) |
| `test/lms/contract_test.clj` | Tests for principal-allocation in waterfall, funding breakdown, excess-return |
| `test/lms/operations_test.clj` | Tests for new operations |
