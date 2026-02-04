# Settlement Calculation

## Overview

Settlement derives the cost to close a contract on a given date. It is a **pure derivation** — nothing stored, everything computed from existing facts. It mirrors how `contract-state` derives installment statuses, but answers a different question: "what does the customer owe to close this contract today?"

Settlement is NOT a special operation. It's a read-only computation that can be called at any time without side effects.

---

## Remaining Principal

### What it is

Remaining principal is the outstanding principal balance at the **start of each installment period** — the balance that profit accrues on. It is a **contractual fact**: part of the agreed amortization schedule, determined at contract signing.

### Why it's stored

The installment schedule stores `principal-due` and `profit-due`, but not the balance those values were calculated against. Without remaining principal:

- You cannot derive the rate (`profit-due / ???`)
- `profit-due / principal-due` is wrong — `principal-due` is the repayment, not the balance
- Profit-only installments have `principal-due = 0`, causing division by zero

Remaining principal has the same status as `profit-due`: computable from the contract terms but stored because it's a contractual fact that was agreed upon at signing.

### Schema

```clojure
{:db/ident       :installment/remaining-principal
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one
 :db/doc         "Outstanding principal balance at start of this period.
                  Contractual fact from amortization schedule."}
```

### Boarding requirement

Every installment must include `:installment/remaining-principal` at boarding. The `board-contract` operation validates this — throws if missing. No fallback derivation.

### Relationship to other installment fields

```
remaining-principal  = balance at START of this period
principal-due        = principal repayment during this period
profit-due           = profit charged during this period (on remaining-principal)
end-of-period balance = remaining-principal - principal-due  (not stored, trivially derived)
```

### Examples by schedule structure

**Bullet / balloon** (WakeCap: 11 profit-only + 1 amortizing):
```
Principal: 9,652,509.65 SAR
Rate: 16% annual flat

Inst  remaining-principal  principal-due   profit-due
  1     9,652,509.65              0.00     128,700.13
  2     9,652,509.65              0.00     128,700.13
 ...
 11     9,652,509.65              0.00     128,700.13
 12     9,652,509.65      9,652,509.65     128,700.13
```

Remaining principal is constant (no principal repaid until bullet at maturity).

**Diminishing (constant rate)**:
```
Principal: 1,200,000. Rate: 15% annual.

Inst  remaining-principal  principal-due   profit-due
  1     1,200,000           100,000         15,000.00
  2     1,100,000           100,000         13,750.00
  3     1,000,000           100,000         12,500.00
 ...
 12       100,000           100,000          1,250.00
```

Profit decreases as remaining principal declines. Rate is constant.

**Flat profit (constant installment amount)**:
```
Principal: 1,200,000. Monthly profit: 15,000 (flat).

Inst  remaining-principal  principal-due   profit-due
  1     1,200,000           100,000         15,000
  2     1,100,000           100,000         15,000
 ...
 12       100,000           100,000         15,000
```

Profit is constant — the effective rate per period increases as balance declines. This is a valid Murabaha structure.

**Grace period** (no payment due):
```
Principal: 1,000,000. Rate: 12% annual. 3-month grace.

Inst  remaining-principal  principal-due  profit-due   Notes
  1     1,000,000                  0           0       Grace: profit capitalizes
  2     1,010,000                  0           0       Grace: 10K capitalized from period 1
  3     1,020,100                  0           0       Grace: 10.1K capitalized from period 2
  4     1,030,301           103,030.10     10,303.01   First payment period
 ...
```

During grace periods (`principal-due = 0 AND profit-due = 0`), uncollected profit adds to the balance. The boarding schedule computes this capitalization and stores the resulting remaining-principal. Settlement doesn't need to recompute it.

---

## Rate Derivation

### Formula

```
per-period-rate = profit-due / remaining-principal
```

This works for ALL schedule structures because remaining-principal is always the balance that profit accrues on.

### Annualization for Actual/360

```
period-days   = days(current-installment.due-date - previous-installment.due-date)
                (use contract/start-date for installment 1)
annual-rate   = per-period-rate × (360 / period-days)
daily-rate    = annual-rate / 360
```

### Why not other approaches

| Approach | Problem |
|----------|---------|
| Step-up terms | Describes policy, not actuality. After `adjust-rate`, the applied rate may differ from the contractual base-rate. |
| `profit-due / principal-due` | `principal-due` is repayment, not balance. Division by zero for profit-only installments. |
| `total_profit / principal / tenure` | Blended average. Wrong for step-up contracts where rate changes per term. |
| `profit-due / principal-due × 12` | Assumes monthly periods. Fails for irregular schedules. |

### Worked examples

**WakeCap** — settling installment 6 (mid-period):
```
remaining-principal[6] = 9,652,509.65
profit-due[6]          = 128,700.13
per-period-rate        = 128,700.13 / 9,652,509.65 = 0.013333...

period-start           = installment 5 due-date (Dec 7, 2025)
period-end             = installment 6 due-date (Jan 7, 2026)
period-days            = 31
annual-rate            = 0.013333 × (360 / 31) = 0.15484 (≈16% annualized to 360 from 31-day period)
```

**Flat profit** — settling at installment 6 (remaining = 700K):
```
remaining-principal[6] = 700,000
profit-due[6]          = 15,000
per-period-rate        = 15,000 / 700,000 = 0.02143

period-days            = 31
annual-rate            = 0.02143 × (360 / 31) = 0.2489
```

The rate is higher than for installment 1 (0.0125 × 360/31 = 0.145) — correct for flat-profit structures where the effective rate increases as balance declines.

---

## Settlement Calculation

### Function signature

```clojure
(defn calculate-settlement
  "Derive settlement amount for a contract as of a given date.

  Pure derivation from facts — nothing stored.
  Internally uses Actual/360 for pro-rata profit in current period.

  Args:
  - state: contract state from contract/contract-state
  - settlement-date: java.util.Date
  - penalty-days: int (days of profit as penalty, provided by user)
  - opts: {:manual-override bigdec} optional profit override

  Returns settlement breakdown map."
  [state settlement-date penalty-days & {:keys [manual-override]}]
  ...)
```

### Installment classification

Each installment is classified relative to the settlement date:

```
PAST:    due-date ≤ settlement-date  →  full profit-due counts as accrued
CURRENT: first installment where due-date > settlement-date  →  pro-rata Actual/360
FUTURE:  all remaining installments  →  0 accrued (profit forgiven)
```

### Period boundaries

```
period-start  = previous installment's due-date
                (or contract/start-date for installment 1)
period-end    = current installment's due-date
accrued-days  = days(settlement-date - period-start)
period-days   = days(period-end - period-start)
```

### Accrued profit computation

```
accrued-profit = Σ(past installments: profit-due)
               + pro-rata(current period)

where pro-rata(current period):
  per-period-rate = profit-due[current] / remaining-principal[current]
  annual-rate     = per-period-rate × (360 / period-days)
  daily-profit    = remaining-principal[current] × annual-rate / 360
  accrued         = daily-profit × accrued-days
```

### Formula

```
settlement-amount = outstanding-principal
                  + accrued-unpaid-profit
                  + outstanding-fees
                  + penalty-amount
                  - credit-balance

Where:
  outstanding-principal  = total-principal-due - total-principal-paid
  accrued-unpaid-profit  = accrued-profit - total-profit-paid
  outstanding-fees       = total-fees-due - total-fees-paid
  daily-profit           = outstanding-principal × annual-rate / 360
  penalty-amount         = daily-profit × penalty-days
  credit-balance         = from contract-state (overpayments)
  unearned-profit        = total-profit-due - accrued-profit
                           (informational — not in settlement-amount)
```

### Return map

```clojure
{;; Principal
 :outstanding-principal   bigdec

 ;; Profit breakdown
 :accrued-profit          bigdec   ;; total earned to date (past + pro-rata current)
 :profit-already-paid     bigdec   ;; from contract-state
 :accrued-unpaid-profit   bigdec   ;; accrued - paid
 :unearned-profit         bigdec   ;; future profit forgiven (informational)

 ;; Fees
 :outstanding-fees        bigdec

 ;; Penalty
 :penalty-days            int
 :daily-profit            bigdec
 :penalty-amount          bigdec

 ;; Credits
 :credit-balance          bigdec

 ;; Result
 :settlement-amount       bigdec   ;; the number that matters

 ;; Rate details (for transparency)
 :annual-rate             bigdec   ;; derived from current period
 :current-period-start    date
 :current-period-end      date
 :accrued-days            int

 ;; Override
 :manual-override?        boolean}
```

### Manual override

When `:manual-override` is provided, it replaces `accrued-unpaid-profit` in the formula. The settlement breakdown should clearly flag this with `:manual-override? true`.

### Edge cases

| Scenario | Behavior |
|----------|----------|
| Settlement date = installment due-date | That installment is PAST (≤), full profit-due accrued |
| Settlement before first installment | All installments are CURRENT/FUTURE, only pro-rata of period 1 |
| Settlement after last installment | All installments are PAST, full profit accrued, no pro-rata |
| Contract fully paid | settlement-amount = 0 (or negative if overpaid → credit) |
| Grace period installment is "current" | profit-due = 0, remaining-principal available, rate from nearest non-grace |

---

## Files Modified

| File | Change |
|------|--------|
| `src/lms/db.clj` | Add `:installment/remaining-principal` schema |
| `src/lms/operations.clj` | Boarding validation for remaining-principal |
| `src/lms/views.clj` | Add remaining-principal to installments table |
| `src/lms/settlement.clj` | **NEW** — `calculate-settlement` function |
| `test/lms/settlement_test.clj` | **NEW** — settlement tests |

---

## Verification

```clojure
;; WakeCap contract — settle mid-period (Dec 20, 2025, between inst 5 and 6)
(def state (contract/contract-state db contract-id #inst "2025-12-20"))
(settlement/calculate-settlement state #inst "2025-12-20" 90)

;; Expected:
;; - outstanding-principal: 9,652,509.65 (all principal still owed)
;; - accrued-profit: 5 × 128,700.13 + pro-rata(13 days of period 6)
;; - penalty: 90 × daily-profit
;; - settlement-amount: principal + accrued-unpaid-profit + fees + penalty - credit

;; Diminishing schedule — settle after 3 payments
;; Flat profit — verify rate varies by period
;; Grace period — verify capitalization doesn't break settlement
```
