(ns lms.contract
  "Contract queries and state derivation.

   This namespace contains the 'read model' of the system. It queries
   facts from Datomic and derives all state through pure computation.

   Core principle: contract-state derives EVERYTHING from stored facts.
   - Facts: contract terms, schedule, payment/disbursement/deposit entities
   - Derived: paid amounts, balances, statuses, credit balance, deposit held

   Entity query functions return raw Datomic entities (payment/*, disbursement/*,
   deposit/*). State derivation happens in contract-state and its helpers.

   No state is stored. Every time you call contract-state, it recomputes
   from facts. This ensures consistency: the displayed state is always
   the truth as of right now."
  (:require [datomic.client.api :as d]
            [lms.waterfall :as waterfall]
            [lms.dates :as dates]
            [clojure.edn :as edn]))

;; ============================================================
;; Query Functions (Fetch Facts)
;; ============================================================

(defn get-payments
  "Query all payment entities for a contract.

   Returns sequence of payment entity maps, sorted by business date.
   Each map contains: :payment/id, :payment/amount, :payment/date,
   :payment/reference, :payment/channel (optional)"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?p [*])
                      :in $ ?contract-id
                      :where
                      [?p :payment/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)
       (sort-by :payment/date)))

(defn get-disbursements
  "Query all disbursement entities for a contract.

   Returns sequence of disbursement entity maps, sorted by business date.
   Each map contains: :disbursement/id, :disbursement/type (:funding or :refund),
   :disbursement/amount, :disbursement/date, :disbursement/reference,
   :disbursement/iban (optional), :disbursement/bank (optional)"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?d [*])
                      :in $ ?contract-id
                      :where
                      [?d :disbursement/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)
       (sort-by :disbursement/date)))

(defn get-deposits
  "Query all deposit entities for a contract.

   Includes deposits where :deposit/contract = this contract AND
   deposits where :deposit/target-contract = this contract (transfers IN).

   Returns sequence of deposit entity maps, sorted by business date.
   Each map contains: :deposit/id, :deposit/type (:received, :refund, :offset, :transfer),
   :deposit/amount, :deposit/date, :deposit/contract, :deposit/target-contract (optional)"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?d [*])
                      :in $ ?contract-id
                      :where
                      [?c :contract/id ?contract-id]
                      (or [?d :deposit/contract ?c]
                          [?d :deposit/target-contract ?c])]
             :args [db contract-id]})
       (map first)
       (sort-by :deposit/date)))

(defn get-principal-allocations
  "Query all principal allocation entities for a contract.

   Returns sequence of principal-allocation entity maps, sorted by business date.
   Each map contains: :principal-allocation/id, :principal-allocation/amount,
   :principal-allocation/date, :principal-allocation/contract,
   :principal-allocation/reference (optional)"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?pa [*])
                      :in $ ?contract-id
                      :where
                      [?pa :principal-allocation/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)
       (sort-by :principal-allocation/date)))

(defn get-admin-events
  "Query admin events (boarding, rate-adjustment) for a contract.

   These are tx-metadata-only events — no business entity, just recording facts.
   Date comes from txInstant (admin events are always recorded in real time).

   Returns sequence of event maps:
   {:type :boarding, :date #inst ..., :author \"user-1\", :note \"...\", :tx-id 123}"
  [db contract-id]
  (->> (d/q {:query '[:find ?tx ?type ?time
                      :in $ ?contract
                      :where
                      [?tx :tx/contract ?contract]
                      [?tx :tx/type ?type]
                      [?tx :db/txInstant ?time]]
             :args [db [:contract/id contract-id]]})
       (map (fn [[tx type time]]
              (let [e (d/pull db '[:tx/author :tx/note] tx)]
                {:event-type :admin
                 :type type
                 :date time
                 :author (:tx/author e)
                 :note (:tx/note e)
                 :tx-id tx})))
       (sort-by :date)))

(defn get-retracted-payments
  "Query payments that were retracted (data corrections) for a contract.

   Joins history db (retracted payment datoms) with current db (tx metadata).
   Returns original payment details + retraction reason, author, note, and date.

   Uses two database sources:
   - $history (d/history db): contains retracted payment datoms
   - $current (db): contains tx metadata (:tx/reason, :tx/corrects, etc.)"
  [db contract-id]
  (let [hdb (d/history db)]
    (->> (d/q {:query '[:find ?payment-id ?amount ?date ?reason ?author ?note ?retract-time
                        :in $history $current ?contract
                        :where
                        [$history ?p :payment/contract ?contract]
                        [$history ?p :payment/id ?payment-id]
                        [$history ?p :payment/amount ?amount]
                        [$history ?p :payment/date ?date]
                        [$current ?tx :tx/corrects ?p]
                        [$current ?tx :tx/reason ?reason]
                        [$current ?tx :db/txInstant ?retract-time]
                        [(get-else $current ?tx :tx/author "") ?author]
                        [(get-else $current ?tx :tx/note "") ?note]]
               :args [hdb db [:contract/id contract-id]]})
         (map (fn [[payment-id amount date reason author note retract-time]]
                {:payment/id     payment-id
                 :payment/amount amount
                 :payment/date   date
                 :retraction/reason reason
                 :retraction/author (when (seq author) author)
                 :retraction/note   (when (seq note) note)
                 :retraction/date   retract-time}))
         (sort-by :retraction/date))))

(defn get-events
  "Build a unified, chronologically sorted event timeline for a contract.

   Merges payments + disbursements + deposits + principal allocations +
   admin events + retracted payments into a single list sorted by date.
   Used by views for the transaction timeline.

   Each event has at minimum: :event-type, :date, :amount (except admin).
   Entity events also have :id and :reference.
   Retracted payments also have :reason, :author, :note, :original-date."
  [db contract-id]
  (let [payments (get-payments db contract-id)
        disbursements (get-disbursements db contract-id)
        deposits (get-deposits db contract-id)
        principal-allocations (get-principal-allocations db contract-id)
        admin-events (get-admin-events db contract-id)
        retracted-payments (get-retracted-payments db contract-id)]
    (->> (concat
           (map (fn [p] {:event-type :payment
                         :id (:payment/id p)
                         :amount (:payment/amount p)
                         :date (:payment/date p)
                         :reference (:payment/reference p)
                         :channel (:payment/channel p)})
                payments)
           (map (fn [d] {:event-type :disbursement
                         :sub-type (:disbursement/type d)
                         :id (:disbursement/id d)
                         :amount (:disbursement/amount d)
                         :date (:disbursement/date d)
                         :reference (:disbursement/reference d)})
                disbursements)
           (map (fn [d] {:event-type :deposit
                         :sub-type (:deposit/type d)
                         :id (:deposit/id d)
                         :amount (:deposit/amount d)
                         :date (:deposit/date d)
                         :reference (:deposit/reference d)})
                deposits)
           (map (fn [pa] {:event-type :principal-allocation
                          :id (:principal-allocation/id pa)
                          :amount (:principal-allocation/amount pa)
                          :date (:principal-allocation/date pa)
                          :reference (:principal-allocation/reference pa)})
                principal-allocations)
           (map (fn [rp] {:event-type :retracted-payment
                          :id (:payment/id rp)
                          :amount (:payment/amount rp)
                          :date (:retraction/date rp)
                          :original-date (:payment/date rp)
                          :reason (:retraction/reason rp)
                          :author (:retraction/author rp)
                          :note (:retraction/note rp)})
                retracted-payments)
           admin-events)
         (sort-by :date))))

(defn get-fees
  "Query all fees for a contract.

   Returns sequence of fee entities (as maps).
   Each map contains: :fee/id, :fee/type, :fee/amount, :fee/due-date"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?e [*])
                      :in $ ?contract-id
                      :where
                      [?e :fee/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)  ;; Extract pull result from each tuple
       (sort-by :fee/due-date)))

(defn get-installments
  "Query all installments for a contract.

   Returns sequence of installment entities (as maps).
   Each map contains: :installment/id, :installment/seq, :installment/due-date,
                     :installment/principal-due, :installment/profit-due

   Sorted by :installment/seq"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?e [*])
                      :in $ ?contract-id
                      :where
                      [?e :installment/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)  ;; Extract pull result from each tuple
       (sort-by :installment/seq)))

(defn get-contract
  "Query contract entity by ID.

   Returns contract entity as map, or nil if not found."
  [db contract-id]
  (d/pull db {:selector '[*]
              :eid [:contract/id contract-id]}))

(defn get-facility
  "Query facility entity by ID.

   Returns facility entity as map, or nil if not found."
  [db facility-id]
  (d/pull db {:selector '[*]
              :eid [:facility/id facility-id]}))

(defn get-contracts-by-facility
  "Query all contracts under a facility.

   Returns sequence of contract entities (as maps).
   Sorted by external-id."
  [db facility-id]
  (->> (d/q {:query '[:find (pull ?e [*])
                      :in $ ?facility-id
                      :where
                      [?e :contract/facility ?f]
                      [?f :facility/id ?facility-id]]
             :args [db facility-id]})
       (map first)
       (sort-by :contract/external-id)))

;; ============================================================
;; Facility State (Derivation)
;; ============================================================

(defn facility-state
  "Derive facility utilization and available balance.

   Utilization is computed from the sum of active contract principals.
   Available is the remaining credit capacity.

   Args:
   - db: database value
   - facility-id: UUID of facility

   Returns map:
   {:id facility-id
    :external-id \"PIP-123\"
    :customer-id \"...\"
    :customer-name \"...\"
    :limit 10000000M
    :funder \"SKFH\"
    :status :active
    :utilization 2000000M      ;; sum of active contract principals
    :available 8000000M        ;; limit - utilization
    :contracts [...]}          ;; list of contracts under facility"
  [db facility-id]
  (let [facility (get-facility db facility-id)
        contracts (get-contracts-by-facility db facility-id)
        active-principals (->> contracts
                               (filter #(= :active (:contract/status %)))
                               (map :contract/principal)
                               (filter some?)
                               (reduce + 0M))
        limit (or (:facility/limit facility) 0M)]
    {:id (:facility/id facility)
     :external-id (:facility/external-id facility)
     :customer-id (:facility/customer-id facility)
     :customer-name (:facility/customer-name facility)
     :limit limit
     :funder (:facility/funder facility)
     :status (:facility/status facility)
     :created-at (:facility/created-at facility)
     :utilization active-principals
     :available (- limit active-principals)
     :contracts (mapv (fn [c]
                        {:id (:contract/id c)
                         :external-id (:contract/external-id c)
                         :principal (:contract/principal c)
                         :status (:contract/status c)})
                      contracts)}))

;; ============================================================
;; Maturity Date Derivation (deprecates stored :contract/maturity-date)
;; ============================================================

(defn derive-maturity-date
  "Derive maturity date from installments.

   Maturity is the due-date of the last installment.
   This deprecates the stored :contract/maturity-date attribute.

   Args:
   - installments: sequence of installment entities

   Returns: java.util.Date or nil if no installments"
  [installments]
  (when (seq installments)
    (->> installments
         (map :installment/due-date)
         (filter some?)
         (sort #(compare (.getTime ^java.util.Date %2) (.getTime ^java.util.Date %1)))
         first)))

;; ============================================================
;; Status Derivation
;; ============================================================

(defn derive-status
  "Derive installment status from payments and due date.

   Status is purely derived - it's the interpretation of facts:
   - FACT: installment due-date is X, paid amount is Y
   - INTERPRETATION: status is :paid/:partial/:overdue/:scheduled

   Rules:
   - :paid - paid >= due (fully paid)
   - :partial - 0 < paid < due (partially paid)
   - :overdue - paid = 0 and as-of > due-date (unpaid and late)
   - :scheduled - paid = 0 and as-of <= due-date (not yet due)

   Args:
   - inst: installment entity
   - total-paid: sum of profit-paid + principal-paid
   - total-due: sum of profit-due + principal-due
   - as-of: java.util.Date (\"now\" for determining overdue)

   Returns keyword: :paid :partial :overdue :scheduled"
  [inst total-paid total-due as-of]
  (let [due-date (:installment/due-date inst)]
    (cond
      (>= total-paid total-due)
      :paid

      (and (pos? total-paid) (< total-paid total-due))
      :partial

      (and (zero? total-paid) (dates/after? as-of due-date))
      :overdue

      :else
      :scheduled)))

(defn- due-date-comparator
  "Compare two items by due date for waterfall ordering."
  [a b]
  (let [date-a (or (:fee/due-date a) (:installment/due-date a))
        date-b (or (:fee/due-date b) (:installment/due-date b))]
    (compare (.getTime ^java.util.Date date-a)
             (.getTime ^java.util.Date date-b))))

(defn compute-thresholds
  "Compute cumulative payment thresholds for each installment.

   Returns map of {installment-id -> threshold} where threshold is the
   minimum cumulative payment needed to fully pay that installment.

   Encodes waterfall allocation order:
   1. All items (fees + installments) sorted by due-date
   2. Fees before installments on same due-date
   3. Cumulative sum through the sorted list

   Example: If fee=5000 (due Jan 1), inst1=100000 (due Feb 1), inst2=100000 (due Mar 1)
   Thresholds: {inst1-id: 105000, inst2-id: 205000}"
  [fees installments]
  (let [tagged-fees (map #(assoc % :_type :fee
                                   :_amount (:fee/amount %))
                         fees)
        tagged-insts (map #(assoc % :_type :installment
                                    :_amount (+ (:installment/profit-due %)
                                                (:installment/principal-due %)))
                          installments)
        ;; Fees concat first = priority on same due-date (stable sort)
        sorted-items (sort due-date-comparator (concat tagged-fees tagged-insts))]
    (first
     (reduce
       (fn [[thresholds cumulative] item]
         (let [new-cumulative (+ cumulative (:_amount item))]
           (if (= :installment (:_type item))
             [(assoc thresholds (:installment/id item) new-cumulative) new-cumulative]
             [thresholds new-cumulative])))
       [{} 0M]
       sorted-items))))

(defn find-paid-dates
  "Replay events chronologically to find when each installment became fully paid.

   Returns map of {installment-id -> paid-date} for installments that are
   currently fully paid after all events.

   Uses precomputed thresholds instead of re-running waterfall per event.

   Waterfall-affecting events:
   - Payments: add to running total (money in)
   - Refund disbursements: subtract from running total (money returned)
   - Deposit offsets: add to running total (deposit applied to balance)
   - Principal allocations: add to running total (funding allocated to fees)

   When a refund reduces total below an installment's threshold, that
   installment's paid-date is removed."
  [fees installments payments disbursements deposits principal-allocations]
  (let [thresholds (compute-thresholds fees installments)
        ;; Build waterfall-affecting events with signed deltas
        waterfall-events
        (concat
          ;; Payments add
          (map (fn [p] {:delta (:payment/amount p)
                        :date (:payment/date p)})
               payments)
          ;; Refund disbursements subtract
          (->> disbursements
               (filter #(= :refund (:disbursement/type %)))
               (map (fn [d] {:delta (- (:disbursement/amount d))
                             :date (:disbursement/date d)})))
          ;; Deposit offsets add
          (->> deposits
               (filter #(= :offset (:deposit/type %)))
               (map (fn [d] {:delta (:deposit/amount d)
                             :date (:deposit/date d)})))
          ;; Principal allocations add
          (map (fn [pa] {:delta (:principal-allocation/amount pa)
                         :date (:principal-allocation/date pa)})
               principal-allocations))
        all-events (sort-by :date waterfall-events)]
    (:paid-dates
     (reduce
       (fn [{:keys [running-total paid-dates]} event]
         (let [new-total (+ running-total (:delta event))
               ;; Only positive deltas (payments/offsets) set paid-date
               payment-date (when (pos? (:delta event))
                              (:date event))
               new-paid-dates
               (reduce
                 (fn [dates [inst-id threshold]]
                   (let [is-paid-now (>= new-total threshold)
                         was-paid (contains? dates inst-id)]
                     (cond
                       (and is-paid-now (not was-paid) payment-date)
                       (assoc dates inst-id payment-date)

                       (and (not is-paid-now) was-paid)
                       (dissoc dates inst-id)

                       :else dates)))
                 paid-dates
                 thresholds)]
           {:running-total new-total
            :paid-dates new-paid-dates}))
       {:running-total 0M :paid-dates {}}
       all-events))))

;; ============================================================
;; Contract State — Decomposed Helpers
;; ============================================================

(defn query-facts
  "Query all entity facts for a contract.

   Returns map of raw Datomic entities, one key per entity type.
   No derivation — just the facts."
  [db contract-id]
  {:contract (get-contract db contract-id)
   :fees (get-fees db contract-id)
   :installments (get-installments db contract-id)
   :payments (get-payments db contract-id)
   :disbursements (get-disbursements db contract-id)
   :deposits (get-deposits db contract-id)
   :principal-allocations (get-principal-allocations db contract-id)})

(defn compute-waterfall-total
  "Compute total payment amount flowing through waterfall.

   All amounts on entities are positive. Direction is determined by entity type:
   - payment/* → money in (adds)
   - disbursement/* type :refund → money out (subtracts)
   - deposit/* type :offset → applied to balance (adds)
   - principal-allocation/* → funding allocated to fees (adds)"
  [payments disbursements deposits principal-allocations]
  (let [payments-sum (->> payments
                          (map :payment/amount)
                          (reduce + 0M))
        refund-sum (->> disbursements
                        (filter #(= :refund (:disbursement/type %)))
                        (map :disbursement/amount)
                        (reduce + 0M))
        offset-sum (->> deposits
                        (filter #(= :offset (:deposit/type %)))
                        (map :deposit/amount)
                        (reduce + 0M))
        allocation-sum (->> principal-allocations
                            (map :principal-allocation/amount)
                            (reduce + 0M))]
    (+ (- payments-sum refund-sum) offset-sum allocation-sum)))

(defn compute-deposit-held
  "Compute security deposit currently held.

   received - (refunded + offset) + transfers in."
  [deposits contract-id]
  (let [deposit-in (->> deposits
                        (filter #(= :received (:deposit/type %)))
                        (map :deposit/amount)
                        (reduce + 0M))
        deposit-out (->> deposits
                         (filter #(#{:refund :offset} (:deposit/type %)))
                         (map :deposit/amount)
                         (reduce + 0M))
        deposit-transfers-in
        (let [contract-ref [:contract/id contract-id]]
          (->> deposits
               (filter #(and (= :transfer (:deposit/type %))
                             (= contract-ref
                                (get-in % [:deposit/target-contract :contract/id]
                                        (:deposit/target-contract %)))))
               (map :deposit/amount)
               (reduce + 0M)))]
    (+ (- deposit-in deposit-out) deposit-transfers-in)))

(defn funding-breakdown
  "Derive how principal was allocated at origination.

   Pure derivation from facts. Returns breakdown + balance check.
   All inputs are raw entity sequences from query-facts.

   Args:
   - contract: contract entity map
   - principal-allocations: principal-allocation entities
   - deposits: deposit entities
   - disbursements: disbursement entities"
  [contract principal-allocations deposits disbursements]
  (let [principal (:contract/principal contract)
        fee-deductions (->> principal-allocations
                            (map :principal-allocation/amount)
                            (reduce + 0M))
        deposit-from-funding (->> deposits
                                  (filter #(and (= :received (:deposit/type %))
                                                (= :funding (:deposit/source %))))
                                  (map :deposit/amount)
                                  (reduce + 0M))
        merchant-disbursement (->> disbursements
                                   (filter #(= :funding (:disbursement/type %)))
                                   (map :disbursement/amount)
                                   (reduce + 0M))
        excess-returned (->> disbursements
                             (filter #(= :excess-return (:disbursement/type %)))
                             (map :disbursement/amount)
                             (reduce + 0M))
        total-allocated (+ fee-deductions deposit-from-funding
                           merchant-disbursement excess-returned)]
    {:principal principal
     :fee-deductions fee-deductions
     :deposit-from-funding deposit-from-funding
     :merchant-disbursement merchant-disbursement
     :excess-returned excess-returned
     :total-allocated total-allocated
     :balanced? (= principal total-allocated)}))

(defn enrich-fees
  "Attach waterfall allocations to fees.

   Returns sequence of enriched fee maps with :paid, :outstanding, :status."
  [fees allocations]
  (for [fee fees
        :let [alloc (waterfall/allocation-for-fee
                     {:allocations allocations}
                     (:fee/id fee))
              paid (or (:amount alloc) 0M)
              amount (:fee/amount fee)
              outstanding (- amount paid)]]
    {:id (:fee/id fee)
     :type (:fee/type fee)
     :amount amount
     :due-date (:fee/due-date fee)
     :paid paid
     :outstanding outstanding
     :status (if (>= paid amount) :paid :unpaid)}))

(defn enrich-installments
  "Attach waterfall allocations to installments and derive status.

   Returns sequence of enriched installment maps with payment info,
   derived status, and days delinquent."
  [installments allocations paid-dates as-of]
  (for [inst installments
        :let [alloc (waterfall/allocation-for-installment
                     {:allocations allocations}
                     (:installment/id inst))
              profit-paid (or (:profit-paid alloc) 0M)
              principal-paid (or (:principal-paid alloc) 0M)
              profit-due (:installment/profit-due inst)
              principal-due (:installment/principal-due inst)
              total-due (+ profit-due principal-due)
              total-paid (+ profit-paid principal-paid)
              outstanding (- total-due total-paid)
              paid-date (get paid-dates (:installment/id inst))
              days-delinquent (dates/days-between
                                (:installment/due-date inst)
                                (or paid-date as-of))]]
    {:id (:installment/id inst)
     :seq (:installment/seq inst)
     :due-date (:installment/due-date inst)
     :remaining-principal (:installment/remaining-principal inst)
     :principal-due principal-due
     :profit-due profit-due
     :total-due total-due
     :principal-paid principal-paid
     :profit-paid profit-paid
     :total-paid total-paid
     :outstanding outstanding
     :status (derive-status inst total-paid total-due as-of)
     :days-delinquent days-delinquent}))

(defn compute-totals
  "Aggregate totals from raw entities and enriched collections."
  [fees enriched-fees installments enriched-installments]
  (let [total-fees-due (reduce + 0M (map :fee/amount fees))
        total-fees-paid (reduce + 0M (map :paid enriched-fees))
        total-principal-due (reduce + 0M (map :installment/principal-due installments))
        total-principal-paid (reduce + 0M (map :principal-paid enriched-installments))
        total-profit-due (reduce + 0M (map :installment/profit-due installments))
        total-profit-paid (reduce + 0M (map :profit-paid enriched-installments))
        total-outstanding (+ (reduce + 0M (map :outstanding enriched-fees))
                            (reduce + 0M (map :outstanding enriched-installments)))]
    {:total-fees-due total-fees-due
     :total-fees-paid total-fees-paid
     :total-principal-due total-principal-due
     :total-principal-paid total-principal-paid
     :total-profit-due total-profit-due
     :total-profit-paid total-profit-paid
     :total-outstanding total-outstanding}))

;; ============================================================
;; Contract State (Composition)
;; ============================================================

(defn contract-state
  "Compute complete contract state from facts.

   Composes query-facts → waterfall → enrich → totals.
   Return shape is unchanged — all callers work as before.

   Args:
   - db: Datomic database value
   - contract-id: UUID
   - as-of: java.util.Date (for determining overdue status)"
  [db contract-id as-of]
  (let [{:keys [contract fees installments payments disbursements deposits
                principal-allocations]}
        (query-facts db contract-id)

        total-payments (compute-waterfall-total payments disbursements deposits
                                                principal-allocations)
        deposit-held (compute-deposit-held deposits contract-id)

        {:keys [allocations credit-balance]}
        (waterfall/waterfall fees installments total-payments)

        paid-dates (find-paid-dates fees installments payments disbursements deposits
                                    principal-allocations)
        enriched-fees (enrich-fees fees allocations)
        enriched-installments (enrich-installments installments allocations paid-dates as-of)
        totals (compute-totals fees enriched-fees installments enriched-installments)]

    (merge
      {:contract {:id (:contract/id contract)
                  :external-id (:contract/external-id contract)
                  :customer-name (:contract/customer-name contract)
                  :customer-id (:contract/customer-id contract)
                  :status (:contract/status contract)
                  :start-date (:contract/start-date contract)
                  :maturity-date (derive-maturity-date installments)
                  :principal (:contract/principal contract)
                  :net-disbursement (:contract/net-disbursement contract)
                  :security-deposit-required (:contract/security-deposit contract)
                  :step-up-terms (:contract/step-up-terms contract)
                  :facility-id (get-in contract [:contract/facility :facility/id])
                  :commodity {:quantity (:contract/commodity-quantity contract)
                              :unit-price (:contract/commodity-unit-price contract)
                              :description (:contract/commodity-description contract)
                              :vendor (:contract/commodity-vendor contract)}
                  :disbursement-iban (:contract/disbursement-iban contract)
                  :disbursement-bank (:contract/disbursement-bank contract)
                  :virtual-iban (:contract/virtual-iban contract)
                  :refinances-id (get-in contract [:contract/refinances :contract/id])}
       :fees enriched-fees
       :installments enriched-installments
       :deposit-held deposit-held
       :credit-balance credit-balance}
      totals)))

;; ============================================================
;; Query Helpers
;; ============================================================

(defn list-contracts
  "List all contracts with optional status filter.

   Returns sequence of contract summary maps (not full state):
   {:id ... :external-id ... :customer-name ... :status ...}

   For full state, use contract-state on each ID.

   Args:
   - db: database value
   - status-filter: optional keyword (:active, :closed, etc.) or nil for all"
  [db status-filter]
  (let [results (if status-filter
                  (d/q {:query '[:find ?id ?ext-id ?name ?status
                                 :in $ ?status-filter
                                 :where
                                 [?e :contract/id ?id]
                                 [?e :contract/external-id ?ext-id]
                                 [?e :contract/customer-name ?name]
                                 [?e :contract/status ?status]
                                 [(= ?status ?status-filter)]]
                        :args [db status-filter]})
                  (d/q {:query '[:find ?id ?ext-id ?name ?status
                                 :in $
                                 :where
                                 [?e :contract/id ?id]
                                 [?e :contract/external-id ?ext-id]
                                 [?e :contract/customer-name ?name]
                                 [?e :contract/status ?status]]
                        :args [db]}))]
    (->> results
         (map (fn [[id ext-id name status]]
                {:id id
                 :external-id ext-id
                 :customer-name name
                 :status status}))
         (sort-by :external-id))))

(defn parse-step-up-terms
  "Parse step-up terms EDN string from contract.

   Returns vector of term maps, or nil if no step-up terms.

   Example:
   [{:term 1 :installments [1 4] :rate 0.15}
    {:term 2 :installments [5 8] :base-rate 0.18 :on-time-rate 0.15}]"
  [contract]
  (when-let [terms-str (:contract/step-up-terms contract)]
    (edn/read-string terms-str)))

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  (require '[lms.db :as db])
  (require '[lms.operations :as ops])
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Create test contract with schedule (via operations)
  (def test-contract-id (random-uuid))

  (ops/board-contract conn
                      {:contract/id test-contract-id
                       :contract/external-id "TEST-001"
                       :contract/customer-name "Test Customer Co."
                       :contract/customer-id "CR-123456"
                       :contract/status :active
                       :contract/start-date #inst "2024-01-01"
                       :contract/principal 1200000M
                       :contract/security-deposit 60000M}
                      [{:fee/id (random-uuid)
                        :fee/type :management
                        :fee/amount 5000M
                        :fee/due-date #inst "2024-01-01"}]
                      [{:installment/id (random-uuid)
                        :installment/seq 1
                        :installment/due-date #inst "2024-01-31"
                        :installment/principal-due 100000M
                        :installment/profit-due 10000M
                        :installment/remaining-principal 1200000M}
                       {:installment/id (random-uuid)
                        :installment/seq 2
                        :installment/due-date #inst "2024-02-28"
                        :installment/principal-due 100000M
                        :installment/profit-due 10000M
                        :installment/remaining-principal 1100000M}]
                      "repl-user")

  ;; Record a payment — creates payment/* entity
  (ops/record-payment conn test-contract-id 50000M #inst "2024-01-15"
                      "TEST-PAY-001" "repl-user")

  ;; Query entities directly
  (get-payments (d/db conn) test-contract-id)
  ;; => [{:payment/id #uuid "..." :payment/amount 50000M :payment/date #inst "2024-01-15" ...}]

  (get-disbursements (d/db conn) test-contract-id)
  (get-deposits (d/db conn) test-contract-id)
  (get-admin-events (d/db conn) test-contract-id)

  ;; Unified timeline
  (get-events (d/db conn) test-contract-id)

  ;; Compute contract state
  (def state (contract-state (d/db conn) test-contract-id (java.util.Date.)))

  ;; Inspect state
  (:total-outstanding state)
  ;; => Should be: 1200000 + 20000 (profit) + 5000 (fee) - 50000 (payment)

  (:fees state)
  ;; => [{:id ... :type :management :amount 5000M :paid 5000M
  ;;      :outstanding 0M :status :paid}]

  (:installments state)
  ;; => [{:seq 1 ... :profit-paid 10000M :principal-paid 35000M
  ;;      :outstanding 65000M :status :partial}
  ;;     {:seq 2 ... :profit-paid 0M :principal-paid 0M
  ;;      :outstanding 110000M :status :scheduled}]

  ;; List all contracts
  (list-contracts (d/db conn) nil)
  (list-contracts (d/db conn) :active)

  )
