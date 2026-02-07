(ns lms.migration
  "Data migration from Jira + payment table to Datomic.

   This namespace handles the one-time migration of existing contracts from:
   - Jira issues (contract metadata + schedule)
   - Payment table CSV (transaction history)

   Migration Philosophy (from PRD section 7):
   - Replay history: Create contract, then replay all transactions chronologically
   - Use :tx/original-date to preserve actual event dates
   - Use :tx/migrated-from for traceability
   - Verify: computed state must match expected outstanding balance

   Process:
   1. Load Jira data → extract contract + fees + installments
   2. Load payment CSV → extract transactions
   3. Map payment types to transaction types (PRD 7.2 table)
   4. Transact in order: boarding → disbursement → payments → deposits
   5. Compute final state → compare to expected balance
   6. Report discrepancies"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [datomic.client.api :as d]
            [lms.contract :as contract]
            [lms.operations :as ops]))

;; ============================================================
;; Transaction Type Mapping (PRD Section 7.2)
;; ============================================================

(defn map-tx-type
  "Map payment table fields to Datomic transaction type.

   Mapping rules from PRD section 7.2:

   | Payment Summary | Paid By      | Source        | → Transaction Type |
   |----------------|--------------|---------------|-------------------|
   | Management Fee | Customer     | -             | :payment          |
   | Transfer       | Customer     | -             | :payment          |
   | Funding        | AlRaedah     | Disbursement  | :disbursement     |
   | Transfer       | AlRaedah     | Disbursement  | :deposit-refund   |
   | Principle      | AlRaedah     | -             | SKIP              |

   Args:
   - payment-row: Map with keys :payment-summary :paid-by :source :amount

   Returns:
   - {:tx-type :payment/:disbursement/:deposit-refund
      :amount 1000000M
      :skip? false}
   - {:skip? true} if should be skipped

   Example:
   (map-tx-type {:payment-summary \"Transfer\"
                 :paid-by \"Customer\"
                 :source \"\"
                 :amount 1000000M})
   ;; => {:tx-type :payment :amount 1000000M :skip? false}"
  [{:keys [payment-summary paid-by source amount]}]
  (let [summary (str/lower-case (str/trim payment-summary))
        payer (str/lower-case (str/trim paid-by))
        src (str/lower-case (str/trim source))]

    (cond
      ;; Skip internal bookings
      (str/includes? summary "principle")
      {:skip? true}

      ;; Customer payments
      (= payer "customer")
      {:tx-type :payment
       :amount amount
       :skip? false}

      ;; AlRaedah transactions
      (= payer "alraedah")
      (cond
        ;; Disbursement
        (and (str/includes? summary "funding")
             (str/includes? src "disbursement"))
        {:tx-type :disbursement
         :amount amount
         :skip? false}

        ;; Deposit refund
        (and (str/includes? summary "transfer")
             (str/includes? src "disbursement"))
        {:tx-type :deposit-refund
         :amount amount
         :skip? false}

        ;; Other AlRaedah transactions (skip)
        :else
        {:skip? true})

      ;; Unknown - skip
      :else
      {:skip? true})))

;; ============================================================
;; Jira Data Loading
;; ============================================================

(defn parse-jira-date
  "Parse Jira date string to java.util.Date.

   Jira exports dates in ISO format: \"2024-01-15\"

   Args:
   - date-str: String like \"2024-01-15\"

   Returns: java.util.Date"
  [date-str]
  (when date-str
    (java.util.Date/from
     (java.time.Instant/parse (str date-str "T00:00:00Z")))))

(defn load-jira-data
  "Load contract data from Jira export file.

   Jira exports are EDN files with structure:
   {:contract {:external-id \"LOAN-2024-001\"
               :customer-name \"Customer Co.\"
               :customer-id \"CR-123456\"
               :start-date \"2024-01-01\"
               :maturity-date \"2024-12-31\"
               :principal 1000000M
               :security-deposit 50000M
               :step-up-terms \"[...]\"}
    :fees [{:type :management
            :amount 5000M
            :due-date \"2024-01-01\"}]
    :installments [{:seq 1
                    :due-date \"2024-01-31\"
                    :principal-due 83333M
                    :profit-due 12500M}
                   ...]}

   Args:
   - file-path: Path to Jira export EDN file

   Returns: Map with :contract :fees :installments

   Example:
   (load-jira-data \"contracts/LOAN-2024-001.edn\")"
  [file-path]
  (let [data (edn/read-string (slurp file-path))

        ;; Parse contract
        contract-raw (:contract data)
        ;; Note: status is derived, maturity-date is derived from installments
        contract {:contract/id (random-uuid)
                  :contract/external-id (:external-id contract-raw)
                  :contract/customer-name (:customer-name contract-raw)
                  :contract/customer-id (:customer-id contract-raw)
                  :contract/start-date (parse-jira-date (:start-date contract-raw))
                  :contract/principal (:principal contract-raw)
                  :contract/security-deposit (:security-deposit contract-raw)
                  :contract/step-up-terms (:step-up-terms contract-raw)}

        ;; Parse fees
        fees (for [fee-raw (:fees data)]
               {:fee/id (random-uuid)
                :fee/type (:type fee-raw)
                :fee/amount (:amount fee-raw)
                :fee/due-date (parse-jira-date (:due-date fee-raw))})

        ;; Parse installments
        installments (for [inst-raw (:installments data)]
                       {:installment/id (random-uuid)
                        :installment/seq (:seq inst-raw)
                        :installment/due-date (parse-jira-date (:due-date inst-raw))
                        :installment/principal-due (:principal-due inst-raw)
                        :installment/profit-due (:profit-due inst-raw)})]

    {:contract contract
     :fees fees
     :installments installments}))

;; ============================================================
;; Payment Table Loading
;; ============================================================

(defn parse-payment-date
  "Parse payment table date string to java.util.Date.

   Payment table dates are in format: \"2024-01-15\" or \"15/01/2024\"

   Args:
   - date-str: Date string

   Returns: java.util.Date"
  [date-str]
  (when date-str
    (try
      ;; Try ISO format first
      (java.util.Date/from
       (java.time.Instant/parse (str date-str "T00:00:00Z")))
      (catch Exception _
        ;; Try DD/MM/YYYY format
        (let [[day month year] (str/split date-str #"/")]
          (java.util.Date/from
           (java.time.Instant/parse
            (format "%s-%s-%sT00:00:00Z" year month day))))))))

(defn parse-payment-amount
  "Parse payment amount from string to BigDecimal.

   Handles formats like:
   - \"1000000\"
   - \"1,000,000\"
   - \"1000000.00\"

   Args:
   - amount-str: Amount string

   Returns: BigDecimal"
  [amount-str]
  (when amount-str
    (bigdec (str/replace amount-str "," ""))))

(defn load-payment-data
  "Load payment history from CSV file.

   CSV format (from PRD section 7):
   Date,External ID,Payment Summary,Amount,Paid By,Source,Reference

   Example row:
   2024-01-15,LOAN-2024-001,Transfer,1000000,Customer,,FT-ANB-12345

   Args:
   - file-path: Path to payment CSV file
   - contract-external-id: Filter to this contract only

   Returns: Vector of payment maps sorted by date:
   [{:date #inst \"2024-01-15\"
     :external-id \"LOAN-2024-001\"
     :payment-summary \"Transfer\"
     :amount 1000000M
     :paid-by \"Customer\"
     :source \"\"
     :reference \"FT-ANB-12345\"}]"
  [file-path contract-external-id]
  (with-open [reader (io/reader file-path)]
    (let [rows (csv/read-csv reader)
          header (first rows)
          data-rows (rest rows)

          ;; Parse rows
          payments
          (for [row data-rows
                :let [[date ext-id summary amount paid-by source reference] row]
                :when (= ext-id contract-external-id)]
            {:date (parse-payment-date date)
             :external-id ext-id
             :payment-summary summary
             :amount (parse-payment-amount amount)
             :paid-by paid-by
             :source source
             :reference reference})]

      ;; Sort by date
      (vec (sort-by :date payments)))))

;; ============================================================
;; Contract Migration
;; ============================================================

(defn migrate-contract
  "Migrate a single contract from Jira + payment data to Datomic.

   Process:
   1. Board contract (create contract + schedule + :boarding tx)
   2. For each payment (chronologically):
      - Map to transaction type
      - Transact with :tx/original-date and :tx/migrated-from

   Args:
   - conn: Datomic connection
   - jira-data: Map from load-jira-data
   - payments: Vector from load-payment-data

   Returns:
   - contract-id (UUID)

   Side effects:
   - Transacts contract and all transactions to Datomic

   Example:
   (migrate-contract conn
                     (load-jira-data \"LOAN-2024-001.edn\")
                     (load-payment-data \"payments.csv\" \"LOAN-2024-001\"))"
  [conn jira-data payments]
  (let [contract-id (get-in jira-data [:contract :contract/id])
        external-id (get-in jira-data [:contract :contract/external-id])]

    (println (format "Migrating contract: %s" external-id))

    ;; 1. Board contract
    (println "  - Boarding contract...")
    (ops/board-contract conn
                        (:contract jira-data)
                        (:fees jira-data)
                        (:installments jira-data)
                        "migration-script")

    ;; 2. Process payments
    (println (format "  - Processing %d transactions..." (count payments)))
    (doseq [[idx payment] (map-indexed vector payments)]
      (let [mapped (map-tx-type payment)]

        (when-not (:skip? mapped)
          (let [tx-type (:tx-type mapped)
                amount (:amount mapped)
                reference (:reference payment)
                original-date (:date payment)

                ;; Build transaction data
                tx-data [{:db/id "datomic.tx"
                          :tx/type tx-type
                          :tx/contract [:contract/id contract-id]
                          :tx/amount amount
                          :tx/reference reference
                          :tx/author "migration-script"
                          :tx/original-date original-date
                          :tx/migrated-from (format "%s-payment-%d" external-id idx)}]]

            (d/transact conn {:tx-data tx-data})

            (when (zero? (mod (inc idx) 10))
              (println (format "    Processed %d/%d transactions"
                              (inc idx) (count payments))))))))

    (println (format "  ✓ Migration complete for %s" external-id))
    contract-id))

;; ============================================================
;; Migration Verification
;; ============================================================

(defn verify-migration
  "Verify migrated contract by comparing computed state to expected balance.

   Args:
   - db: Database value
   - contract-id: UUID
   - expected-outstanding: Expected outstanding balance (from source system)

   Returns:
   - {:match? true/false
      :computed-outstanding 0M
      :expected-outstanding 0M
      :discrepancy 0M
      :contract-id contract-id
      :external-id \"LOAN-2024-001\"}

   Example:
   (verify-migration (d/db conn) contract-id 850000M)
   ;; => {:match? true :computed-outstanding 850000M
   ;;     :expected-outstanding 850000M :discrepancy 0M}"
  [db contract-id expected-outstanding]
  (let [contract (contract/get-contract db contract-id)
        external-id (:contract/external-id contract)
        state (contract/contract-state db contract-id (java.util.Date.))
        computed-outstanding (:total-outstanding state)
        discrepancy (- computed-outstanding expected-outstanding)
        match? (= computed-outstanding expected-outstanding)]

    {:match? match?
     :computed-outstanding computed-outstanding
     :expected-outstanding expected-outstanding
     :discrepancy discrepancy
     :contract-id contract-id
     :external-id external-id}))

(defn migrate-all-contracts
  "Migrate all contracts from a directory of Jira files + payment CSV.

   Args:
   - conn: Datomic connection
   - jira-dir: Directory containing *.edn files (one per contract)
   - payments-csv: Path to payments CSV file

   Returns:
   - Vector of verification results

   Example:
   (migrate-all-contracts conn \"migration/contracts/\" \"migration/payments.csv\")"
  [conn jira-dir payments-csv]
  (let [jira-files (file-seq (io/file jira-dir))
        edn-files (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn"))
                         jira-files)]

    (println (format "Found %d contracts to migrate" (count edn-files)))

    (for [file edn-files]
      (let [jira-data (load-jira-data (.getPath file))
            external-id (get-in jira-data [:contract :contract/external-id])
            payments (load-payment-data payments-csv external-id)

            ;; Migrate
            contract-id (migrate-contract conn jira-data payments)

            ;; Verify (TODO: need expected balance from source system)
            ;; For now, just return computed state
            db (d/db conn)
            state (contract/contract-state db contract-id (java.util.Date.))]

        {:contract-id contract-id
         :external-id external-id
         :computed-outstanding (:total-outstanding state)
         :transaction-count (count (:transactions state))}))))

;; ============================================================
;; Discrepancy Reporting
;; ============================================================

(defn generate-discrepancy-report
  "Generate human-readable report of migration discrepancies.

   Args:
   - verification-results: Vector of verify-migration results

   Returns: String report

   Example:
   (generate-discrepancy-report results)"
  [verification-results]
  (let [total (count verification-results)
        matches (count (filter :match? verification-results))
        mismatches (remove :match? verification-results)
        total-discrepancy (reduce + 0M (map :discrepancy mismatches))]

    (str/join "\n"
              ["=========================================="
               "Migration Verification Report"
               "=========================================="
               ""
               (format "Total contracts: %d" total)
               (format "Matches: %d (%.1f%%)" matches (* 100.0 (/ matches total)))
               (format "Discrepancies: %d" (count mismatches))
               (format "Total discrepancy: %.2f SAR" (double total-discrepancy))
               ""
               "Discrepancies:"
               ""
               (str/join "\n"
                        (for [result mismatches]
                          (format "  %s: Expected %.2f, Got %.2f, Diff %.2f"
                                 (:external-id result)
                                 (double (:expected-outstanding result))
                                 (double (:computed-outstanding result))
                                 (double (:discrepancy result)))))])))

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  (require '[lms.db :as db])
  (require '[lms.migration :as mig])
  (require '[datomic.client.api :as d])

  ;; Setup
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Load sample data
  (def jira-data (mig/load-jira-data "migration/LOAN-2024-001.edn"))
  (def payments (mig/load-payment-data "migration/payments.csv" "LOAN-2024-001"))

  ;; Inspect data
  (count (:installments jira-data))
  ;; => 12

  (count payments)
  ;; => 25

  ;; Migrate one contract
  (def contract-id (mig/migrate-contract conn jira-data payments))

  ;; Check result
  (def state (contract/contract-state (d/db conn) contract-id (java.util.Date.)))
  (:total-outstanding state)
  ;; => 850000M

  ;; Verify migration
  (def verification (mig/verify-migration (d/db conn) contract-id 850000M))
  verification
  ;; => {:match? true :computed-outstanding 850000M
  ;;     :expected-outstanding 850000M :discrepancy 0M}

  ;; Migrate all contracts
  (def results (mig/migrate-all-contracts conn
                                          "migration/contracts/"
                                          "migration/payments.csv"))

  ;; Generate report
  (println (mig/generate-discrepancy-report results))

  ;; Example: Test transaction type mapping
  (mig/map-tx-type {:payment-summary "Transfer"
                    :paid-by "Customer"
                    :source ""
                    :amount 1000000M})
  ;; => {:tx-type :payment :amount 1000000M :skip? false}

  (mig/map-tx-type {:payment-summary "Funding"
                    :paid-by "AlRaedah"
                    :source "Disbursement"
                    :amount 1000000M})
  ;; => {:tx-type :disbursement :amount 1000000M :skip? false}

  (mig/map-tx-type {:payment-summary "Principle"
                    :paid-by "AlRaedah"
                    :source ""
                    :amount 100000M})
  ;; => {:skip? true}

  )
