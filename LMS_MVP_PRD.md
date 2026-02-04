# LMS MVP: Murabaha Term Loans

## Overview

Loan management system for Murabaha term financing with step-up profit rates.

**Principle:** Store facts, derive state.

**Stack:** Clojure, Datomic, Fulcro/Re-frame

---

## 1. What We Store vs Derive

| Stored (Facts) | Derived (Computed) |
|----------------|-------------------|
| Contract terms | Balances |
| Schedule (fees + installments) | Paid amounts per fee/installment |
| Transactions (money in/out) | Fee/installment status |
| Step-up rules (EDN) | Security deposit held |
| | Credit balance |
| | Waterfall allocations |

---

## 2. Schema

```clojure
;; ════════════════════════════════════════════════════════════
;; CONTRACT
;; ════════════════════════════════════════════════════════════

{:db/ident       :contract/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/external-id
 :db/valueType   :db.type/string
 :db/unique      :db.unique/value
 :db/cardinality :db.cardinality/one
 :db/doc         "Jira key or LOS reference"}

{:db/ident       :contract/customer-name
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/customer-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/status
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         ":active :closed :written-off :refinanced"}

{:db/ident       :contract/start-date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/maturity-date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/principal
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one}

{:db/ident       :contract/security-deposit
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one
 :db/doc         "Required amount (held amount is derived)"}

{:db/ident       :contract/step-up-terms
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "EDN: step-up rules, nil if flat rate"}


;; ════════════════════════════════════════════════════════════
;; INSTALLMENT (schedule)
;; ════════════════════════════════════════════════════════════

{:db/ident       :installment/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity
 :db/cardinality :db.cardinality/one}

{:db/ident       :installment/contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident       :installment/seq
 :db/valueType   :db.type/long
 :db/cardinality :db.cardinality/one}

{:db/ident       :installment/due-date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one}

{:db/ident       :installment/principal-due
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one}

{:db/ident       :installment/profit-due
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one
 :db/doc         "Can change with rate adjustment"}


;; ════════════════════════════════════════════════════════════
;; FEE (schedule)
;; ════════════════════════════════════════════════════════════

{:db/ident       :fee/id
 :db/valueType   :db.type/uuid
 :db/unique      :db.unique/identity
 :db/cardinality :db.cardinality/one}

{:db/ident       :fee/contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident       :fee/type
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         ":management :late :processing :insurance"}

{:db/ident       :fee/amount
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one}

{:db/ident       :fee/due-date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one}


;; ════════════════════════════════════════════════════════════
;; TRANSACTION METADATA (on Datomic tx)
;; ════════════════════════════════════════════════════════════

{:db/ident       :tx/type
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         ":boarding :disbursement :payment :deposit-received
                  :deposit-refund :deposit-offset :rate-adjustment"}

{:db/ident       :tx/contract
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident       :tx/amount
 :db/valueType   :db.type/bigdec
 :db/cardinality :db.cardinality/one}

{:db/ident       :tx/reference
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Bank reference, external ID"}

{:db/ident       :tx/author
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "User ID from application auth"}

{:db/ident       :tx/note
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident       :tx/original-date
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "For migrated data"}

{:db/ident       :tx/migrated-from
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc         "Source ID from legacy system"}
```

---

## 3. Transaction Types

| Type | Description |
|------|-------------|
| `:boarding` | Contract created |
| `:disbursement` | Loan funded to customer |
| `:payment` | Customer payment (waterfall allocates to fees → installments) |
| `:deposit-received` | Security deposit collected |
| `:deposit-refund` | Security deposit returned |
| `:deposit-offset` | Deposit applied to balance (non-cash, flows through waterfall) |
| `:rate-adjustment` | Schedule profit amounts changed |

---

## 4. Step-Up Terms (EDN)

Stored on contract as EDN string:

```clojure
;; :contract/step-up-terms value
[{:term 1
  :installments [1 4]
  :rate 0.15}
 
 {:term 2
  :installments [5 8]
  :base-rate 0.18
  :on-time-rate 0.15}
 
 {:term 3
  :installments [9 12]
  :base-rate 0.21
  :on-time-rate 0.18}]
```

**Meaning:**
- Term 1: 15% (fixed)
- Term 2: 18% default, drops to 15% if term 1 paid on time
- Term 3: 21% default, drops to 18% if term 2 paid on time

---

## 5. Core Functions

### 5.1 Get Transactions

```clojure
(defn get-transactions
  "All transactions for a contract from tx log"
  [db contract-id]
  (->> (d/q '[:find ?tx ?type ?amount ?time ?ref ?note ?author
              :in $ ?contract
              :where
              [?tx :tx/contract ?contract]
              [?tx :tx/type ?type]
              [?tx :db/txInstant ?time]
              [(get-else $ ?tx :tx/amount nil) ?amount]
              [(get-else $ ?tx :tx/reference nil) ?ref]
              [(get-else $ ?tx :tx/note nil) ?note]
              [(get-else $ ?tx :tx/author nil) ?author]]
            db [:contract/id contract-id])
       (map (fn [[tx type amount time ref note author]]
              {:tx-id tx
               :type type
               :amount amount
               :timestamp time
               :reference ref
               :note note
               :author author}))
       (sort-by :timestamp)))
```

### 5.2 Waterfall

```clojure
(defn waterfall
  "Apply payments to fees then installments. Returns allocations."
  [fees installments total-payments]
  
  (let [;; Step 1: Fees (by due date)
        sorted-fees (sort-by :fee/due-date fees)
        
        {:keys [allocations remaining]}
        (reduce
          (fn [{:keys [allocations remaining]} fee]
            (let [payment (min remaining (:fee/amount fee))]
              {:allocations (conj allocations 
                              {:type :fee
                               :id (:fee/id fee)
                               :amount payment})
               :remaining (- remaining payment)}))
          {:allocations [] :remaining total-payments}
          sorted-fees)
        
        ;; Step 2: Installments (by seq, profit first)
        sorted-installments (sort-by :installment/seq installments)
        
        {:keys [allocations remaining]}
        (reduce
          (fn [{:keys [allocations remaining]} inst]
            (let [profit-due (:installment/profit-due inst)
                  principal-due (:installment/principal-due inst)
                  
                  to-profit (min remaining profit-due)
                  to-principal (min (- remaining to-profit) principal-due)]
              {:allocations (conj allocations
                              {:type :installment
                               :id (:installment/id inst)
                               :seq (:installment/seq inst)
                               :profit-paid to-profit
                               :principal-paid to-principal})
               :remaining (- remaining to-profit to-principal)}))
          {:allocations allocations :remaining remaining}
          sorted-installments)]
    
    {:allocations allocations
     :credit-balance remaining}))
```

### 5.3 Contract State

```clojure
(defn contract-state
  "Compute full contract state from facts"
  [db contract-id as-of]
  
  (let [;; Facts
        contract (d/entity db [:contract/id contract-id])
        fees (get-fees db contract-id)
        installments (get-installments db contract-id)
        txs (get-transactions db contract-id)
        
        ;; Payments (customer payments + deposit offsets)
        total-payments
        (->> txs
             (filter #(#{:payment :deposit-offset} (:type %)))
             (map :amount)
             (reduce + 0M))
        
        ;; Security deposit
        deposit-in (->> txs
                        (filter #(= :deposit-received (:type %)))
                        (map :amount)
                        (reduce + 0M))
        deposit-out (->> txs
                         (filter #(#{:deposit-refund :deposit-offset} (:type %)))
                         (map :amount)
                         (reduce + 0M))
        deposit-held (- deposit-in deposit-out)
        
        ;; Waterfall
        {:keys [allocations credit-balance]}
        (waterfall fees installments total-payments)
        
        ;; Enrich fees
        fee-allocations (filter #(= :fee (:type %)) allocations)
        enriched-fees
        (for [fee fees
              :let [alloc (first (filter #(= (:fee/id fee) (:id %)) fee-allocations))
                    paid (or (:amount alloc) 0M)]]
          {:id (:fee/id fee)
           :type (:fee/type fee)
           :amount (:fee/amount fee)
           :due-date (:fee/due-date fee)
           :paid paid
           :outstanding (- (:fee/amount fee) paid)
           :status (if (>= paid (:fee/amount fee)) :paid :unpaid)})
        
        ;; Enrich installments
        inst-allocations (filter #(= :installment (:type %)) allocations)
        enriched-installments
        (for [inst installments
              :let [alloc (first (filter #(= (:installment/id inst) (:id %)) inst-allocations))
                    profit-paid (or (:profit-paid alloc) 0M)
                    principal-paid (or (:principal-paid alloc) 0M)
                    total-due (+ (:installment/profit-due inst) 
                                 (:installment/principal-due inst))
                    total-paid (+ profit-paid principal-paid)]]
          {:id (:installment/id inst)
           :seq (:installment/seq inst)
           :due-date (:installment/due-date inst)
           :principal-due (:installment/principal-due inst)
           :profit-due (:installment/profit-due inst)
           :total-due total-due
           :principal-paid principal-paid
           :profit-paid profit-paid
           :total-paid total-paid
           :outstanding (- total-due total-paid)
           :status (derive-status inst total-paid total-due as-of)})]
    
    {:contract {:id (:contract/id contract)
                :external-id (:contract/external-id contract)
                :customer-name (:contract/customer-name contract)
                :status (:contract/status contract)
                :start-date (:contract/start-date contract)
                :maturity-date (:contract/maturity-date contract)
                :principal (:contract/principal contract)
                :security-deposit-required (:contract/security-deposit contract)}
     
     :fees enriched-fees
     :installments (sort-by :seq enriched-installments)
     :transactions txs
     
     :deposit-held deposit-held
     :credit-balance credit-balance
     
     ;; Totals
     :total-fees-due (reduce + (map :fee/amount fees))
     :total-fees-paid (reduce + (map :paid enriched-fees))
     :total-principal-due (reduce + (map :installment/principal-due installments))
     :total-principal-paid (reduce + (map :principal-paid enriched-installments))
     :total-profit-due (reduce + (map :installment/profit-due installments))
     :total-profit-paid (reduce + (map :profit-paid enriched-installments))
     :total-outstanding (+ (reduce + (map :outstanding enriched-fees))
                           (reduce + (map :outstanding enriched-installments)))}))

(defn derive-status [inst total-paid total-due as-of]
  (let [due-date (:installment/due-date inst)]
    (cond
      (>= total-paid total-due)                    :paid
      (and (pos? total-paid) (< total-paid total-due)) :partial
      (and (zero? total-paid) (.after as-of due-date)) :overdue
      :else                                        :scheduled)))
```

---

## 6. Operations

### 6.1 Record Payment

```clojure
(defn record-payment
  "Record a payment. State recomputes automatically."
  [conn contract-id amount reference user-id]
  
  (d/transact conn
    [{:db/id "datomic.tx"
      :tx/type :payment
      :tx/contract [:contract/id contract-id]
      :tx/amount amount
      :tx/reference reference
      :tx/author user-id}]))
```

### 6.2 Adjust Rate

```clojure
(defn adjust-rate
  "Change profit-due for a range of installments"
  [conn contract-id from-seq to-seq new-rate reason user-id]
  
  (let [db (d/db conn)
        installments (->> (get-installments db contract-id)
                          (filter #(<= from-seq (:installment/seq %) to-seq)))
        
        updates
        (for [inst installments
              :let [new-profit (calc-profit (:installment/principal-due inst) 
                                            new-rate
                                            1)]] ;; 1 month simplified
          {:db/id (:db/id inst)
           :installment/profit-due new-profit})]
    
    (d/transact conn
      (concat
        updates
        [{:db/id "datomic.tx"
          :tx/type :rate-adjustment
          :tx/contract [:contract/id contract-id]
          :tx/note (format "Installments %d-%d: %.2f%%. %s" 
                           from-seq to-seq (* 100 new-rate) reason)
          :tx/author user-id}]))))
```

### 6.3 Security Deposit

```clojure
(defn receive-deposit [conn contract-id amount user-id]
  (d/transact conn
    [{:db/id "datomic.tx"
      :tx/type :deposit-received
      :tx/contract [:contract/id contract-id]
      :tx/amount amount
      :tx/author user-id}]))

(defn refund-deposit [conn contract-id amount reason user-id]
  (d/transact conn
    [{:db/id "datomic.tx"
      :tx/type :deposit-refund
      :tx/contract [:contract/id contract-id]
      :tx/amount amount
      :tx/note reason
      :tx/author user-id}]))

(defn offset-deposit [conn contract-id amount reason user-id]
  ;; Flows through waterfall as payment
  (d/transact conn
    [{:db/id "datomic.tx"
      :tx/type :deposit-offset
      :tx/contract [:contract/id contract-id]
      :tx/amount amount
      :tx/note reason
      :tx/author user-id}]))
```

### 6.4 Evaluate Step-Up

```clojure
(defn evaluate-step-up
  "Check if customer earned lower rate for next term"
  [db contract-id completed-term as-of]
  
  (let [contract (d/entity db [:contract/id contract-id])
        rules (some-> contract :contract/step-up-terms edn/read-string)
        
        current-term-rule (->> rules
                               (filter #(= completed-term (:term %)))
                               first)
        next-term-rule (->> rules
                            (filter #(= (inc completed-term) (:term %)))
                            first)]
    
    (when next-term-rule
      (let [;; Check if term was paid on time
            state (contract-state db contract-id as-of)
            term-installments (->> (:installments state)
                                   (filter #(let [seq (:seq %)]
                                              (<= (first (:installments current-term-rule))
                                                  seq
                                                  (second (:installments current-term-rule))))))
            
            all-paid-on-time? (every? #(= :paid (:status %)) term-installments)
            
            ;; Determine suggested rate
            current-rate (or (:base-rate next-term-rule) (:rate next-term-rule))
            suggested-rate (if all-paid-on-time?
                             (:on-time-rate next-term-rule)
                             (:base-rate next-term-rule))]
        
        {:term (inc completed-term)
         :installment-range (:installments next-term-rule)
         :paid-on-time? all-paid-on-time?
         :current-rate current-rate
         :suggested-rate suggested-rate
         :rate-change? (not= current-rate suggested-rate)
         :action (if (and all-paid-on-time? (not= current-rate suggested-rate))
                   :apply-reduction
                   :no-change)}))))
```

### 6.5 Preview (Using d/with)

```clojure
(defn preview-payment
  "Preview what would happen if payment is applied"
  [db contract-id amount]
  
  (let [;; Current state
        before (contract-state db contract-id (java.util.Date.))
        
        ;; Speculative transaction
        tx-data [{:db/id "datomic.tx"
                  :tx/type :payment
                  :tx/contract [:contract/id contract-id]
                  :tx/amount amount}]
        
        ;; Apply speculatively
        {:keys [db-after]} (d/with db tx-data)
        
        ;; State after
        after (contract-state db-after contract-id (java.util.Date.))]
    
    {:before {:total-outstanding (:total-outstanding before)
              :credit-balance (:credit-balance before)}
     
     :after {:total-outstanding (:total-outstanding after)
             :credit-balance (:credit-balance after)}
     
     :changes
     (for [[before-inst after-inst] (map vector 
                                          (:installments before) 
                                          (:installments after))
           :when (not= (:total-paid before-inst) (:total-paid after-inst))]
       {:seq (:seq after-inst)
        :principal-applied (- (:principal-paid after-inst) (:principal-paid before-inst))
        :profit-applied (- (:profit-paid after-inst) (:profit-paid before-inst))
        :status-before (:status before-inst)
        :status-after (:status after-inst)})}))
```

---

## 7. Migration

### 7.1 Approach

```
Source:
├── Jira issues (contract metadata)
└── Payment table (transactions)

Target:
├── Contract entities
├── Fee entities (from Jira)
├── Installment entities (from Jira schedule)
└── Transaction metadata (from payment table)

Verification:
└── contract-state(new-db) = expected balances from Jira
```

### 7.2 Mapping

| Payment Table | Transaction Type |
|---------------|------------------|
| Management Fee, Customer | `:payment` (waterfall allocates to fees) |
| Funding, AlRaedah | `:disbursement` |
| Transfer, Customer | `:payment` |
| Transfer, AlRaedah (final) | `:deposit-refund` |
| Principle, AlRaedah | Skip (internal booking) |

### 7.3 Script

```clojure
(defn migrate-contract [conn jira-data payments]
  (let [contract-id (random-uuid)
        contract-ref [:contract/id contract-id]]
    
    ;; 1. Create contract + schedule
    (d/transact conn
      (concat
        ;; Contract
        [{:contract/id contract-id
          :contract/external-id (:key jira-data)
          :contract/customer-name (:borrower-name jira-data)
          :contract/customer-id (:borrower-id jira-data)
          :contract/status :active
          :contract/start-date (:start-date jira-data)
          :contract/maturity-date (:maturity-date jira-data)
          :contract/principal (:principal jira-data)
          :contract/security-deposit (:security-deposit jira-data)
          :contract/step-up-terms (some-> (:step-up-terms jira-data) pr-str)}]
        
        ;; Fees
        (for [fee (:fees jira-data)]
          {:fee/id (random-uuid)
           :fee/contract contract-ref
           :fee/type (keyword (:type fee))
           :fee/amount (:amount fee)
           :fee/due-date (:due-date fee)})
        
        ;; Installments
        (for [inst (:schedule jira-data)]
          {:installment/id (random-uuid)
           :installment/contract contract-ref
           :installment/seq (:seq inst)
           :installment/due-date (:due-date inst)
           :installment/principal-due (:principal inst)
           :installment/profit-due (:profit inst)})
        
        ;; Boarding transaction
        [{:db/id "datomic.tx"
          :tx/type :boarding
          :tx/contract contract-ref
          :tx/author "migration"}]))
    
    ;; 2. Record transactions
    (doseq [pmt (sort-by :date payments)]
      (let [tx-type (map-tx-type pmt)]
        (when tx-type
          (d/transact conn
            [{:db/id "datomic.tx"
              :tx/type tx-type
              :tx/contract contract-ref
              :tx/amount (:amount pmt)
              :tx/reference (:reference pmt)
              :tx/original-date (:date pmt)
              :tx/migrated-from (:id pmt)
              :tx/author "migration"}]))))
    
    ;; 3. Verify
    (let [state (contract-state (d/db conn) contract-id (java.util.Date.))
          expected-outstanding (:outstanding jira-data)]
      (when (not= (:total-outstanding state) expected-outstanding)
        (log/warn "Mismatch" 
                  {:contract contract-id
                   :expected expected-outstanding
                   :actual (:total-outstanding state)})))))

(defn map-tx-type [{:keys [summary paid-by source]}]
  (cond
    (= summary "Funding")                    :disbursement
    (and (= summary "Transfer")
         (= paid-by "AlRaedah Finance")
         (= source "Disbursement"))          :deposit-refund
    (= summary "Transfer")                   :payment
    (= summary "Management Fee")             :payment  ;; Waterfall handles allocation
    (= summary "Principle")                  nil       ;; Skip internal booking
    :else                                    :payment))
```

---

## 8. UI

### 8.1 Contract View

```
┌─────────────────────────────────────────────────────────────────┐
│  LOAN-2023-001                                        [Active]  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Customer: Al-Rashid Trading Co.                                │
│  Start: 14 Sep 2023              Maturity: 14 Sep 2025          │
│                                                                 │
│  ┌──────────────────────┐    ┌────────────────────────────────┐│
│  │ TERMS                │    │ BALANCES                       ││
│  │                      │    │                                ││
│  │ Principal: 10,000,000│    │ Fees Outstanding:          0   ││
│  │                      │    │ Principal Outstanding:1,200,000││
│  │ Security Deposit:    │    │ Profit Outstanding:    120,000 ││
│  │   Required: 34,233   │    │ ────────────────────────────── ││
│  │   Held:     34,233   │    │ Total Outstanding:   1,320,000 ││
│  │                      │    │                                ││
│  │                      │    │ Credit Balance:              0 ││
│  └──────────────────────┘    └────────────────────────────────┘│
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  FEES                                                           │
├─────────────────────────────────────────────────────────────────┤
│  Type        │ Amount    │ Due        │ Paid      │ Status     │
│  ────────────┼───────────┼────────────┼───────────┼────────────│
│  Management  │ 2,500     │ 04 Apr 23  │ 2,500     │ ● Paid     │
│  Management  │ 262,000   │ 07 Sep 23  │ 262,000   │ ● Paid     │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  SCHEDULE                                                       │
├─────────────────────────────────────────────────────────────────┤
│  #  │ Due        │ Principal │ Profit  │ Paid      │ Status    │
│  ───┼────────────┼───────────┼─────────┼───────────┼───────────│
│  1  │ 14 Oct 23  │ 833,333   │ 125,000 │ 958,333   │ ● Paid    │
│  2  │ 14 Nov 23  │ 833,333   │ 125,000 │ 958,333   │ ● Paid    │
│  3  │ 14 Dec 23  │ 833,333   │ 125,000 │ 958,333   │ ● Paid    │
│  4  │ 14 Jan 24  │ 833,333   │ 125,000 │ 500,000   │ ◐ Partial │
│  5  │ 14 Feb 24  │ 833,333   │ 104,167 │         0 │ ○ Due     │
│  ...                                                            │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  ACTIONS                                                        │
│                                                                 │
│  [+ Payment]  [Adjust Rate]  [Deposit Refund]                   │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  EVENTS                                                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  14 Feb 25  RATE ADJUSTMENT                          ops_user   │
│             Installments 5-8: 15.00%. Term 1 paid on time       │
│                                                                 │
│  14 Jan 25  PAYMENT             500,000   FT-ANB-108  customer  │
│                                                                 │
│  14 Dec 24  PAYMENT             958,333   FT-ANB-089  customer  │
│                                                                 │
│  ...                                                            │
│                                                                 │
│  14 Sep 23  DISBURSEMENT     10,000,000              system     │
│                                                                 │
│  07 Sep 23  PAYMENT             262,000   FT-ANB-009  customer  │
│             (allocated to: Management Fee)                      │
│                                                                 │
│  04 Apr 23  PAYMENT               2,500   FT-ANB-005  customer  │
│             (allocated to: Management Fee)                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 Payment Modal

```
┌─────────────────────────────────────────────────────┐
│  Record Payment                                [X]  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Amount *        [_______________] SAR              │
│                                                     │
│  Reference *     [_______________]                  │
│                                                     │
│  ┌───────────────────────────────────────────────┐ │
│  │ PREVIEW                                       │ │
│  │                                               │ │
│  │ Installment #4:                               │ │
│  │   +458,333 (completes, status → Paid)         │ │
│  │                                               │ │
│  │ Installment #5:                               │ │
│  │   +41,667 (partial)                           │ │
│  │                                               │ │
│  │ Outstanding: 1,320,000 → 820,000              │ │
│  │ Credit Balance: 0                             │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│                        [Cancel]  [Record Payment]   │
└─────────────────────────────────────────────────────┘
```

### 8.3 Rate Adjustment Modal

```
┌─────────────────────────────────────────────────────┐
│  Adjust Rate                                   [X]  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  From Installment *    [____5____]                  │
│  To Installment *      [____8____]                  │
│                                                     │
│  New Rate *            [___15.00_] %                │
│                                                     │
│  Reason *              [________________________]   │
│                        [________________________]   │
│                                                     │
│  ┌───────────────────────────────────────────────┐ │
│  │ STEP-UP CHECK                                 │ │
│  │                                               │ │
│  │ Term 1 (inst 1-4): ✓ All paid on time         │ │
│  │ Suggested rate for Term 2: 15% (was 18%)      │ │
│  │                                               │ │
│  │ [Apply Suggested Rate]                        │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│  ┌───────────────────────────────────────────────┐ │
│  │ IMPACT                                        │ │
│  │                                               │ │
│  │ Profit change: -125,000 SAR                   │ │
│  │ Affects installments: 5, 6, 7, 8              │ │
│  │                                               │ │
│  │ Note: Installments already paid will show     │ │
│  │ overpayment, which flows to next installment. │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│                              [Cancel]  [Apply]      │
└─────────────────────────────────────────────────────┘
```

---

## 9. Queries

### Contract List

```clojure
(defn list-contracts [db filters]
  (d/q '[:find ?id ?ext-id ?name ?status
         :in $ ?status-filter
         :where
         [?e :contract/id ?id]
         [?e :contract/external-id ?ext-id]
         [?e :contract/customer-name ?name]
         [?e :contract/status ?status]
         [(or (nil? ?status-filter) (= ?status ?status-filter))]]
       db (:status filters)))
```

### Event History

```clojure
(defn event-history [db contract-id]
  (get-transactions db contract-id))
```

### Point-in-Time State

```clojure
(defn state-as-of [conn contract-id as-of-date]
  (let [db (d/as-of (d/db conn) as-of-date)]
    (contract-state db contract-id as-of-date)))
```

---

## 10. Summary

### Schema (4 entities, ~25 attributes)

- Contract: identity + terms + step-up rules
- Installment: schedule
- Fee: schedule
- Transaction metadata: what happened

### Operations

- `record-payment` — just record, waterfall computes allocation
- `adjust-rate` — update profit-due, state recomputes
- `receive-deposit` / `refund-deposit` / `offset-deposit`
- `evaluate-step-up` — helper for business decision

### Everything Derived

- Paid amounts per fee/installment
- Status (paid/partial/overdue/scheduled)
- Deposit held
- Credit balance
- All totals

### Migration

- Create schedule from Jira
- Record transactions from payment table
- Verify: computed state = expected balances

---

## 11. Timeline

| Week | Deliverable |
|------|-------------|
| 1 | Schema, waterfall, contract-state |
| 2 | Migration script, verification |
| 3-4 | UI: contract view, payment modal |
| 5 | Rate adjustment, step-up evaluation |
| 6 | Testing, migration, go-live |
