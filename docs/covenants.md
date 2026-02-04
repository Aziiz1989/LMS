# Covenants

## Overview

Covenants are contractual conditions established at signing. They describe rules and obligations that may trigger future actions (e.g., rate adjustments, deposit calls). The system does not act on them automatically — business users review covenants and manually trigger the corresponding operations.

The payment schedule IS the contract. Covenants are conditions ON the contract.

---

## Current State

Step-up terms are stored as an EDN string on the contract:

```clojure
:contract/step-up-terms  ;; :db.type/string — EDN
```

This covers one covenant type. Other covenant types exist in contracts but are not yet modeled in the system.

---

## Characteristics

- **Immutable** — covenants are facts from signing, they never change after boarding
- **Documentation** — business reviews them when processing merchant requests
- **Multiple types** — step-up, early settlement, collateral, insurance, and others
- **Not operational** — the system doesn't interpret them automatically; humans read and decide
- **Varied structure** — each covenant type has different internal data

---

## Relationship to Operations

```
Covenant (fact from signing)     →  describes what COULD happen
Merchant requests review         →  external business event
Business checks covenant terms   →  human reads covenant
Business approves action         →  human decision
Operation recorded               →  adjust-rates, offset-deposit, etc.
```

The covenant explains WHY an action was taken. The operation records WHAT happened. These are connected by meaning, not by database reference.

Example — step-up rate adjustment:

```
Covenant:  "Rate reduces from 20% to 15% for installments 5-8
            if all Term 1 installments paid by due date."

Event:     Merchant requests rate review after Term 1 completion.

Decision:  Business confirms Term 1 paid on time.

Action:    (adjust-rates conn contract-id
             [{:from-seq 5 :to-seq 8 :rate 0.15M}]
             "Step-up review: Term 1 paid on time" "user-1")
```

---

## Proposed Model

```clojure
:covenant/id          ;; UUID identity
:covenant/contract    ;; ref to parent contract
:covenant/type        ;; keyword (:step-up, :early-settlement, :collateral, :insurance, ...)
:covenant/terms       ;; EDN string — varies by type, opaque to database
:covenant/description ;; human-readable text for business review
```

### Why entity, not a field on contract

- A contract has multiple covenants
- Queryable by type: "which active contracts have step-up covenants?"
- Each covenant is independently displayable in the UI
- Boarded with the contract (same transaction) but a separate thing

### Why EDN string for terms

- The system doesn't query into the terms structure — humans read them
- Terms vary by covenant type — step-up has installment ranges and rates, collateral has thresholds
- Business users see `:covenant/description`, not the EDN
- If programmatic interpretation is needed later, parse EDN in application code

### Why queryable type

- Business asks: "show me all contracts with step-up covenants due for review"
- This is a Datalog query on `:covenant/type`, not a string parse
- The covenant type is the queryable dimension; the terms are the detail

---

## Boarding Example

```clojure
(board-contract conn
  {:contract/id contract-id
   :contract/principal 1000000M
   ...}
  fees
  installments
  [{:covenant/id (random-uuid)
    :covenant/type :step-up
    :covenant/description
    "On-time payment discount: Rate reduces from 20% to 15%
     for Term 2 (installments 5-8) if all Term 1 installments
     paid by due date."
    :covenant/terms
    (pr-str [{:term 1 :installments [1 4] :rate 0.20}
             {:term 2 :installments [5 8]
              :base-rate 0.20 :on-time-rate 0.15}])}

   {:covenant/id (random-uuid)
    :covenant/type :collateral
    :covenant/description
    "Security deposit of 5% (SAR 50,000) must be maintained
     for the duration of the contract."
    :covenant/terms
    (pr-str {:deposit-pct 0.05 :amount 50000M})}

   {:covenant/id (random-uuid)
    :covenant/type :early-settlement
    :covenant/description
    "Early settlement permitted with 90-day profit penalty."
    :covenant/terms
    (pr-str {:penalty-days 90})}]
  "user-1")
```

---

## Open Questions

- What are the full set of covenant types across existing contracts?
- Does business need to track covenant compliance status (e.g., "Term 1 condition met")?
- Should the rate adjustment tx reference the covenant entity (`:tx/covenant` ref) for formal traceability, or is the tx note sufficient?
- Are there covenants that trigger automatically (e.g., late fee generation), or are all actions manual?
