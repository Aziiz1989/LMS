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
            [lms.settlement :as settlement]
            [lms.dates :as dates]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; Forward declarations — document derivations reference functions defined later
(declare get-contract contract-state)

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
   :principal-allocation/type (optional), :principal-allocation/fee (optional ref),
   :principal-allocation/reference (optional)"
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?pa [* {:principal-allocation/fee [:fee/id]}])
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
        retracted-payments (get-retracted-payments db contract-id)
        ;; Document events — use txInstant as generation date
        clearance-letters
        (d/q {:query '[:find ?cl-id ?amount ?tx-time
                       :in $ ?contract-id
                       :where
                       [?cl :clearance-letter/contract ?c]
                       [?c :contract/id ?contract-id]
                       [?cl :clearance-letter/id ?cl-id ?tx]
                       [?cl :clearance-letter/settlement-amount ?amount]
                       [?tx :db/txInstant ?tx-time]]
              :args [db contract-id]})
        statements
        (d/q {:query '[:find ?s-id ?period-start ?period-end ?tx-time
                       :in $ ?contract-id
                       :where
                       [?s :statement/contract ?c]
                       [?c :contract/id ?contract-id]
                       [?s :statement/id ?s-id ?tx]
                       [?s :statement/period-start ?period-start]
                       [?s :statement/period-end ?period-end]
                       [?tx :db/txInstant ?tx-time]]
              :args [db contract-id]})
        agreements
        (d/q {:query '[:find ?ca-id ?tx-time
                       :in $ ?contract-id
                       :where
                       [?ca :contract-agreement/contract ?c]
                       [?c :contract/id ?contract-id]
                       [?ca :contract-agreement/id ?ca-id ?tx]
                       [?tx :db/txInstant ?tx-time]]
              :args [db contract-id]})]
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
          (map (fn [[cl-id amount tx-time]]
                 {:event-type :clearance-letter
                  :id cl-id
                  :amount amount
                  :date tx-time})
               clearance-letters)
          (map (fn [[s-id period-start period-end tx-time]]
                 {:event-type :statement
                  :id s-id
                  :date tx-time
                  :period-start period-start
                  :period-end period-end})
               statements)
          (map (fn [[ca-id tx-time]]
                 {:event-type :contract-agreement
                  :id ca-id
                  :date tx-time})
               agreements)
          admin-events)
         (sort-by :date))))

;; ============================================================
;; Document Query Functions (Fetch Facts)
;; ============================================================

(defn get-clearance-letters
  "Query all clearance letter entities for a contract.

   Returns sequence sorted by txInstant (generation date).
   Each map contains all clearance-letter/* attributes."
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?cl [*]) ?tx-time
                      :in $ ?contract-id
                      :where
                      [?cl :clearance-letter/contract ?c]
                      [?c :contract/id ?contract-id]
                      [?cl :clearance-letter/id _ ?tx]
                      [?tx :db/txInstant ?tx-time]]
             :args [db contract-id]})
       (sort-by second)
       (mapv first)))

(defn get-statements
  "Query all statement entities for a contract.

   Returns sequence sorted by period-end."
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?s [*])
                      :in $ ?contract-id
                      :where
                      [?s :statement/contract ?c]
                      [?c :contract/id ?contract-id]]
             :args [db contract-id]})
       (map first)
       (sort-by :statement/period-end)))

(defn get-contract-agreements
  "Query all contract agreement entities for a contract.

   Returns sequence sorted by txInstant (generation date)."
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?ca [*]) ?tx-time
                      :in $ ?contract-id
                      :where
                      [?ca :contract-agreement/contract ?c]
                      [?c :contract/id ?contract-id]
                      [?ca :contract-agreement/id _ ?tx]
                      [?tx :db/txInstant ?tx-time]]
             :args [db contract-id]})
       (sort-by second)
       (mapv (fn [[entity tx-time]] (assoc entity :db/txInstant tx-time)))))

(defn get-signings
  "Query all signing entities for a document.

   Takes the document's lookup ref (e.g., [:clearance-letter/id uuid]).
   Returns sequence sorted by :signing/date."
  [db document-ref]
  (->> (d/q {:query '[:find (pull ?s [* {:signing/party [:party/id :party/type
                                                         :party/legal-name
                                                         :party/national-id]}])
                      :in $ ?doc
                      :where
                      [?s :signing/document ?doc]]
             :args [db document-ref]})
       (map first)
       (sort-by :signing/date)))

(defn get-documents
  "Get all documents for a contract.

   Convenience function that queries all three document types in one call.

   Returns: {:clearance-letters [...] :statements [...] :contract-agreements [...]}"
  [db contract-id]
  {:clearance-letters (get-clearance-letters db contract-id)
   :statements (get-statements db contract-id)
   :contract-agreements (get-contract-agreements db contract-id)})

;; ============================================================
;; Document Derivations
;; ============================================================

(defn get-active-clearance-letters
  "Return clearance letters that haven't been superseded.

   A letter is superseded when another letter's :clearance-letter/supersedes
   points to it. Comparison is by :db/id (entity identity)."
  [db contract-id]
  (let [all-letters (get-clearance-letters db contract-id)
        superseded-eids (->> all-letters
                             (keep :clearance-letter/supersedes)
                             (map :db/id)
                             set)]
    (remove #(contains? superseded-eids (:db/id %)) all-letters)))

(defn contract-signed?
  "Derive whether the latest contract agreement is fully signed.

   True when every authorized signatory on the contract has a signing fact
   for the most recent contract-agreement. No 'signed' flag is stored —
   this is pure derivation from signing/* and contract/* facts."
  [db contract-id]
  (let [agreements (get-contract-agreements db contract-id)]
    (when (seq agreements)
      (let [latest (last agreements)
            signings (get-signings db (:db/id latest))
            signed-party-ids (set (map #(get-in % [:signing/party :party/id]) signings))
            contract (get-contract db contract-id)
            required-ids (set (map :party/id (:contract/authorized-signatories contract)))]
        (and (seq required-ids)
             (every? signed-party-ids required-ids))))))

(defn check-clearance-contradictions
  "Compare committed settlement amounts against current computation.

   For each active (non-superseded) clearance letter, recomputes settlement
   using the same parameters (settlement-date, penalty-days) and compares
   the result with the committed settlement-amount.

   Returns vec of contradiction maps, or empty vec if consistent.
   Each map: {:clearance-letter cl, :committed M, :current M, :difference M}"
  [db contract-id as-of]
  (let [active-letters (get-active-clearance-letters db contract-id)
        state (contract-state db contract-id as-of)]
    (->> active-letters
         (keep (fn [cl]
                 (let [committed (:clearance-letter/settlement-amount cl)
                       recomputed (settlement/calculate-settlement
                                   state
                                   (:clearance-letter/settlement-date cl)
                                   (:clearance-letter/penalty-days cl))
                       current (:settlement-amount recomputed)]
                   (when (not= committed current)
                     {:clearance-letter cl
                      :committed committed
                      :current current
                      :difference (- current committed)}))))
         vec)))

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

   Returns contract entity as map, or nil if not found.
   Navigates party refs to include borrower/guarantor/signatory attributes."
  [db contract-id]
  (d/pull db {:selector '[:db/id :contract/id :contract/external-id
                          :contract/start-date :contract/principal
                          :contract/security-deposit :contract/step-up-terms
                          :contract/disbursed-at :contract/written-off-at
                          :contract/days-to-first-installment
                          :contract/net-disbursement
                          :contract/commodity-quantity :contract/commodity-unit-price
                          :contract/commodity-description :contract/commodity-vendor
                          :contract/disbursement-iban :contract/disbursement-bank
                          :contract/virtual-iban
                          {:contract/facility [:facility/id]}
                          {:contract/borrower [:party/id :party/type :party/legal-name
                                               :party/cr-number :party/national-id]}
                          {:contract/guarantors [:party/id :party/type :party/legal-name
                                                 :party/cr-number :party/national-id]}
                          {:contract/authorized-signatories [:party/id :party/type :party/legal-name
                                                             :party/national-id]}]
              :eid [:contract/id contract-id]}))

(defn get-facility
  "Query facility entity by ID.

   Returns facility entity as map, or nil if not found.
   Navigates :facility/party ref to include party attributes."
  [db facility-id]
  (d/pull db {:selector '[:facility/id :facility/external-id :facility/limit
                          :facility/funder :facility/status :facility/created-at
                          {:facility/party [:party/id :party/legal-name :party/cr-number]}]
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
    :party {:id uuid :legal-name \"...\" :cr-number \"...\"}
    :limit 10000000M
    :funder \"SKFH\"
    :status :active
    :utilization 2000000M      ;; sum of active contract principals
    :available 8000000M        ;; limit - utilization
    :contracts [...]}          ;; list of contracts under facility"
  [db facility-id]
  (let [facility (get-facility db facility-id)
        contracts (get-contracts-by-facility db facility-id)
        ;; Active = disbursed but not written-off
        ;; Note: We use disbursed-at/written-off-at facts to determine if active
        ;; A more complete calculation would also check if fully paid (closed)
        active-principals (->> contracts
                               (filter #(and (some? (:contract/disbursed-at %))
                                             (nil? (:contract/written-off-at %))))
                               (map :contract/principal)
                               (filter some?)
                               (reduce + 0M))
        limit (or (:facility/limit facility) 0M)]
    {:id (:facility/id facility)
     :external-id (:facility/external-id facility)
     :party (when-let [p (:facility/party facility)]
              {:id (:party/id p)
               :legal-name (:party/legal-name p)
               :cr-number (:party/cr-number p)})
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
                         :disbursed-at (:contract/disbursed-at c)
                         :written-off-at (:contract/written-off-at c)})
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

(defn- waterfall-allocation?
  "True if this principal-allocation flows through the waterfall.
   :deposit type does NOT flow through waterfall (deposit ledger is separate).
   nil type = backward compat (old allocations before type was added)."
  [pa]
  (not= :deposit (:principal-allocation/type pa)))

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
          ;; Principal allocations add (only waterfall types)
         (->> principal-allocations
              (filter waterfall-allocation?)
              (map (fn [pa] {:delta (:principal-allocation/amount pa)
                             :date (:principal-allocation/date pa)}))))
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
;; Status Derivation (replaces stored :contract/status)
;; ============================================================

(defn contract-is-refinanced?
  "A contract is refinanced if it received a payment funded by another contract.

   Uses :payment/source-contract to detect refinancing payments. When a new contract
   pays off an old one, the payment on the old contract has source-contract pointing
   to the new contract.

   Args:
   - payments: sequence of payment entities for this contract

   Returns: truthy if any payment has :payment/source-contract set"
  [payments]
  (some :payment/source-contract payments))

(defn derive-contract-status
  "Derive contract status from facts.

   Status is purely computed — not stored. Priority order:
   1. Written off (board decision) → :written-off
   2. Refinanced (payment from another contract) → :refinanced
   3. Paid off (no outstanding balance, disbursed) → :closed
   4. Disbursed (funding sent) → :active
   5. Not yet disbursed → :pending

   Args:
   - contract: contract entity map (needs :contract/written-off-at, :contract/disbursed-at)
   - payments: sequence of payment entities
   - total-outstanding: computed total outstanding balance (fees + installments)

   Returns: keyword status"
  [contract payments total-outstanding]
  (cond
    (:contract/written-off-at contract)
    :written-off

    (contract-is-refinanced? payments)
    :refinanced

    (and (:contract/disbursed-at contract)
         (zero? total-outstanding))
    :closed

    (:contract/disbursed-at contract)
    :active

    :else
    :pending))

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
   - principal-allocation/* type :fee-settlement or :installment-prepayment → adds
   - principal-allocation/* type :deposit → excluded (deposit ledger is separate)"
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
                            (filter waterfall-allocation?)
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
                            (filter #(not= :deposit (:principal-allocation/type %)))
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

(defn derive-fee-due-dates
  "Compute fee due-dates from days-after-disbursement + disbursed-at.
   Falls back to today when not yet disbursed."
  [fees disbursed-at]
  (let [base-date (or disbursed-at (java.util.Date.))]
    (mapv (fn [fee]
            (assoc fee :fee/due-date
                   (dates/add-days base-date
                                   (or (:fee/days-after-disbursement fee) 0))))
          fees)))

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

        ;; Derive fee due-dates from days-after-disbursement + disbursed-at
        fees (derive-fee-due-dates fees (:contract/disbursed-at contract))

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
     {:contract (let [borrower (:contract/borrower contract)]
                  {:id (:contract/id contract)
                   :external-id (:contract/external-id contract)
                   :borrower (when borrower
                               {:id (:party/id borrower)
                                :legal-name (:party/legal-name borrower)
                                :cr-number (:party/cr-number borrower)})
                   :customer-name (get-in contract [:contract/borrower :party/legal-name])
                   :guarantors (mapv (fn [g] {:id (:party/id g)
                                              :legal-name (:party/legal-name g)
                                              :type (:party/type g)})
                                     (:contract/guarantors contract))
                   :authorized-signatories (mapv (fn [s] {:id (:party/id s)
                                                          :legal-name (:party/legal-name s)})
                                                 (:contract/authorized-signatories contract))
                   :status (derive-contract-status contract payments (:total-outstanding totals))
                   :maturity-date (derive-maturity-date installments)
                   :disbursed-at (:contract/disbursed-at contract)
                   :written-off-at (:contract/written-off-at contract)
                   :principal (:contract/principal contract)
                   :net-disbursement (:contract/net-disbursement contract)
                   :security-deposit-required (:contract/security-deposit contract)
                   :step-up-terms (:contract/step-up-terms contract)
                   :days-to-first-installment (:contract/days-to-first-installment contract)
                   :facility-id (get-in contract [:contract/facility :facility/id])
                   :commodity {:quantity (:contract/commodity-quantity contract)
                               :unit-price (:contract/commodity-unit-price contract)
                               :description (:contract/commodity-description contract)
                               :vendor (:contract/commodity-vendor contract)}
                   :disbursement-iban (:contract/disbursement-iban contract)
                   :disbursement-bank (:contract/disbursement-bank contract)
                   :virtual-iban (:contract/virtual-iban contract)
                   :refinanced? (contract-is-refinanced? payments)})
      :fees enriched-fees
      :installments enriched-installments
      :deposit-held deposit-held
      :credit-balance credit-balance
      :documents {:clearance-letters (get-clearance-letters db contract-id)
                  :statements (get-statements db contract-id)
                  :contract-agreements (get-contract-agreements db contract-id)}}
     totals)))

;; ============================================================
;; Query Helpers
;; ============================================================

(defn derive-list-status
  "Derive status for list view: pending, active, or closed.

   Requires total-outstanding to determine if closed.
   - :pending - not yet disbursed
   - :closed - disbursed and fully paid (total-outstanding = 0)
   - :active - disbursed but not fully paid"
  [disbursed-at total-outstanding]
  (cond
    (nil? disbursed-at) :pending
    (zero? total-outstanding) :closed
    :else :active))

(defn list-contracts
  "List all contracts with derived status.

   Returns sequence of contract summary maps:
   {:id ... :external-id ... :customer-name ... :status ... :disbursed-at ...}
   Note: :customer-name is sourced from the borrower party's legal name.

   Status is derived: :pending, :active, or :closed.
   Note: This queries payments for each contract to compute total-outstanding.

   Args:
   - db: database value"
  [db]
  (let [results (d/q {:query '[:find (pull ?e [:contract/id
                                               :contract/external-id
                                               :contract/disbursed-at
                                               {:contract/borrower [:party/legal-name]}])
                               :in $
                               :where
                               [?e :contract/id _]]
                      :args [db]})]
    (->> results
         (map first)
         (map (fn [c]
                (let [contract-id (:contract/id c)
                      ;; Query facts needed for status derivation
                      raw-fees (get-fees db contract-id)
                      fees (derive-fee-due-dates raw-fees (:contract/disbursed-at c))
                      installments (get-installments db contract-id)
                      payments (get-payments db contract-id)
                      disbursements (get-disbursements db contract-id)
                      deposits (get-deposits db contract-id)
                      principal-allocations (get-principal-allocations db contract-id)
                      ;; Compute total for waterfall
                      total-payments (compute-waterfall-total payments disbursements
                                                              deposits principal-allocations)
                      ;; Run waterfall to get allocations
                      {:keys [allocations]} (waterfall/waterfall fees installments total-payments)
                      ;; Compute totals
                      enriched-fees (enrich-fees fees allocations)
                      enriched-installments (enrich-installments installments allocations {} (java.util.Date.))
                      totals (compute-totals fees enriched-fees installments enriched-installments)]
                  {:id contract-id
                   :external-id (:contract/external-id c)
                   :customer-name (get-in c [:contract/borrower :party/legal-name])
                   :status (derive-list-status (:contract/disbursed-at c)
                                               (:total-outstanding totals))
                   :disbursed-at (:contract/disbursed-at c)})))
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
;; Comprehensive History (Datom-level Audit Trail)
;; ============================================================

(defn- build-attr-ident-map
  "Build map from Datomic attribute entity IDs to their ident keywords.
   Used to resolve attribute entity IDs from tx-range datoms."
  [db]
  (into {} (d/q {:query '[:find ?e ?ident :where [?e :db/ident ?ident]]
                 :args [db]})))

(defn get-contract-entity-ids
  "Get all entity IDs (db/id) associated with a contract.
   Queries history DB to include entities that were later retracted."
  [db contract-id]
  (let [hdb (d/history db)
        ;; Find the contract entity first
        contract-eids (d/q {:query '[:find ?e
                                     :in $ ?cid
                                     :where [?e :contract/id ?cid]]
                            :args [hdb contract-id]})
        ;; Then find all child entities (all or-branches use same vars: ?e ?c)
        child-eids (when-let [c (ffirst contract-eids)]
                     (d/q {:query '[:find ?e
                                    :in $ ?c
                                    :where
                                    (or [?e :fee/contract ?c]
                                        [?e :installment/contract ?c]
                                        [?e :payment/contract ?c]
                                        [?e :disbursement/contract ?c]
                                        [?e :deposit/contract ?c]
                                        [?e :deposit/target-contract ?c]
                                        [?e :principal-allocation/contract ?c]
                                        [?e :clearance-letter/contract ?c]
                                        [?e :statement/contract ?c]
                                        [?e :contract-agreement/contract ?c])]
                           :args [hdb c]}))
        ;; Signing entities reference documents, not contracts — two-hop join
        doc-eids (into #{} (map first) child-eids)
        signing-eids (when (seq doc-eids)
                       (d/q {:query '[:find ?s
                                      :in $ [?doc ...]
                                      :where [?s :signing/document ?doc]]
                             :args [hdb (vec doc-eids)]}))]
    (into #{} (map first) (concat contract-eids child-eids signing-eids))))

(defn- entity-type-from-attr
  "Determine entity type keyword from attribute namespace."
  [attr-ident]
  (when-let [ns (namespace attr-ident)]
    (case ns
      "contract" :contract
      "installment" :installment
      "fee" :fee
      "payment" :payment
      "disbursement" :disbursement
      "deposit" :deposit
      "principal-allocation" :principal-allocation
      "clearance-letter" :clearance-letter
      "statement" :statement
      "contract-agreement" :contract-agreement
      "signing" :signing
      nil)))

(def ^:private internal-attrs
  "Attributes to exclude from history display."
  #{:db/txInstant :db/doc :db/ident :db/valueType :db/cardinality :db/unique})

(def ^:private reference-attrs
  "Attributes that point to parent entities (redundant in contract context)."
  #{:payment/contract :fee/contract :installment/contract
    :disbursement/contract :deposit/contract :deposit/target-contract
    :principal-allocation/contract :contract/facility
    :clearance-letter/contract :statement/contract
    :contract-agreement/contract :signing/document})

(defn build-entity-label-cache
  "Build a cache of entity labels by querying the history DB.
   Returns map of {entity-id {:type :payment :label \"FT-123\" :seq nil :sub-type nil}}."
  [db entity-ids]
  (let [hdb (d/history db)
        ;; Query label attributes from history (covers retracted entities)
        refs (d/q {:query '[:find ?e ?v
                            :in $ [?e ...]
                            :where
                            (or [?e :payment/reference ?v]
                                [?e :disbursement/reference ?v]
                                [?e :deposit/reference ?v]
                                [?e :principal-allocation/reference ?v]
                                [?e :contract/external-id ?v])]
                   :args [hdb (vec entity-ids)]})
        ref-map (into {} refs)
        ;; Sequence numbers for installments
        seqs (d/q {:query '[:find ?e ?v
                            :in $ [?e ...]
                            :where [?e :installment/seq ?v]]
                   :args [hdb (vec entity-ids)]})
        seq-map (into {} seqs)
        ;; Fee types
        fee-types (d/q {:query '[:find ?e ?v
                                 :in $ [?e ...]
                                 :where [?e :fee/type ?v]]
                        :args [hdb (vec entity-ids)]})
        fee-type-map (into {} fee-types)
        ;; Sub-types for disbursements and deposits
        sub-types (d/q {:query '[:find ?e ?v
                                 :in $ [?e ...]
                                 :where
                                 (or [?e :disbursement/type ?v]
                                     [?e :deposit/type ?v])]
                        :args [hdb (vec entity-ids)]})
        sub-type-map (into {} sub-types)]
    (into {}
          (for [eid entity-ids]
            [eid {:label (or (get ref-map eid)
                             (when-let [s (get seq-map eid)] (str "Installment #" s))
                             (when-let [ft (get fee-type-map eid)] (str (str/capitalize (name ft)) " Fee")))
                  :seq (get seq-map eid)
                  :sub-type (get sub-type-map eid)}]))))

(defn get-comprehensive-history
  "Query complete Datomic history for all entities associated with a contract.

   Uses d/tx-range to get per-transaction datom data with the added? flag,
   filtered to datoms belonging to this contract's entities.

   Returns vector of tx maps sorted by timestamp:
   [{:tx-id       long
     :tx-instant  #inst \"...\"
     :tx-metadata {:tx/author \"user\" :tx/type :boarding ...}
     :changes     [{:entity-id long :entity-type :payment
                    :attribute :payment/amount :value 50000M :added? true}]}]"
  [conn db contract-id & [{:keys [entity-types from-date to-date]}]]
  (let [entity-ids (get-contract-entity-ids db contract-id)]
    (when (seq entity-ids)
      (let [attr-idents (build-attr-ident-map db)
            all-txs (d/tx-range conn {})
            result
            (reduce
             (fn [acc tx-data]
               (let [data (:data tx-data)
                      ;; Filter datoms to our contract's entities, excluding noise
                     relevant
                     (reduce
                      (fn [datoms d]
                        (let [e (nth d 0)
                              a-eid (nth d 1)
                              ident (get attr-idents a-eid)]
                          (if (and (contains? entity-ids e)
                                   ident
                                   (not (contains? internal-attrs ident))
                                   (not (contains? reference-attrs ident))
                                   (entity-type-from-attr ident)
                                   (or (nil? entity-types)
                                       (contains? (set entity-types)
                                                  (entity-type-from-attr ident))))
                            (conj datoms {:entity-id e
                                          :entity-type (entity-type-from-attr ident)
                                          :attribute ident
                                          :value (nth d 2)
                                          :added? (nth d 4)})
                            datoms)))
                      []
                      data)]
                 (if (seq relevant)
                   (let [tx-eid (nth (first data) 3)
                         tx-pull (try
                                   (d/pull db [:db/txInstant :tx/author :tx/type
                                               :tx/note :tx/reason] tx-eid)
                                   (catch Exception _ {}))
                         tx-instant (:db/txInstant tx-pull)]
                     (if (and (or (nil? from-date)
                                  (not (.before ^java.util.Date tx-instant from-date)))
                              (or (nil? to-date)
                                  (.before ^java.util.Date tx-instant to-date)))
                       (conj acc {:tx-id tx-eid
                                  :tx-instant tx-instant
                                  :tx-metadata (dissoc tx-pull :db/txInstant :db/id)
                                  :changes relevant})
                       acc))
                   acc)))
             []
             all-txs)]
        (sort-by :tx-instant result)))))

(defn- classify-entity-changes
  "Classify changes for a single entity within a TX.
   Pairs assertion+retraction of same attribute as :updated.
   Returns {:operation :created|:updated|:retracted, :attr-changes [...]}"
  [changes]
  (let [by-attr (group-by :attribute changes)
        attr-changes
        (vec
         (for [[attr datoms] by-attr
               :let [asserted (filter :added? datoms)
                     retracted (remove :added? datoms)]]
           {:attribute attr
            :old-value (first (map :value retracted))
            :new-value (first (map :value asserted))
            :operation (cond
                         (and (seq asserted) (seq retracted)) :updated
                         (seq asserted) :asserted
                         :else :retracted)}))
        all-asserted? (every? #(= :asserted (:operation %)) attr-changes)
        all-retracted? (every? #(= :retracted (:operation %)) attr-changes)]
    {:operation (cond all-asserted? :created
                      all-retracted? :retracted
                      :else :updated)
     :attr-changes attr-changes}))

(defn- attribute-display-name
  "Human-readable name for an attribute ident.
   :payment/amount -> \"Amount\", :installment/profit-due -> \"Profit Due\"."
  [attr-ident]
  (-> (name attr-ident)
      (str/replace "-" " ")
      (str/replace #"(?:^|\\s)\\w"
                   #(str/upper-case %))))

(defn- format-value
  "Format a Datomic value for human display."
  [_attr-ident value]
  (cond
    (nil? value) nil
    (instance? java.math.BigDecimal value) (str "SAR " (format "%.2f" (double value)))
    (instance? java.util.Date value) (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") value)
    (keyword? value) (str/capitalize (name value))
    (uuid? value) (subs (str value) 0 8)
    (string? value) value
    (number? value) (str value)
    :else (str value)))

(defn format-history-for-display
  "Transform raw history into display-ready format.

   Groups changes by entity, pairs assertion/retraction for before->after,
   classifies operations, enriches with entity labels.

   Args:
   - raw-history: from get-comprehensive-history
   - entity-labels: from build-entity-label-cache

   Returns vector of processed tx maps with :entities and :operation keys."
  [raw-history entity-labels]
  (vec
   (for [tx raw-history
         :let [changes (:changes tx)
               by-entity (group-by :entity-id changes)
               entities
               (vec
                (for [[eid entity-changes] by-entity
                      :let [entity-type (:entity-type (first entity-changes))
                            {:keys [operation attr-changes]}
                            (classify-entity-changes entity-changes)
                            label-info (get entity-labels eid)
                              ;; Format attr changes for display
                            display-changes
                            (vec
                             (for [c attr-changes
                                   :when (not (#{:asserted :retracted}
                                               (:operation c))
                                               ;; Show all for updates, skip identity attrs for create/retract
                                              )]
                               (assoc c
                                      :display-name (attribute-display-name (:attribute c))
                                      :display-old (format-value (:attribute c) (:old-value c))
                                      :display-new (format-value (:attribute c) (:new-value c)))))]]
                  {:entity-id eid
                   :entity-type entity-type
                   :label (:label label-info)
                   :sub-type (:sub-type label-info)
                   :operation operation
                   :changes attr-changes
                   :display-changes display-changes}))
               tx-operation
               (cond
                 (some-> tx :tx-metadata :tx/type) :admin
                 (some-> tx :tx-metadata :tx/reason) :correction
                 (every? #(= :created (:operation %)) entities) :created
                 (every? #(= :retracted (:operation %)) entities) :retracted
                 :else :updated)]]
     (assoc tx
            :entities entities
            :operation tx-operation))))

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  (require '[lms.db :as db])
  (require '[lms.operations :as ops])
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Create test party (borrower)
  (require '[lms.party :as party])
  (def test-party-id (random-uuid))
  (party/create-party conn {:party/type :party.type/company
                            :party/legal-name "Test Customer Co."
                            :party/cr-number "CR-123456"}
                      "repl-user")

  ;; Create test contract with schedule (via operations)
  (def test-contract-id (random-uuid))

  (ops/board-contract conn
                      {:contract/id test-contract-id
                       :contract/external-id "TEST-001"
                       :contract/borrower [:party/id test-party-id]
                       :contract/disbursed-at #inst "2024-01-02"
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
  (list-contracts (d/db conn)))
