(ns lms.boarding
  "Contract boarding orchestration.

   This namespace coordinates the boarding of new and existing contracts.
   It validates input data, delegates to operations/board-contract for
   the atomic write, and handles payment replay for existing loans.

   Two entry points:
   - board-new-contract: New loan (validate + board, disbursement separate)
   - board-existing-contract: Existing loan migration (validate + board + replay payments + disbursement)

   Philosophy: Boarding is an orchestration concern. Validation is a pure
   function over data. The actual writes use operations.clj primitives.
   All IDs are generated server-side via (random-uuid) — the user never
   provides or sees them."
  (:require [lms.operations :as ops]
            [lms.contract :as contract]
            [lms.migration :as migration]
            [datomic.client.api :as d]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;; ============================================================
;; Validation
;; ============================================================

(defn validate-boarding-data
  "Validate contract data, fees, and installments before boarding.

   Pure function — no side effects (unless db provided for uniqueness checks).

   Checks:
   1. Required contract fields present
   2. Principal is positive
   3. All installments have required fields
   4. Installment principal-due values sum to contract principal
   5. Installment sequences are contiguous starting from 1
   6. Due dates are chronologically ordered
   7. All fees have required fields and positive amounts
   8. External-id uniqueness (only when db provided)
   9. Facility capacity (only when db provided and facility-linked)

   Args:
   - contract-data: Map with :contract/* keys
   - fees: Sequence of fee maps
   - installments: Sequence of installment maps
   - db: Optional database value for uniqueness/capacity checks

   Returns:
   {:valid? true} or {:valid? false :errors [{:field :x :message \"...\"}]}

   Usage:
     (validate-boarding-data contract-data fees installments)
     (validate-boarding-data contract-data fees installments (d/db conn))"
  [contract-data fees installments & [db]]
  (let [errors (atom [])
        add-error! (fn [field message]
                     (swap! errors conj {:field field :message message}))]

    ;; 1. Required contract fields
    ;; Note: status is derived, not stored
    (doseq [k [:contract/external-id :contract/borrower
               :contract/principal]]
      (when-not (get contract-data k)
        (add-error! k (str "Missing required field: " (name k)))))

    ;; 2. Principal must be positive
    (when-let [p (:contract/principal contract-data)]
      (when-not (pos? p)
        (add-error! :contract/principal "Principal must be positive")))

    ;; 2b. Days-to-first-installment must be positive if provided
    (when-let [d (:contract/days-to-first-installment contract-data)]
      (when-not (and (integer? d) (pos? d))
        (add-error! :contract/days-to-first-installment
                    "Days to first installment must be a positive integer")))

    ;; 3. Installment required fields
    (doseq [[idx inst] (map-indexed vector installments)]
      (doseq [k [:installment/seq :installment/due-date
                 :installment/principal-due :installment/profit-due
                 :installment/remaining-principal]]
        (when-not (get inst k)
          (add-error! k (str "Installment " (inc idx) " missing " (name k))))))

    ;; 4. Installment principals must sum to contract principal
    (when (and (seq installments)
               (:contract/principal contract-data)
               (every? :installment/principal-due installments))
      (let [inst-sum (reduce + 0M (map :installment/principal-due installments))
            principal (:contract/principal contract-data)]
        (when-not (= inst-sum principal)
          (add-error! :installment/principal-due
                      (str "Installment principals sum to " inst-sum
                           " but contract principal is " principal)))))

    ;; 5. Sequences must be contiguous starting from 1
    (when (and (seq installments)
               (every? :installment/seq installments))
      (let [seqs (sort (map :installment/seq installments))
            expected (vec (range 1 (inc (count installments))))]
        (when-not (= (vec seqs) expected)
          (add-error! :installment/seq
                      (str "Sequences must be contiguous from 1. Got: " (vec seqs))))))

    ;; 6. Due dates must be chronologically ordered
    (when (and (seq installments)
               (every? :installment/seq installments)
               (every? :installment/due-date installments))
      (let [sorted-by-seq (sort-by :installment/seq installments)
            dates (map :installment/due-date sorted-by-seq)
            timestamps (map #(.getTime ^java.util.Date %) dates)]
        (when-not (apply <= timestamps)
          (add-error! :installment/due-date
                      "Due dates must be chronologically ordered"))))

    ;; 7. Fee required fields and positive amounts
    (doseq [[idx fee] (map-indexed vector fees)]
      (doseq [k [:fee/type :fee/amount]]
        (when-not (get fee k)
          (add-error! k (str "Fee " (inc idx) " missing " (name k)))))
      (when-not (some? (:fee/days-after-disbursement fee))
        (add-error! :fee/days-after-disbursement (str "Fee " (inc idx) " missing days-after-disbursement")))
      (when (and (:fee/amount fee)
                 (not (pos? (:fee/amount fee))))
        (add-error! :fee/amount (str "Fee " (inc idx) " amount must be positive"))))

    ;; 8. External-id uniqueness (only with db)
    (when (and db (:contract/external-id contract-data))
      (let [ext-id (:contract/external-id contract-data)
            existing (d/q {:query '[:find ?e
                                    :in $ ?ext-id
                                    :where [?e :contract/external-id ?ext-id]]
                           :args [db ext-id]})]
        (when (seq existing)
          (add-error! :contract/external-id
                      (str "External ID '" ext-id "' already exists")))))

    ;; 9. Facility capacity check (only with db and facility ref)
    (when (and db (:contract/facility contract-data))
      (let [facility-ref (:contract/facility contract-data)
            facility-id (when (and (vector? facility-ref)
                                   (= :facility/id (first facility-ref)))
                          (second facility-ref))]
        (when facility-id
          (let [fs (contract/facility-state db facility-id)
                principal (or (:contract/principal contract-data) 0M)]
            (when (and (:available fs) (> principal (:available fs)))
              (add-error! :contract/facility
                          (str "Facility capacity exceeded. Available: " (:available fs)
                               ", requested: " principal)))))))

    ;; Return result
    (let [errs @errors]
      (if (empty? errs)
        {:valid? true}
        {:valid? false :errors errs}))))

;; ============================================================
;; CSV Parsing
;; ============================================================

(defn parse-schedule-csv
  "Parse installment schedule from CSV content.

   CSV format (header row required):
   Seq,Due Date,Principal Due,Profit Due,Remaining Principal

   The Remaining Principal column is optional — if absent, it is computed
   from contract-principal minus cumulative principal-due.

   Generates :installment/id via (random-uuid) for each row.
   Reuses migration date/amount parsers for format flexibility.

   Args:
   - csv-content: String of CSV data
   - contract-principal: BigDecimal principal for remaining-principal derivation

   Returns: Vector of installment maps with :installment/* keys

   Usage:
     (parse-schedule-csv csv-string 1000000M)"
  [csv-content contract-principal]
  (let [rows (csv/read-csv (java.io.StringReader. csv-content))
        header (first rows)
        data-rows (rest rows)
        has-remaining? (>= (count header) 5)]

    (loop [remaining data-rows
           cumulative-principal 0M
           result []]
      (if (empty? remaining)
        result
        (let [row (first remaining)
              seq-num (Long/parseLong (str/trim (nth row 0)))
              due-date (migration/parse-payment-date (str/trim (nth row 1)))
              principal-due (migration/parse-payment-amount (str/trim (nth row 2)))
              profit-due (migration/parse-payment-amount (str/trim (nth row 3)))
              remaining-principal (if (and has-remaining?
                                           (> (count row) 4)
                                           (not (str/blank? (nth row 4))))
                                    (migration/parse-payment-amount (str/trim (nth row 4)))
                                    (- contract-principal cumulative-principal))
              inst {:installment/id (random-uuid)
                    :installment/seq seq-num
                    :installment/due-date due-date
                    :installment/principal-due principal-due
                    :installment/profit-due profit-due
                    :installment/remaining-principal remaining-principal}]
          (recur (rest remaining)
                 (+ cumulative-principal principal-due)
                 (conj result inst)))))))

(defn parse-payment-csv
  "Parse payment history from CSV content.

   Same format as migration/load-payment-data:
   Date,External ID,Payment Summary,Amount,Paid By,Source,Reference

   Args:
   - csv-content: String of CSV data

   Returns: Vector of payment maps sorted by date

   Usage:
     (parse-payment-csv csv-string)"
  [csv-content]
  (let [rows (csv/read-csv (java.io.StringReader. csv-content))
        data-rows (rest rows)]  ;; skip header
    (->> data-rows
         (map (fn [row]
                (let [fields (map str/trim row)
                      [date-str ext-id summary amount-str paid-by source reference] fields]
                  {:date (migration/parse-payment-date date-str)
                   :external-id ext-id
                   :payment-summary summary
                   :amount (migration/parse-payment-amount amount-str)
                   :paid-by paid-by
                   :source (or source "")
                   :reference (or reference "")})))
         (sort-by :date)
         vec)))

;; ============================================================
;; Boarding Operations
;; ============================================================

(defn board-new-contract
  "Board a new loan contract.

   Orchestrates: generate IDs → validate → board-contract.
   Disbursement is a separate step (it hasn't happened yet at boarding time).

   All IDs (contract/id, fee/id, installment/id) are generated server-side.

   Args:
   - conn: Datomic connection
   - contract-data: Map with :contract/* keys (id generated if absent)
   - fees: Sequence of fee maps (id generated if absent)
   - installments: Sequence of installment maps (id generated if absent)
   - user-id: String user performing the operation

   Returns:
   {:success? true :contract-id uuid}
   or
   {:success? false :errors [{:field :x :message \"...\"}]}

   Usage:
     (board-new-contract conn
       {:contract/external-id \"LOAN-2024-001\"
        :contract/borrower [:party/id borrower-party-id]
        :contract/start-date #inst \"2024-01-01\"
        :contract/principal 1000000M}
       [{:fee/type :management :fee/amount 5000M :fee/due-date #inst \"2024-01-01\"}]
       installments
       \"user-1\")"
  [conn contract-data fees installments user-id]
  (let [contract-data (if (:contract/id contract-data)
                        contract-data
                        (assoc contract-data :contract/id (random-uuid)))
        fees (mapv #(if (:fee/id %) % (assoc % :fee/id (random-uuid))) fees)
        installments (mapv #(if (:installment/id %) % (assoc % :installment/id (random-uuid))) installments)

        db (d/db conn)
        validation (validate-boarding-data contract-data fees installments db)]

    (if-not (:valid? validation)
      {:success? false :errors (:errors validation)}

      (try
        (ops/board-contract conn contract-data fees installments user-id)
        (log/info "Contract boarded" {:contract-id (:contract/id contract-data)
                                      :external-id (:contract/external-id contract-data)})
        {:success? true :contract-id (:contract/id contract-data)}
        (catch Exception e
          (log/error e "Boarding failed" {:external-id (:contract/external-id contract-data)})
          {:success? false
           :errors [{:field :system
                     :message (str "Boarding failed: " (.getMessage e))}]})))))

(defn board-existing-contract
  "Board an existing loan being migrated into the system.

   Orchestrates: generate IDs → validate → board-contract → record
   historical disbursement → replay payments chronologically.

   Reuses migration/map-tx-type for payment classification.

   Args:
   - conn: Datomic connection
   - contract-data: Map with :contract/* keys
   - fees: Sequence of fee maps
   - installments: Sequence of installment maps
   - payments: Sequence of payment maps (from parse-payment-csv)
   - disbursement: Map with {:amount :date :reference :iban :bank} or nil
   - user-id: String user performing the operation

   Returns:
   {:success? true :contract-id uuid :payments-processed n :payments-skipped n}
   or
   {:success? false :errors [{:field :x :message \"...\"}]}

   Usage:
     (board-existing-contract conn contract-data fees installments
                              payments
                              {:amount 1000000M :date #inst \"2024-01-02\"
                               :reference \"WIRE-001\"}
                              \"user-1\")"
  [conn contract-data fees installments payments disbursement user-id]
  (let [contract-data (if (:contract/id contract-data)
                        contract-data
                        (assoc contract-data :contract/id (random-uuid)))
        fees (mapv #(if (:fee/id %) % (assoc % :fee/id (random-uuid))) fees)
        installments (mapv #(if (:installment/id %) % (assoc % :installment/id (random-uuid))) installments)

        db (d/db conn)
        validation (validate-boarding-data contract-data fees installments db)]

    (if-not (:valid? validation)
      {:success? false :errors (:errors validation)}

      (try
        (let [contract-id (:contract/id contract-data)]

          ;; 1. Board contract (atomic: contract + schedule + fees)
          (ops/board-contract conn contract-data fees installments user-id)
          (log/info "Contract boarded (existing)" {:contract-id contract-id
                                                   :external-id (:contract/external-id contract-data)})

          ;; 2. Record historical disbursement (if provided)
          (when disbursement
            ;; Funding inflow — principal enters waterfall
            (ops/record-funding-inflow conn contract-id
                                       (:amount disbursement)
                                       (:date disbursement) user-id)
            ;; Borrower disbursement with outflow component
            (ops/record-disbursement conn contract-id
                                     (:amount disbursement)
                                     (:date disbursement)
                                     (or (:reference disbursement) "BOARDING-HISTORICAL")
                                     user-id
                                     :iban (:iban disbursement)
                                     :bank (:bank disbursement))
            ;; Set disbursed-at + shift dates (separate step)
            (ops/set-disbursed-at conn contract-id (:date disbursement) user-id)
            (log/info "Historical disbursement recorded" {:contract-id contract-id
                                                          :amount (:amount disbursement)}))

          ;; 3. Replay payments chronologically
          (let [sorted-payments (sort-by :date payments)
                result (reduce
                        (fn [{:keys [processed skipped]} payment]
                          (let [mapped (migration/map-tx-type payment)]
                            (if (:skip? mapped)
                              {:processed processed :skipped (inc skipped)}
                              (do
                                (case (:tx-type mapped)
                                  :payment
                                  (ops/record-payment conn contract-id
                                                      (:amount mapped)
                                                      (:date payment)
                                                      (or (:reference payment)
                                                          (str "MIGRATED-" processed))
                                                      user-id)

                                  :disbursement
                                  (ops/record-disbursement conn contract-id
                                                           (:amount mapped)
                                                           (:date payment)
                                                           (or (:reference payment)
                                                               (str "DISB-MIGRATED-" processed))
                                                           user-id)

                                  :deposit-refund
                                  (ops/refund-deposit conn contract-id
                                                      (:amount mapped)
                                                      (:date payment)
                                                      "Migrated deposit refund"
                                                      user-id)

                                   ;; Unknown type — skip
                                  nil)
                                {:processed (inc processed) :skipped skipped}))))
                        {:processed 0 :skipped 0}
                        sorted-payments)]

            (log/info "Payment replay complete" {:contract-id contract-id
                                                 :processed (:processed result)
                                                 :skipped (:skipped result)})

            {:success? true
             :contract-id contract-id
             :payments-processed (:processed result)
             :payments-skipped (:skipped result)}))

        (catch Exception e
          (log/error e "Existing contract boarding failed"
                     {:external-id (:contract/external-id contract-data)})
          {:success? false
           :errors [{:field :system
                     :message (str "Boarding failed: " (.getMessage e))}]})))))

;; ============================================================
;; Origination (funding-day operations)
;; ============================================================
;;
;; Origination is NOT a single orchestrated function. Each step is a
;; separate business fact that the user triggers independently:
;;
;; 1. ops/record-funding-inflow   — principal enters the waterfall
;; 2. ops/record-disbursement     — money wired to borrower (with outflow)
;; 3. ops/receive-deposit         — deposit from funding (deposit ledger)
;; 4. ops/record-settlement       — refi: outflow on new + inflow on old
;; 5. ops/record-refund           — excess returned (disbursement with outflow)
;; 6. ops/set-disbursed-at        — marks contract active, shifts dates
;;
;; Fee settlement, deposit funding, and installment prepayment are NOT
;; explicit origination steps — the waterfall derives these allocations
;; from: available = sum(inflows) - sum(outflows).
;;
;; The handler/UI presents these steps and lets the user execute each one.
;; Each step is a separate Datomic transaction — a recorded fact.

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  (require '[lms.db :as db])
  (require '[lms.boarding :as boarding])
  (require '[datomic.client.api :as d])

  ;; Setup
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Validate boarding data (pure — no DB needed)
  ;; Note: status is derived, not passed
  (boarding/validate-boarding-data
   {:contract/external-id "TEST-001"
    :contract/borrower [:party/id #uuid "00000000-0000-0000-0000-000000000001"]
    :contract/start-date #inst "2024-01-01"
    :contract/principal 200000M}
   [{:fee/type :management :fee/amount 1000M :fee/due-date #inst "2024-01-01"}]
   [{:installment/seq 1
     :installment/due-date #inst "2024-01-31"
     :installment/principal-due 100000M
     :installment/profit-due 5000M
     :installment/remaining-principal 200000M}
    {:installment/seq 2
     :installment/due-date #inst "2024-02-28"
     :installment/principal-due 100000M
     :installment/profit-due 5000M
     :installment/remaining-principal 100000M}])
  ;; => {:valid? true}

  ;; Parse schedule CSV
  (boarding/parse-schedule-csv
   "Seq,Due Date,Principal Due,Profit Due,Remaining Principal\n1,2024-01-31,100000,5000,200000\n2,2024-02-28,100000,5000,100000"
   200000M)

  ;; Board new contract (status derived as :pending until disbursement)
  ;; Prerequisite: create party first via lms.party/create-party
  (boarding/board-new-contract conn
                               {:contract/external-id "BOARD-001"
                                :contract/borrower [:party/id borrower-party-id]
                                :contract/start-date #inst "2024-01-01"
                                :contract/principal 200000M}
                               [{:fee/type :management :fee/amount 1000M :fee/due-date #inst "2024-01-01"}]
                               [{:installment/seq 1
                                 :installment/due-date #inst "2024-01-31"
                                 :installment/principal-due 100000M
                                 :installment/profit-due 5000M
                                 :installment/remaining-principal 200000M}
                                {:installment/seq 2
                                 :installment/due-date #inst "2024-02-28"
                                 :installment/principal-due 100000M
                                 :installment/profit-due 5000M
                                 :installment/remaining-principal 100000M}]
                               "test-user")
  ;; => {:success? true :contract-id #uuid "..."}
  )
