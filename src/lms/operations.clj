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
            [lms.settlement :as settlement]
            [lms.db :as db]
            [lms.dates :as dates]))

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
     - :party-id (required, UUID of party — must be company)
     - :limit (required, approved credit limit in SAR)
     - :funder (optional, funding source code)
     - :status (optional, defaults to :active)
   - user-id: User performing the operation

   Returns: Transaction result map with :facility-id in tempids

   Usage:
     (create-facility conn
       {:external-id \"PIP-1283621\"
        :party-id #uuid \"...\"
        :limit 10000000M
        :funder \"SKFH\"}
       \"user-1\")"
  [conn facility-data user-id]
  (doseq [k [:external-id :party-id :limit]]
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
                                 :facility/party [:party/id (:party-id facility-data)]
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

   Contract starts in :pending status (derived). It becomes :active when
   disbursement is recorded via record-disbursement.

   Args:
   - conn: Datomic connection
   - contract-data: Map with contract attributes:
     Required:
       :contract/id, :contract/external-id, :contract/borrower,
       :contract/start-date, :contract/principal
     Optional (enrichments):
       :contract/facility           - ref to facility (for credit line contracts)
       :contract/guarantors         - ref many to guarantor parties
       :contract/authorized-signatories - ref many to signatory parties
       :contract/commodity-quantity - Murabaha: commodity quantity
       :contract/commodity-unit-price - Murabaha: unit price
       :contract/commodity-description - Murabaha: description
       :contract/commodity-vendor   - Murabaha: vendor name
       :contract/disbursement-iban  - IBAN for disbursement
       :contract/disbursement-bank  - Bank name for disbursement
       :contract/virtual-iban       - Virtual IBAN for collection
     Derived (do not pass):
       :contract/status             - derived from disbursed-at, written-off-at, payments
       :contract/maturity-date      - derived from last installment's due-date
   - fees: Sequence of fee maps
   - installments: Sequence of installment maps
   - user-id: User performing the operation

   Returns: Transaction result map

   Usage:
     (board-contract conn
       {:contract/id (random-uuid)
        :contract/external-id \"LOAN-2024-001\"
        :contract/borrower [:party/id borrower-id]
        :contract/start-date #inst \"2024-01-01\"
        :contract/principal 1000000M
        ;; Optional enrichments
        :contract/facility [:facility/id facility-id]
        :contract/guarantors [[:party/id g1-id]]
        :contract/commodity-description \"Palm Oil\"
        :contract/commodity-quantity 2591.567M
        :contract/commodity-unit-price 3724.585M
        :contract/commodity-vendor \"Nafaes\"
        :contract/disbursement-iban \"SA242000...\"
        :contract/virtual-iban \"SA103010...\"}
       [{:fee/id (random-uuid) :fee/type :management :fee/amount 5000M ...}]
       [{:installment/id (random-uuid) :installment/seq 1 ...}]
       \"user-1\")

   Transaction type: :boarding"
  [conn contract-data fees installments user-id]
  ;; Validate required contract fields
  ;; Note: status is derived, not stored. maturity-date is derived from installments.
  (doseq [k [:contract/id :contract/external-id :contract/borrower
             :contract/start-date :contract/principal]]
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

(defn write-off-contract
  "Record board-approved write-off decision.

   Sets :contract/written-off-at timestamp. This is a regulatory fact that
   affects GL entries, SIMAH reporting, and audit trail.

   Write-off requires board approval documented via approval-reference.
   Once written-off, contract status will derive as :written-off.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract to write off
   - approval-reference: Board approval reference (e.g., \"BOARD-2024-001\")
   - user-id: User performing the operation
   - note: Optional additional note

   Returns: Transaction result map

   Usage:
     (write-off-contract conn contract-id \"BOARD-2024-001\" \"user-1\"
                         :note \"Customer bankruptcy - irrecoverable\")"
  [conn contract-id approval-reference user-id & {:keys [note]}]
  (when (empty? approval-reference)
    (throw (ex-info "Write-off requires board approval reference"
                    {:contract-id contract-id :error :missing-approval-reference})))
  (d/transact conn
              {:tx-data [{:db/id [:contract/id contract-id]
                          :contract/written-off-at (java.util.Date.)}
                         {:db/id "datomic.tx"
                          :tx/type :write-off
                          :tx/contract [:contract/id contract-id]
                          :tx/author user-id
                          :tx/note (str "Board approval: " approval-reference
                                        (when note (str " - " note)))}]}))

(defn- compute-date-shift
  "Compute days to shift schedule so first installment lands on expected date.

   Given the disbursement date and the contractual days-to-first-installment,
   calculates how many days to shift ALL schedule dates.

   Returns: number of days to shift (positive = forward, negative = backward)"
  [disbursed-at days-to-first installments]
  (let [first-inst (->> installments
                        (sort-by :installment/seq)
                        first)
        current-first-date (:installment/due-date first-inst)
        expected-first-date (dates/add-days disbursed-at days-to-first)]
    (dates/days-between current-first-date expected-first-date)))

(defn record-disbursement
  "Record loan disbursement (funding) to customer.

   Creates a disbursement/* entity with type :funding. This records that
   funds were actually transferred to the customer. Funding disbursements
   don't affect the waterfall — they're just the record of loan funding.

   Also sets :contract/disbursed-at to mark the contract as active.
   Once disbursed, the contract status will derive as :active.

   If the contract has :contract/days-to-first-installment set, all installment
   due dates are shifted so the first installment falls on
   disbursed-at + days-to-first-installment. Fee due dates are NOT shifted —
   they are set explicitly and independent of the installment schedule.

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
  (let [db (d/db conn)
        contract (d/pull db [:contract/days-to-first-installment] [:contract/id contract-id])
        days-to-first (:contract/days-to-first-installment contract)
        installments (contract/get-installments db contract-id)

        ;; Compute shift (0 if days-to-first not set or no installments)
        shift-days (if (and days-to-first (seq installments))
                     (compute-date-shift date days-to-first installments)
                     0)

        ;; Shift all installment due dates (fee due dates are NOT shifted —
        ;; they are set explicitly by the user and independent of the schedule)
        installment-updates
        (when (not= 0 shift-days)
          (for [inst installments]
            {:db/id [:installment/id (:installment/id inst)]
             :installment/due-date
             (dates/add-days (:installment/due-date inst) shift-days)}))

        disbursement-entity
        (cond-> {:disbursement/id (random-uuid)
                 :disbursement/type :funding
                 :disbursement/amount amount
                 :disbursement/date date
                 :disbursement/contract [:contract/id contract-id]
                 :disbursement/reference reference}
          iban (assoc :disbursement/iban iban)
          bank (assoc :disbursement/bank bank))]

    (d/transact conn
                {:tx-data (concat
                           [disbursement-entity]
                           installment-updates
                           [{:db/id [:contract/id contract-id]
                             :contract/disbursed-at date}
                            (db/recording-metadata user-id)])})))

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
  "Record a deduction from principal at origination.

   Creates a principal-allocation/* entity. Records that part of the
   principal was allocated for a specific purpose — fee settlement,
   deposit, or installment prepayment.

   Type determines waterfall behavior:
   - :fee-settlement     → flows through waterfall (settles a fee)
   - :installment-prepayment → flows through waterfall (prepays installments)
   - :deposit            → does NOT flow through waterfall (deposit ledger is separate)

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - amount: Allocation amount (must be positive)
   - date: Business date — typically origination date
   - user-id: User performing the operation
   - type: Keyword — :fee-settlement, :deposit, or :installment-prepayment
   - fee-id: Optional UUID of the fee being settled (for :fee-settlement type)
   - reference: Optional external reference or description
   - note: Optional note

   Returns: Transaction result map

   Usage:
     (record-principal-allocation conn contract-id 46687.50M
                                  #inst \"2024-01-15\" \"user-1\"
                                  :type :fee-settlement
                                  :fee-id fee-uuid
                                  :reference \"FUNDING-FEE-SETTLEMENT\")"
  [conn contract-id amount date user-id & {:keys [reference note fee-id type]}]
  (when-not (pos? amount)
    (throw (ex-info "Principal allocation amount must be positive"
                    {:amount amount :error :non-positive-amount})))
  (d/transact conn
              {:tx-data [(cond-> {:principal-allocation/id (random-uuid)
                                  :principal-allocation/amount amount
                                  :principal-allocation/date date
                                  :principal-allocation/contract [:contract/id contract-id]}
                           type (assoc :principal-allocation/type type)
                           fee-id (assoc :principal-allocation/fee [:fee/id fee-id])
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
;; Document Operations
;; ============================================================

(defn generate-clearance-letter
  "Generate a clearance letter — binding settlement communication.

   Computes settlement internally using contract-state + calculate-settlement,
   stores the settlement-amount as a first-class bigdec and the full
   calculation result as an EDN snapshot.

   The committed settlement-amount becomes a fact. If contract state changes
   later (new payment, correction), this creates a detectable contradiction.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - settlement-date: java.util.Date — effective date of settlement
   - penalty-days: int — days of profit as penalty
   - user-id: User performing the operation
   - note: Optional note

   Returns: Transaction result map"
  [conn contract-id settlement-date penalty-days user-id & {:keys [note]}]
  (let [db (d/db conn)
        state (contract/contract-state db contract-id settlement-date)
        result (settlement/calculate-settlement state settlement-date penalty-days)]
    (d/transact conn
                {:tx-data [{:clearance-letter/id (random-uuid)
                            :clearance-letter/contract [:contract/id contract-id]
                            :clearance-letter/settlement-date settlement-date
                            :clearance-letter/penalty-days penalty-days
                            :clearance-letter/settlement-amount (:settlement-amount result)
                            :clearance-letter/snapshot (pr-str result)}
                           (db/recording-metadata user-id :note note)]})))

(defn supersede-clearance-letter
  "Generate a new clearance letter that supersedes a prior one.

   Same as generate-clearance-letter, plus sets :clearance-letter/supersedes
   to point to the prior letter. The prior letter remains as a historical
   fact but is no longer 'active'.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - prior-cl-id: UUID of clearance letter being superseded
   - settlement-date: java.util.Date
   - penalty-days: int
   - user-id: User performing the operation
   - note: Optional note

   Returns: Transaction result map"
  [conn contract-id prior-cl-id settlement-date penalty-days user-id & {:keys [note]}]
  (let [db (d/db conn)
        state (contract/contract-state db contract-id settlement-date)
        result (settlement/calculate-settlement state settlement-date penalty-days)]
    (d/transact conn
                {:tx-data [{:clearance-letter/id (random-uuid)
                            :clearance-letter/contract [:contract/id contract-id]
                            :clearance-letter/settlement-date settlement-date
                            :clearance-letter/penalty-days penalty-days
                            :clearance-letter/settlement-amount (:settlement-amount result)
                            :clearance-letter/snapshot (pr-str result)
                            :clearance-letter/supersedes [:clearance-letter/id prior-cl-id]}
                           (db/recording-metadata user-id :note note)]})))

(defn generate-statement
  "Generate a statement — informational account snapshot over a period.

   Computes contract-state at period-end and stores as EDN snapshot.
   Statements are informational, not binding.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - period-start: java.util.Date
   - period-end: java.util.Date
   - user-id: User performing the operation
   - note: Optional note

   Returns: Transaction result map"
  [conn contract-id period-start period-end user-id & {:keys [note]}]
  (let [db (d/db conn)
        state (contract/contract-state db contract-id period-end)]
    (d/transact conn
                {:tx-data [{:statement/id (random-uuid)
                            :statement/contract [:contract/id contract-id]
                            :statement/period-start period-start
                            :statement/period-end period-end
                            :statement/snapshot (pr-str state)}
                           (db/recording-metadata user-id :note note)]})))

(defn generate-contract-agreement
  "Generate a contract agreement — document freezing contract terms.

   Snapshots current contract terms (contract entity + schedule + fees +
   parties + commodity) as EDN. This IS the agreement — the terms as they
   exist at generation time, frozen.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - user-id: User performing the operation
   - note: Optional note

   Returns: Transaction result map"
  [conn contract-id user-id & {:keys [note]}]
  (let [db (d/db conn)
        contract (contract/get-contract db contract-id)
        fees (contract/get-fees db contract-id)
        installments (contract/get-installments db contract-id)
        snapshot {:contract (dissoc contract :db/id)
                  :fees (mapv #(dissoc % :db/id) fees)
                  :installments (mapv #(dissoc % :db/id) installments)}]
    (d/transact conn
                {:tx-data [{:contract-agreement/id (random-uuid)
                            :contract-agreement/contract [:contract/id contract-id]
                            :contract-agreement/snapshot (pr-str snapshot)}
                           (db/recording-metadata user-id :note note)]})))

(defn record-signing
  "Record the act of signing a document.

   Creates a signing/* entity linking a party to a document. The document
   can be any type (clearance letter, contract agreement, etc.) — Datomic
   refs are polymorphic.

   Validates no duplicate signing (same party + same document).

   Args:
   - conn: Datomic connection
   - document-ref: Lookup ref, e.g. [:clearance-letter/id uuid]
   - party-id: UUID of the signing party
   - signing-date: Business date (when signing happened in the real world)
   - method: Keyword — :wet-ink, :digital, or :otp
   - user-id: User recording the signing
   - reference: Optional digital signing reference
   - note: Optional note

   Returns: Transaction result map"
  [conn document-ref party-id signing-date method user-id & {:keys [reference note]}]
  (let [db (d/db conn)
        existing (d/q {:query '[:find ?s
                                :in $ ?doc ?party
                                :where
                                [?s :signing/document ?doc]
                                [?s :signing/party ?party]]
                       :args [db document-ref [:party/id party-id]]})]
    (when (seq existing)
      (throw (ex-info "Duplicate signing: party has already signed this document"
                      {:document-ref document-ref
                       :party-id party-id
                       :error :duplicate-signing})))
    (d/transact conn
                {:tx-data [(cond-> {:signing/id (random-uuid)
                                    :signing/document document-ref
                                    :signing/party [:party/id party-id]
                                    :signing/date signing-date
                                    :signing/method method}
                             reference (assoc :signing/reference reference))
                           (db/recording-metadata user-id :note note)]})))

(defn retract-document
  "Retract a document entity and associated signings — for unsent documents.

   Atomically retracts the document + all signing/* entities pointing to it.
   Use for documents generated in error and never sent.

   Do NOT use for documents already communicated — use supersession instead.

   Args:
   - conn: Datomic connection
   - ns-kw: Namespace keyword, e.g. :clearance-letter, :statement, :contract-agreement
   - document-id: UUID of the document
   - reason: Keyword — :correction, :duplicate-removal, or :erroneous-entry
   - user-id: User performing the retraction
   - note: Optional note

   Returns: Transaction result map"
  [conn ns-kw document-id reason user-id & {:keys [note]}]
  (let [db (d/db conn)
        lookup-ref [(keyword (name ns-kw) "id") document-id]
        doc-eid (:db/id (d/pull db [:db/id] lookup-ref))
        _ (when-not doc-eid
            (throw (ex-info "Document not found"
                            {:ns ns-kw :document-id document-id :error :not-found})))
        signing-eids (d/q {:query '[:find ?s
                                    :in $ ?doc
                                    :where [?s :signing/document ?doc]]
                           :args [db doc-eid]})]
    (d/transact conn
                {:tx-data (concat
                           (map (fn [[eid]] [:db/retractEntity eid]) signing-eids)
                           [[:db/retractEntity lookup-ref]
                            (cond-> {:db/id "datomic.tx"
                                     :tx/reason reason
                                     :tx/corrects lookup-ref
                                     :tx/author user-id}
                              note (assoc :tx/note note))])})))

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

  ;; Create a contract under the facility (status is derived, not stored)
  (def contract-id (random-uuid))
  (ops/board-contract conn
                      {:contract/id contract-id
                       :contract/external-id "TEST-001"
                       :contract/customer-name "Test Customer"
                       :contract/customer-id "CR-123"
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
