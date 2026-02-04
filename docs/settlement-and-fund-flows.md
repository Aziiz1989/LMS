# Settlement & Inter-Contract Fund Flows

## Overview

LMS manages a loan from boarding to full repayment or write-off. The lifecycle:

```
Board → Disburse → Collect Payments → Close (repaid / written-off)
```

Two capabilities are missing:

1. **Settlement** — derive what it costs to close a contract on a given date (pure derivation)
2. **Inter-contract fund flows** — record where funds come from / go to across contracts

"Refinancing" is not a special operation. It's what you observe when these building blocks are used together: calculate settlement → board new contract → disburse → record payment on old (sourced from new) → transfer deposit.

## What are the facts?

```
Fact:    Payment received on contract A, funded by contract B (:tx/source-contract)
Fact:    Deposit moved from contract A to contract B (deposit-transfer entity)
Fact:    Disbursement of X on contract B (existing :tx/type :disbursement)

Derived:
  "Settlement amount"    — computed from schedule + payments + date + penalty
  "Contract is settled"  — derived from total-outstanding = 0
  "Contracts are linked" — derived from payments with :tx/source-contract
  "Contract was disbursed" — derived from existence of :disbursement transaction
```

---

## Track A: Settlement Derivation

**File: `src/lms/settlement.clj`** (new namespace)

Pure function. Derives settlement amount from existing facts. Like `contract-state` derives installment statuses, this derives the cost to close a contract on a given date.

```clojure
(defn calculate-settlement
  "Derive settlement amount for a contract as of a given date.

  A derivation from facts — nothing stored.
  Internally uses Actual/360 for pro-rata profit in current period.

  Args:
  - state: contract state from contract/contract-state
  - settlement-date: java.util.Date
  - penalty-days: int (days of profit as penalty, provided by user)
  - opts: {:manual-override bigdec} optional profit override

  Returns:
  {:outstanding-principal  bigdec
   :accrued-profit         bigdec   ; earned to date (Actual/360)
   :profit-already-paid    bigdec
   :accrued-unpaid-profit  bigdec   ; accrued - paid
   :penalty-days           int
   :daily-profit           bigdec
   :penalty-amount         bigdec
   :unearned-profit        bigdec   ; future profit dropped
   :settlement-amount      bigdec}"
  [state settlement-date penalty-days & {:keys [manual-override]}]
  ...)
```

Internal helpers (private):
- `derive-annual-rate` — from step-up terms (current term's rate) or fallback derivation
- `derive-accrued-profit` — walks installments: past-due → full profit-due, current period → Actual/360 pro-rata, future → 0

---

## Track B: Inter-Contract Fund Flows

### B1: Schema — :tx/source-contract

**File: `src/lms/db.clj`**

```clojure
{:db/ident       :tx/source-contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "Contract that funded this payment. Links contracts via fund flows."}
```

When contract B's disbursement pays contract A, the payment on A has `:tx/source-contract → B`.

### B2: Enhance record-payment

**File: `src/lms/operations.clj`**

Add optional `source-contract-id` to `record-payment` and `build-payment-tx-data`:

```clojure
(defn record-payment
  [conn contract-id amount reference user-id
   & {:keys [note original-date source-contract-id]}]
  ;; When source-contract-id provided, add :tx/source-contract to tx-data
  ...)
```

### B3: Deposit Transfer

**File: `src/lms/operations.clj`**

```clojure
(defn transfer-deposit
  "Record deposit transfer between contracts.
  Single atomic event reflecting merchant authorization document."
  [conn from-contract-id to-contract-id amount user-id]
  ...)
```

**Schema additions in `src/lms/db.clj`:**
```clojure
{:db/ident :deposit-transfer/id           :db/valueType :db.type/uuid   :db/unique :db.unique/identity}
{:db/ident :deposit-transfer/from-contract :db/valueType :db.type/ref}
{:db/ident :deposit-transfer/to-contract   :db/valueType :db.type/ref}
{:db/ident :deposit-transfer/amount        :db/valueType :db.type/bigdec}
{:db/ident :deposit-transfer/author        :db/valueType :db.type/string}
{:db/ident :deposit-transfer/note          :db/valueType :db.type/string}
```

### B4: Update contract-state for deposit transfers

**File: `src/lms/contract.clj`**

Query `deposit-transfer` entities:
- `from-contract = this` → reduces deposit-held
- `to-contract = this` → increases deposit-held

### B5: Linked contracts query

**File: `src/lms/contract.clj`**

```clojure
(defn get-linked-contracts
  "Find contracts linked via inter-contract payments or deposit transfers."
  [db contract-id]
  ...)
```

---

## HTTP & Views

### Settlement endpoint

**File: `src/lms/handlers.clj`**

```
GET /contracts/:id/settlement?date=2025-06-15&penalty-days=90
```

Read-only derivation. Returns settlement breakdown.

### Deposit transfer endpoint

```
POST /contracts/:from-id/transfer-deposit
  body: {to-contract-id: uuid, amount: bigdec}
```

### Views

**File: `src/lms/views.clj`**

- Settlement form + result on contract detail page
- Deposit transfer form
- Linked contracts section (derived from inter-contract payments + deposit transfers)

---

## Tests

**File: `test/lms/settlement_test.clj`** (new)

- `settlement-no-payments-test`
- `settlement-mid-period-test` — Actual/360 pro-rata
- `settlement-with-penalty-test`
- `settlement-manual-override-test`
- `settlement-past-due-test`
- `inter-contract-payment-test` — payment with :tx/source-contract
- `deposit-transfer-test` — transfer + contract-state derivation
- `linked-contracts-test`

---

## Files to Modify / Create

| File | Action | Changes |
|------|--------|---------|
| `src/lms/settlement.clj` | **CREATE** | `calculate-settlement` derivation |
| `src/lms/db.clj` | MODIFY | `:tx/source-contract` + deposit-transfer entity schema |
| `src/lms/operations.clj` | MODIFY | Enhance `record-payment`, add `transfer-deposit` |
| `src/lms/contract.clj` | MODIFY | Deposit transfer in contract-state, `get-linked-contracts` |
| `src/lms/handlers.clj` | MODIFY | Settlement + deposit-transfer endpoints |
| `src/lms/views.clj` | MODIFY | Settlement form, transfer form, linked contracts |
| `test/lms/settlement_test.clj` | **CREATE** | All tests |

---

## Open Question: Rate Source

Settlement derivation uses Actual/360 internally: `principal × rate / 360`.
Rate is derived from existing facts:

1. **From step-up terms** — rate for the term containing the settlement date
2. **Fallback** — derive from contract data: `total_profit / principal / tenure_years`

Confirm during implementation.

---

## Verification

```clojure
;; Settlement
(settlement/calculate-settlement state #inst "2025-06-15" 90)

;; Inter-contract payment (new contract pays old)
(ops/record-payment conn old-id settlement-amount "REFI-001" "user-1"
  :source-contract-id new-id)

;; Transfer deposit
(ops/transfer-deposit conn old-id new-id 60000M "user-1")

;; Verify links
(contract/get-linked-contracts db old-id) ;; → [{:contract-id new-id :direction :from ...}]
```
