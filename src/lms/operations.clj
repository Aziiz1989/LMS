(ns lms.operations
  "Write operations for the LMS.

   This namespace contains all operations that modify state (transact to Datomic).
   Each operation creates business entities (payment/*, disbursement/*, deposit/*)
   with recording metadata on the transaction (tx/author, tx/note).

   Philosophy: Operations record FACTS as entities. State derivation happens
   elsewhere (in lms.contract namespace). Business facts (amount, date, reference)
   live on entities. Recording facts (who entered it, when) live on tx metadata.

   Operations:
   - record-payment: Customer payment received → payment/* entity
   - record-disbursement: Loan funding sent → disbursement/* entity (type :funding)
   - record-refund: Money returned to customer → disbursement/* entity (type :refund)
   - receive-deposit, refund-deposit, offset-deposit: Security deposit ops → deposit/* entities
   - retract-payment, retract-disbursement, retract-deposit: Error corrections
   - retract-contract: Retract contract + all associated entities (error correction)
   - adjust-rate: Change profit-due for installments (step-up) — admin event, tx metadata only
   - preview-payment: Speculative transaction to show allocation before commit"
  (:require [datomic.client.api :as d]
            [lms.contract :as contract]
            [lms.waterfall :as waterfall]
            [lms.db :as db]))

;; ============================================================
;; Payment Operations
;; ============================================================

(defn- build-payment-entity
  "Build transaction data for a payment entity.

   This is the single source of truth for payment transaction structure.
   Used by both record-payment (actual commit) and preview-payment (speculative).

   Creates a payment/* entity (business fact) plus recording metadata (tx).

   Args:
   - contract-id: UUID of contract
   - amount: Payment amount (bigdec, must be positive)
   - date: Business date — when money was received
   - reference: External reference (bank transfer ID, check number, etc.)
   - user-id: User ID performing the operation
   - note: Optional note about the payment
   - channel: Optional payment channel (e.g., \"bank-transfer\", \"check\")
   - source-contract: Optional UUID of contract that funded this payment
                      (e.g., new contract paying off old in refi)

   Returns: Transaction data vector"
  [contract-id amount date reference user-id
   & {:keys [note channel source-contract]}]
  (when-not (pos? amount)
    (throw (ex-info "Payment amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  [(cond-> {:payment/id (random-uuid)
            :payment/amount amount
            :payment/date date
            :payment/contract [:contract/id contract-id]
            :payment/reference reference}
     channel (assoc :payment/channel channel)
     source-contract (assoc :payment/source-contract
                            [:contract/id source-contract]))
   (db/recording-metadata user-id :note note)])

(defn record-payment
  "Record a customer payment.

   Creates a payment/* entity. The payment amount flows through the
   waterfall (fees → installments). State recomputes automatically
   on next contract-state call.

   Business date (:payment/date) is when money was received.
   Recording date (txInstant) is when it was entered in the system.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Payment amount (bigdec, must be positive)
   - date: Business date — when money was received
   - reference: External reference (bank transfer ID, check number, etc.)
   - user-id: User performing the operation
   - note: Optional note about the payment
   - channel: Optional payment channel (e.g., \"bank-transfer\", \"check\")
   - source-contract: Optional UUID of contract that funded this payment
                      (refi settlement where new contract pays off old)

   Returns: Transaction result map

   Usage:
     (record-payment conn contract-id 1000000M #inst \"2024-01-15\"
                     \"FT-ANB-123\" \"user-1\"
                     :note \"Wire transfer from customer account\"
                     :channel \"bank-transfer\")
     ;; Refi settlement:
     (record-payment conn old-contract-id 100000M #inst \"2024-06-01\"
                     \"REFI-SETTLE\" \"user-1\"
                     :source-contract new-contract-id)"
  [conn contract-id amount date reference user-id
   & {:keys [note channel source-contract]}]
  (d/transact conn
              {:tx-data (build-payment-entity contract-id amount date reference user-id
                                              :note note
                                              :channel channel
                                              :source-contract source-contract)}))

(defn preview-payment
  "Preview what would happen if a payment is applied (without committing).

   Queries facts once, runs waterfall twice (before and after), diffs
   the enriched allocations. No d/with speculation needed — waterfall
   is a pure function of (fees, installments, total-amount).

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Payment amount to preview

   Returns map:
   {:before {:total-outstanding ... :credit-balance ...}
    :after {:total-outstanding ... :credit-balance ...}
    :changes [{:seq ... :principal-applied ... :profit-applied ...}]}

   Usage:
     (preview-payment conn contract-id 500000M)"
  [conn contract-id amount]
  (let [db (d/db conn)
        now (java.util.Date.)

        ;; Query facts once
        {:keys [fees installments payments disbursements deposits
                principal-allocations]}
        (contract/query-facts db contract-id)

        ;; Current waterfall
        current-total (contract/compute-waterfall-total
                        payments disbursements deposits principal-allocations)
        before-wf (waterfall/waterfall fees installments current-total)
        before-fees (contract/enrich-fees fees (:allocations before-wf))
        before-insts (contract/enrich-installments
                       installments (:allocations before-wf) {} now)

        ;; Waterfall with additional payment — pure function, no d/with needed
        after-wf (waterfall/waterfall fees installments (+ current-total amount))
        after-fees (contract/enrich-fees fees (:allocations after-wf))
        after-insts (contract/enrich-installments
                      installments (:allocations after-wf) {} now)

        ;; Compute outstanding from enriched data
        before-outstanding (+ (reduce + 0M (map :outstanding before-fees))
                             (reduce + 0M (map :outstanding before-insts)))
        after-outstanding (+ (reduce + 0M (map :outstanding after-fees))
                            (reduce + 0M (map :outstanding after-insts)))

        ;; Diff fees
        fee-changes
        (for [[bf af] (map vector before-fees after-fees)
              :when (not= (:paid bf) (:paid af))]
          {:type :fee
           :id (:id af)
           :description (str (name (:type af)) " fee - "
                            (if (= :paid (:status af)) "PAID" "partial"))
           :amount (- (:paid af) (:paid bf))})

        ;; Diff installments
        inst-changes
        (for [[bi ai] (map vector before-insts after-insts)
              :let [profit-applied (- (:profit-paid ai) (:profit-paid bi))
                    principal-applied (- (:principal-paid ai) (:principal-paid bi))]
              :when (or (pos? profit-applied) (pos? principal-applied))]
          {:type :installment
           :id (:id ai)
           :seq (:seq ai)
           :profit-applied profit-applied
           :principal-applied principal-applied
           :description (str "Installment #" (:seq ai)
                            (when (pos? profit-applied)
                              (str " - Profit: " (format "%.2f" (double profit-applied))))
                            (when (pos? principal-applied)
                              (str " - Principal: " (format "%.2f" (double principal-applied)))))
           :amount (+ profit-applied principal-applied)})]

    {:before {:total-outstanding before-outstanding
              :credit-balance (:credit-balance before-wf)}
     :after {:total-outstanding after-outstanding
             :credit-balance (:credit-balance after-wf)}
     :changes (concat fee-changes inst-changes)}))

(defn- retract-entity
  "Retract any entity by namespace and ID — shared implementation for all retract-* ops.

   Uses [:db/retractEntity] to remove the entity from the current database.
   TX metadata records WHO corrected it, WHY, and WHAT was corrected.
   Datomic history preserves the retracted datoms for forensics via (d/history db)."
  [conn ns-kw entity-id reason user-id & {:keys [note]}]
  (let [lookup-ref [(keyword (name ns-kw) "id") entity-id]]
    (d/transact conn
                {:tx-data [[:db/retractEntity lookup-ref]
                           (cond-> {:db/id "datomic.tx"
                                    :tx/reason reason
                                    :tx/corrects lookup-ref
                                    :tx/author user-id}
                             note (assoc :tx/note note))]})))

(defn retract-payment
  "Retract a payment entity — data correction for recording errors.

   Uses [:db/retractEntity] to remove the payment from the current database.
   TX metadata records WHO corrected it, WHY, and WHAT was corrected.
   Datomic history preserves the retracted datoms for forensics via (d/history db).

   Use this for: wrong amount (typo), wrong contract, accidental/duplicate entry.
   Do NOT use for real-world undos — use record-refund for money returned.

   Args:
   - conn: Datomic connection
   - payment-id: UUID of payment to retract (:payment/id)
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional free-text explanation

   Returns: Transaction result map

   Usage:
     (retract-payment conn payment-uuid :duplicate-removal \"user-1\"
                      :note \"Duplicate of FT-ANB-123\")"
  [conn payment-id reason user-id & {:keys [note]}]
  (retract-entity conn :payment payment-id reason user-id :note note))

(defn retract-disbursement
  "Retract a disbursement entity — data correction for recording errors.

   Same pattern as retract-payment. See retract-payment for full documentation.

   Args:
   - conn: Datomic connection
   - disbursement-id: UUID of disbursement to retract (:disbursement/id)
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional free-text explanation

   Returns: Transaction result map"
  [conn disbursement-id reason user-id & {:keys [note]}]
  (retract-entity conn :disbursement disbursement-id reason user-id :note note))

(defn retract-deposit
  "Retract a deposit entity — data correction for recording errors.

   Same pattern as retract-payment. See retract-payment for full documentation.

   Args:
   - conn: Datomic connection
   - deposit-id: UUID of deposit to retract (:deposit/id)
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional free-text explanation

   Returns: Transaction result map"
  [conn deposit-id reason user-id & {:keys [note]}]
  (retract-entity conn :deposit deposit-id reason user-id :note note))

(defn retract-contract
  "Retract a contract and all associated entities — data correction for recording errors.

   Atomically retracts the contract entity plus all child entities:
   fees, installments, payments, disbursements, deposits, and principal-allocations.
   Everything is retracted in a single transaction to avoid orphaned entities.

   TX metadata records WHO corrected it, WHY, and WHAT was corrected.
   Datomic history preserves all retracted datoms for forensics via (d/history db).

   Use this for: contract boarded in error, duplicate contract, wrong customer.
   Do NOT use for real-world contract termination — that's a status change, not a retraction.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract to retract (:contract/id)
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional free-text explanation

   Returns: Transaction result map

   Usage:
     (retract-contract conn contract-uuid :erroneous-entry \"user-1\"
                       :note \"Contract boarded against wrong customer\")"
  [conn contract-id reason user-id & {:keys [note]}]
  (let [db (d/db conn)
        contract-ref [:contract/id contract-id]

        ;; Verify contract exists
        contract (d/pull db [:db/id] contract-ref)
        _ (when-not (:db/id contract)
            (throw (ex-info "Contract not found"
                            {:contract-id contract-id :error :not-found})))

        ;; Query all child entity IDs
        fees (contract/get-fees db contract-id)
        installments (contract/get-installments db contract-id)
        payments (contract/get-payments db contract-id)
        disbursements (contract/get-disbursements db contract-id)
        deposits (contract/get-deposits db contract-id)
        principal-allocations (contract/get-principal-allocations db contract-id)

        ;; Build retract statements for all entities
        retract-stmts
        (concat
          (map (fn [f] [:db/retractEntity [:fee/id (:fee/id f)]]) fees)
          (map (fn [i] [:db/retractEntity [:installment/id (:installment/id i)]]) installments)
          (map (fn [p] [:db/retractEntity [:payment/id (:payment/id p)]]) payments)
          (map (fn [d] [:db/retractEntity [:disbursement/id (:disbursement/id d)]]) disbursements)
          (map (fn [d] [:db/retractEntity [:deposit/id (:deposit/id d)]]) deposits)
          (map (fn [pa] [:db/retractEntity [:principal-allocation/id (:principal-allocation/id pa)]])
               principal-allocations)
          ;; Retract the contract itself last
          [[:db/retractEntity contract-ref]])]

    (d/transact conn
                {:tx-data (concat
                            retract-stmts
                            [(cond-> {:db/id "datomic.tx"
                                      :tx/reason reason
                                      :tx/corrects contract-ref
                                      :tx/author user-id}
                               note (assoc :tx/note note))])})))

(defn retract-origination
  "Retract all origination entities for a contract — data correction.

   Finds and atomically retracts:
   - All principal-allocation/* entities (fee deductions from funding)
   - All disbursement/* entities with type :funding or :excess-return
   - All deposit/* entities with source :funding

   TX metadata records WHO corrected it, WHY, and WHAT contract.
   Datomic history preserves retracted datoms for forensics.

   Use this for: wrong origination amounts, duplicate origination, etc.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional free-text explanation

   Returns: Transaction result map

   Usage:
     (retract-origination conn contract-uuid :correction \"user-1\"
                          :note \"Wrong fee deduction amount\")"
  [conn contract-id reason user-id & {:keys [note]}]
  (let [db (d/db conn)

        ;; Query origination-created entities
        principal-allocations (contract/get-principal-allocations db contract-id)
        disbursements (contract/get-disbursements db contract-id)
        deposits (contract/get-deposits db contract-id)

        ;; Filter to origination-related entities only
        funding-disbursements (filter #(#{:funding :excess-return} (:disbursement/type %))
                                      disbursements)
        funding-deposits (filter #(= :funding (:deposit/source %)) deposits)

        ;; Build retract statements
        retract-stmts
        (concat
          (map (fn [pa] [:db/retractEntity [:principal-allocation/id (:principal-allocation/id pa)]])
               principal-allocations)
          (map (fn [d] [:db/retractEntity [:disbursement/id (:disbursement/id d)]])
               funding-disbursements)
          (map (fn [d] [:db/retractEntity [:deposit/id (:deposit/id d)]])
               funding-deposits))]

    (when (empty? retract-stmts)
      (throw (ex-info "No origination entities found to retract"
                      {:contract-id contract-id :error :nothing-to-retract})))

    (d/transact conn
                {:tx-data (concat
                            retract-stmts
                            [(cond-> {:db/id "datomic.tx"
                                      :tx/reason reason
                                      :tx/corrects [:contract/id contract-id]
                                      :tx/author user-id}
                               note (assoc :tx/note note))])})))

;; ============================================================
;; Rate Adjustment Operations
;; ============================================================

(defn- calc-profit
  "Calculate profit amount for an installment.

   Simple calculation: principal * rate * (months/12)

   For MVP, we assume 1 month per installment. More sophisticated
   day-count conventions can be added later.

   Args:
   - principal-due: Principal amount for this installment
   - annual-rate: Annual profit rate (e.g., 0.15 for 15%)
   - months: Number of months (default 1)

   Returns: Profit amount as bigdec"
  [principal-due annual-rate months]
  (with-precision 10 :rounding HALF_UP
    (* principal-due annual-rate (/ months 12M))))

(defn adjust-rates
  "Apply rate adjustments to installments — one transaction for one business event.

   Accepts a collection of adjustments, each specifying a seq range and rate.
   All changes go in a single Datomic transaction. This ensures a step-up review
   (which may touch multiple ranges) is recorded as one atomic fact.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - adjustments: Seq of maps, each with :from-seq, :to-seq, :rate
   - reason: Reason for adjustment (shown in transaction log)
   - user-id: User performing the operation

   Returns: Transaction result map

   Usage:
     (adjust-rates conn contract-id
                   [{:from-seq 1 :to-seq 4 :rate 0.20M}
                    {:from-seq 5 :to-seq 8 :rate 0.15M}]
                   \"Step-up review: Term 1 paid on time\" \"user-1\")

   Transaction type: :rate-adjustment

   Note: This updates :installment/profit-due for affected installments.
   If an installment is already partially paid with the old rate, the
   overpayment will flow to the next installment via waterfall."
  [conn contract-id adjustments reason user-id]
  (let [db (d/db conn)
        all-installments (contract/get-installments db contract-id)

        ;; Build updates across all adjustment ranges
        updates
        (for [{:keys [from-seq to-seq rate]} adjustments
              inst all-installments
              :when (<= from-seq (:installment/seq inst) to-seq)
              :let [new-profit (calc-profit (:installment/principal-due inst)
                                           rate
                                           1)]]  ;; 1 month simplified
          {:db/id [:installment/id (:installment/id inst)]
           :installment/profit-due new-profit})]

    (when (empty? updates)
      (throw (ex-info "No installments matched the adjustment ranges"
                      {:contract-id contract-id
                       :adjustments adjustments
                       :error :no-matching-installments})))

    (d/transact conn
                {:tx-data (concat
                           updates
                           [{:db/id "datomic.tx"
                             :tx/type :rate-adjustment
                             :tx/contract [:contract/id contract-id]
                             :tx/note (str reason)
                             :tx/author user-id}])})))

(defn adjust-rate
  "Convenience wrapper: adjust a single range of installments.

   Delegates to adjust-rates with a single-element adjustment vector.
   See adjust-rates for full documentation.

   Usage:
     (adjust-rate conn contract-id 5 8 0.15M
                  \"Term 1 paid on time\" \"user-1\")"
  [conn contract-id from-seq to-seq new-rate reason user-id]
  (adjust-rates conn contract-id
                [{:from-seq from-seq :to-seq to-seq :rate new-rate}]
                reason user-id))

;; ============================================================
;; Security Deposit Operations
;; ============================================================

(defn receive-deposit
  "Record receipt of security deposit.

   Creates a deposit/* entity with type :received.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Deposit amount received (must be positive)
   - date: Business date — when deposit was received
   - user-id: User performing the operation
   - reference: Optional external reference
   - source: Optional keyword (:funding or :customer) — source of deposit funds.
             Only meaningful at origination to distinguish customer pre-payment
             from principal deduction.

   Returns: Transaction result map

   Usage:
     (receive-deposit conn contract-id 60000M #inst \"2024-01-15\" \"user-1\")
     (receive-deposit conn contract-id 40000M #inst \"2024-01-15\" \"user-1\"
                      :source :funding)"
  [conn contract-id amount date user-id & {:keys [reference source]}]
  (when-not (pos? amount)
    (throw (ex-info "Deposit amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [(cond-> {:deposit/id (random-uuid)
                                  :deposit/type :received
                                  :deposit/amount amount
                                  :deposit/date date
                                  :deposit/contract [:contract/id contract-id]}
                           reference (assoc :deposit/reference reference)
                           source (assoc :deposit/source source))
                         (db/recording-metadata user-id)]}))

(defn refund-deposit
  "Refund security deposit to customer.

   Creates a deposit/* entity with type :refund.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Deposit amount to refund (must be positive)
   - date: Business date — when refund occurred
   - reason: Reason for refund
   - user-id: User performing the operation

   Returns: Transaction result map

   Usage:
     (refund-deposit conn contract-id 60000M #inst \"2024-06-01\"
                     \"Contract closed, no outstanding balance\" \"user-1\")"
  [conn contract-id amount date reason user-id]
  (when-not (pos? amount)
    (throw (ex-info "Deposit refund amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [{:deposit/id (random-uuid)
                          :deposit/type :refund
                          :deposit/amount amount
                          :deposit/date date
                          :deposit/contract [:contract/id contract-id]}
                         (db/recording-metadata user-id :note reason)]}))

(defn offset-deposit
  "Apply security deposit to outstanding balance.

   Creates a deposit/* entity with type :offset. This is a non-cash
   transaction that flows through the waterfall like a regular payment.
   The deposit is no longer held (reduces deposit-held) and is applied
   to fees/installments.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Deposit amount to offset (must be positive)
   - date: Business date — when offset was applied
   - reason: Reason for offset
   - user-id: User performing the operation

   Returns: Transaction result map

   Usage:
     (offset-deposit conn contract-id 60000M #inst \"2024-06-01\"
                     \"Customer default, applying deposit\" \"user-1\")

   Note: This reduces both deposit-held AND outstanding balance."
  [conn contract-id amount date reason user-id]
  (when-not (pos? amount)
    (throw (ex-info "Deposit offset amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [{:deposit/id (random-uuid)
                          :deposit/type :offset
                          :deposit/amount amount
                          :deposit/date date
                          :deposit/contract [:contract/id contract-id]}
                         (db/recording-metadata user-id :note reason)]}))

(defn transfer-deposit
  "Transfer security deposit between contracts.

   Creates a deposit/* entity with type :transfer. Used in refinancing
   to move collateral from old contract to new contract. The transfer
   entity records the source contract (:deposit/contract) and destination
   (:deposit/target-contract).

   Args:
   - conn: Datomic connection
   - source-contract-id: UUID of contract providing the deposit
   - target-contract-id: UUID of contract receiving the deposit
   - amount: Transfer amount (must be positive)
   - date: Business date — when transfer occurred
   - user-id: User performing the operation
   - reference: Optional external reference
   - note: Optional note

   Returns: Transaction result map

   Usage:
     (transfer-deposit conn old-contract-id new-contract-id
                       30000M #inst \"2024-06-01\" \"user-1\"
                       :reference \"REFI-DEP-TRANSFER\")"
  [conn source-contract-id target-contract-id amount date user-id
   & {:keys [reference note]}]
  (when-not (pos? amount)
    (throw (ex-info "Transfer amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [(cond-> {:deposit/id (random-uuid)
                                  :deposit/type :transfer
                                  :deposit/amount amount
                                  :deposit/date date
                                  :deposit/contract [:contract/id source-contract-id]
                                  :deposit/target-contract [:contract/id target-contract-id]}
                           reference (assoc :deposit/reference reference))
                         (db/recording-metadata user-id :note note)]}))

;; ============================================================
;; Facility Operations
;; ============================================================

(defn create-facility
  "Create a new facility (credit line) from LOS import.

   A facility represents an approved credit limit. The limit is a fact
   from LOS. LMS tracks utilization by summing contract principals.

   Args:
   - conn: Datomic connection
   - facility-data: Map with facility attributes:
     - :id (optional, UUID generated if not provided)
     - :external-id (required, LOS reference like PIP-1283621)
     - :customer-id (required, CR number)
     - :customer-name (required)
     - :limit (required, approved credit limit in SAR)
     - :funder (optional, funding source code)
     - :status (optional, defaults to :active)
   - user-id: User performing the operation

   Returns: Transaction result map with :facility-id in tempids

   Usage:
     (create-facility conn
       {:external-id \"PIP-1283621\"
        :customer-id \"7016779188\"
        :customer-name \"WakeCap Saudi\"
        :limit 10000000M
        :funder \"SKFH\"}
       \"user-1\")"
  [conn facility-data user-id]
  (doseq [k [:external-id :customer-id :customer-name :limit]]
    (when-not (get facility-data k)
      (throw (ex-info (str "Missing required facility field: " k)
                      {:field k :error :missing-required-field}))))
  (when-not (pos? (:limit facility-data))
    (throw (ex-info "Facility limit must be positive"
                    {:limit (:limit facility-data) :error :non-positive-limit})))
  (let [facility-id (or (:id facility-data) (random-uuid))
        facility-entity (cond-> {:db/id "new-facility"
                                 :facility/id facility-id
                                 :facility/external-id (:external-id facility-data)
                                 :facility/customer-id (:customer-id facility-data)
                                 :facility/customer-name (:customer-name facility-data)
                                 :facility/limit (:limit facility-data)
                                 :facility/status (or (:status facility-data) :active)
                                 :facility/created-at (java.util.Date.)}
                          (:funder facility-data)
                          (assoc :facility/funder (:funder facility-data)))]
    (d/transact conn {:tx-data [facility-entity
                                {:db/id "datomic.tx"
                                 :tx/type :boarding
                                 :tx/author user-id
                                 :tx/note (str "Facility created: " (:external-id facility-data))}]})))

;; ============================================================
;; Contract Lifecycle Operations
;; ============================================================

(defn board-contract
  "Create a new contract with schedule.

   This is the initial operation that creates:
   - Contract entity
   - Fee entities
   - Installment entities
   - Boarding transaction

   Args:
   - conn: Datomic connection
   - contract-data: Map with contract attributes:
     Required:
       :contract/id, :contract/external-id, :contract/customer-name,
       :contract/customer-id, :contract/status, :contract/start-date,
       :contract/principal
     Optional (enrichments):
       :contract/facility           - ref to facility (for credit line contracts)
       :contract/commodity-quantity - Murabaha: commodity quantity
       :contract/commodity-unit-price - Murabaha: unit price
       :contract/commodity-description - Murabaha: description
       :contract/commodity-vendor   - Murabaha: vendor name
       :contract/disbursement-iban  - IBAN for disbursement
       :contract/disbursement-bank  - Bank name for disbursement
       :contract/virtual-iban       - Virtual IBAN for collection
       :contract/refinances         - ref to contract being refinanced
     Deprecated:
       :contract/maturity-date      - now derived from installments
   - fees: Sequence of fee maps
   - installments: Sequence of installment maps
   - user-id: User performing the operation

   Returns: Transaction result map

   Usage:
     (board-contract conn
       {:contract/id (random-uuid)
        :contract/external-id \"LOAN-2024-001\"
        :contract/customer-name \"Customer Co.\"
        :contract/customer-id \"CR-123\"
        :contract/status :active
        :contract/start-date #inst \"2024-01-01\"
        :contract/principal 1000000M
        ;; Optional enrichments
        :contract/facility [:facility/id facility-id]
        :contract/commodity-description \"Palm Oil\"
        :contract/commodity-quantity 2591.567M
        :contract/commodity-unit-price 3724.585M
        :contract/commodity-vendor \"Nafaes\"
        :contract/disbursement-iban \"SA242000...\"
        :contract/virtual-iban \"SA103010...\"}
       [{:fee/id (random-uuid) :fee/type :management :fee/amount 5000M ...}]
       [{:installment/id (random-uuid) :installment/seq 1 ...}]
       \"user-1\")

   Transaction type: :boarding

   Note: maturity-date is now derived from the last installment's due-date.
   You should NOT pass :contract/maturity-date - it will be ignored by
   contract-state which derives it from installments."
  [conn contract-data fees installments user-id]
  ;; Validate required contract fields
  (doseq [k [:contract/id :contract/external-id :contract/customer-name
             :contract/customer-id :contract/status :contract/start-date
             :contract/principal]]
    (when-not (get contract-data k)
      (throw (ex-info (str "Missing required contract field: " k)
                      {:field k :error :missing-required-field}))))
  ;; Validate remaining-principal on every installment
  (doseq [inst installments]
    (when-not (:installment/remaining-principal inst)
      (throw (ex-info "Every installment must include :installment/remaining-principal"
                      {:installment/seq (:installment/seq inst)
                       :error :missing-remaining-principal}))))

  (let [;; Use temp ID for contract within this transaction
        contract-temp-id "new-contract"

        ;; Build transaction data
        tx-data (concat
                 ;; Contract (with temp ID)
                 [(assoc contract-data :db/id contract-temp-id)]

                 ;; Fees (reference temp ID)
                 (map #(assoc % :fee/contract contract-temp-id) fees)

                 ;; Installments (reference temp ID)
                 (map #(assoc % :installment/contract contract-temp-id) installments)

                 ;; Boarding transaction (reference temp ID)
                 [{:db/id "datomic.tx"
                   :tx/type :boarding
                   :tx/contract contract-temp-id
                   :tx/author user-id}])]

    (d/transact conn {:tx-data tx-data})))

(defn record-disbursement
  "Record loan disbursement (funding) to customer.

   Creates a disbursement/* entity with type :funding. This records that
   funds were actually transferred to the customer. Funding disbursements
   don't affect the waterfall — they're just the record of loan funding.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Disbursement amount (must be positive, should match contract principal)
   - date: Business date — when funds were sent
   - reference: External reference (wire transfer ID, etc.)
   - user-id: User performing the operation
   - iban: Optional destination IBAN
   - bank: Optional destination bank name

   Returns: Transaction result map

   Usage:
     (record-disbursement conn contract-id 1000000M #inst \"2024-01-15\"
                          \"WT-001\" \"user-1\"
                          :iban \"SA242000...\" :bank \"ANB\")"
  [conn contract-id amount date reference user-id & {:keys [iban bank]}]
  (when-not (pos? amount)
    (throw (ex-info "Disbursement amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [(cond-> {:disbursement/id (random-uuid)
                                  :disbursement/type :funding
                                  :disbursement/amount amount
                                  :disbursement/date date
                                  :disbursement/contract [:contract/id contract-id]
                                  :disbursement/reference reference}
                           iban (assoc :disbursement/iban iban)
                           bank (assoc :disbursement/bank bank))
                         (db/recording-metadata user-id)]}))

(defn record-refund
  "Record a refund to customer — money returned.

   Creates a disbursement/* entity with type :refund. Refund disbursements
   reduce effective payments in the waterfall. This is NOT a correction —
   use retract-payment for recording errors.

   Use this when real money is being returned to the customer (e.g., overpayment,
   contract closure with credit balance, negotiated refund).

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Refund amount (must be positive)
   - date: Business date — when refund was sent
   - reference: External reference (wire transfer ID, etc.)
   - user-id: User performing the operation
   - note: Optional reason for refund

   Returns: Transaction result map

   Usage:
     (record-refund conn contract-id 50000M #inst \"2024-06-01\"
                    \"REF-001\" \"user-1\"
                    :note \"Overpayment refund per customer request\")"
  [conn contract-id amount date reference user-id & {:keys [note]}]
  (when-not (pos? amount)
    (throw (ex-info "Refund amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [{:disbursement/id (random-uuid)
                          :disbursement/type :refund
                          :disbursement/amount amount
                          :disbursement/date date
                          :disbursement/contract [:contract/id contract-id]
                          :disbursement/reference reference}
                         (db/recording-metadata user-id :note note)]}))

;; ============================================================
;; Principal Allocation Operations
;; ============================================================

(defn record-principal-allocation
  "Record allocation of principal to settle waterfall obligations (fees).

   Creates a principal-allocation/* entity. This records that part of the
   principal was allocated to pay fees at origination — it's not a payment
   (no money from outside) and not a fee attribute (the fee doesn't know
   how it was settled). The allocation flows through the waterfall as a
   4th source alongside payments, refund disbursements, and deposit offsets.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Allocation amount (must be positive)
   - date: Business date — typically origination date
   - user-id: User performing the operation
   - reference: Optional external reference or description
   - note: Optional note

   Returns: Transaction result map

   Usage:
     (record-principal-allocation conn contract-id 46687.50M
                                  #inst \"2024-01-15\" \"user-1\"
                                  :reference \"FUNDING-FEE-SETTLEMENT\")"
  [conn contract-id amount date user-id & {:keys [reference note]}]
  (when-not (pos? amount)
    (throw (ex-info "Principal allocation amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [(cond-> {:principal-allocation/id (random-uuid)
                                  :principal-allocation/amount amount
                                  :principal-allocation/date date
                                  :principal-allocation/contract [:contract/id contract-id]}
                           reference (assoc :principal-allocation/reference reference))
                         (db/recording-metadata user-id :note note)]}))

(defn record-excess-return
  "Record return of customer excess via disbursement wire.

   Creates a disbursement/* entity with type :excess-return. This records
   that application-level excess (customer pre-paid more than final obligations)
   was returned to the customer, typically bundled in the same wire as the
   loan funding.

   Unlike :refund disbursements, :excess-return does NOT affect the waterfall —
   the excess was never in the waterfall. It came from the external application
   system.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Excess amount being returned (must be positive)
   - date: Business date — when excess was returned
   - reference: External reference (wire transfer ID, etc.)
   - user-id: User performing the operation
   - note: Optional note

   Returns: Transaction result map

   Usage:
     (record-excess-return conn contract-id 35000M #inst \"2024-01-15\"
                           \"WT-001\" \"user-1\"
                           :note \"Customer excess from reduced loan amount\")"
  [conn contract-id amount date reference user-id & {:keys [note]}]
  (when-not (pos? amount)
    (throw (ex-info "Excess return amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [{:disbursement/id (random-uuid)
                          :disbursement/type :excess-return
                          :disbursement/amount amount
                          :disbursement/date date
                          :disbursement/contract [:contract/id contract-id]
                          :disbursement/reference reference}
                         (db/recording-metadata user-id :note note)]}))

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  (require '[lms.db :as db])
  (require '[lms.contract :as contract])
  (require '[lms.operations :as ops])
  (require '[datomic.client.api :as d])

  ;; Setup
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Create a facility (credit line)
  (def facility-id (random-uuid))
  (ops/create-facility conn
                       {:id facility-id
                        :external-id "PIP-123456"
                        :customer-id "CR-123"
                        :customer-name "Test Customer"
                        :limit 5000000M
                        :funder "SKFH"}
                       "test-user")

  ;; Create a contract under the facility
  (def contract-id (random-uuid))
  (ops/board-contract conn
                      {:contract/id contract-id
                       :contract/external-id "TEST-001"
                       :contract/customer-name "Test Customer"
                       :contract/customer-id "CR-123"
                       :contract/status :active
                       :contract/start-date #inst "2024-01-01"
                       :contract/principal 1000000M
                       :contract/security-deposit 50000M
                       :contract/facility [:facility/id facility-id]
                       :contract/commodity-description "Palm Oil"
                       :contract/commodity-quantity 2591.567M
                       :contract/commodity-unit-price 385.99M
                       :contract/commodity-vendor "Nafaes"}
                      [{:fee/id (random-uuid)
                        :fee/type :management
                        :fee/amount 5000M
                        :fee/due-date #inst "2024-01-01"}]
                      [{:installment/id (random-uuid)
                        :installment/seq 1
                        :installment/due-date #inst "2024-01-31"
                        :installment/principal-due 100000M
                        :installment/profit-due 10000M
                        :installment/remaining-principal 1000000M}]
                      "test-user")

  ;; Check facility utilization
  (contract/facility-state (d/db conn) facility-id)

  ;; Record payment — business date + recording date are separate
  (ops/record-payment conn contract-id 50000M #inst "2024-01-15"
                      "FT-ANB-123" "test-user"
                      :channel "bank-transfer"
                      :note "Wire transfer from customer account")

  ;; Check state
  (contract/contract-state (d/db conn) contract-id (java.util.Date.))

  ;; Preview payment
  (ops/preview-payment conn contract-id 100000M)

  ;; Adjust rates — batch: one business event, one transaction
  (ops/adjust-rates conn contract-id
                    [{:from-seq 1 :to-seq 4 :rate 0.20M}
                     {:from-seq 5 :to-seq 12 :rate 0.12M}]
                    "Step-up review: Term 1 paid on time" "test-user")

  ;; Adjust rate — convenience wrapper for single range
  (ops/adjust-rate conn contract-id 1 12 0.12
                   "Customer earned discount" "test-user")

  ;; Deposit operations — all create deposit/* entities
  (ops/receive-deposit conn contract-id 50000M #inst "2024-01-15" "test-user")
  (ops/offset-deposit conn contract-id 50000M #inst "2024-06-01"
                      "Customer default" "test-user")

  ;; Disbursement — loan funding
  (ops/record-disbursement conn contract-id 1000000M #inst "2024-01-02"
                           "WT-001" "test-user"
                           :iban "SA242000..." :bank "ANB")

  ;; Refund — money returned to customer (not a correction!)
  (ops/record-refund conn contract-id 10000M #inst "2024-07-01"
                     "REF-001" "test-user"
                     :note "Overpayment refund")

  ;; Retract a payment (data correction — recording error)
  ;; (ops/retract-payment conn payment-uuid :duplicate-removal "test-user"
  ;;                      :note "Duplicate of FT-ANB-123")

  )
