(ns lms.boarding-test
  "Tests for contract boarding: validation, CSV parsing, and integration.

   Test categories:
   1. Pure validation — no DB, no side effects
   2. DB-dependent validation — uniqueness and capacity checks
   3. CSV parsing — schedule and payment parsing
   4. Integration — board-new-contract and board-existing-contract end-to-end"
  (:require [clojure.test :refer [deftest is testing]]
            [lms.boarding :as sut]
            [lms.contract :as contract]
            [lms.operations :as ops]
            [lms.db :as db]
            [datomic.client.api :as d]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(defn get-test-conn
  "Get a fresh database connection for testing.
   Each call creates a unique database to ensure test isolation."
  []
  (let [client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system "lms"})
        test-db-name (str "test-" (subs (str (random-uuid)) 0 8))]
    (d/create-database client {:db-name test-db-name})
    (let [conn (d/connect client {:db-name test-db-name})]
      (db/install-schema conn)
      conn)))

(def valid-contract-data
  "Minimal valid contract data for tests."
  {:contract/external-id "TEST-001"
   :contract/customer-name "Test Co."
   :contract/customer-id "CR-123"
   :contract/status :active
   :contract/start-date #inst "2024-01-01"
   :contract/principal 200000M})

(def valid-installments
  "Two installments summing to 200,000M principal."
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

(def valid-fees
  "Single management fee."
  [{:fee/type :management
    :fee/amount 1000M
    :fee/due-date #inst "2024-01-01"}])

;; ============================================================
;; Pure Validation Tests (no DB)
;; ============================================================

(deftest validate-missing-required-fields-test
  (testing "Missing required contract fields return errors"
    (let [result (sut/validate-boarding-data {} [] [])]
      (is (false? (:valid? result)))
      (is (>= (count (:errors result)) 6))  ;; 6 required fields
      (let [fields (set (map :field (:errors result)))]
        (is (contains? fields :contract/external-id))
        (is (contains? fields :contract/customer-name))
        (is (contains? fields :contract/customer-id))
        (is (contains? fields :contract/status))
        (is (contains? fields :contract/start-date))
        (is (contains? fields :contract/principal))))))

(deftest validate-negative-principal-test
  (testing "Non-positive principal returns error"
    (let [result (sut/validate-boarding-data
                  (assoc valid-contract-data :contract/principal -100M)
                  valid-fees
                  valid-installments)]
      (is (false? (:valid? result)))
      (is (some #(= :contract/principal (:field %)) (:errors result))))))

(deftest validate-zero-principal-test
  (testing "Zero principal returns error"
    (let [result (sut/validate-boarding-data
                  (assoc valid-contract-data :contract/principal 0M)
                  valid-fees
                  valid-installments)]
      (is (false? (:valid? result)))
      (is (some #(= :contract/principal (:field %)) (:errors result))))))

(deftest validate-principal-sum-mismatch-test
  (testing "Installment principals not summing to contract principal"
    (let [bad-installments [{:installment/seq 1
                             :installment/due-date #inst "2024-01-31"
                             :installment/principal-due 80000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 200000M}
                            {:installment/seq 2
                             :installment/due-date #inst "2024-02-28"
                             :installment/principal-due 80000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 120000M}]
          result (sut/validate-boarding-data valid-contract-data valid-fees bad-installments)]
      (is (false? (:valid? result)))
      (is (some #(and (= :installment/principal-due (:field %))
                      (.contains (:message %) "160000"))
                (:errors result))))))

(deftest validate-non-contiguous-sequences-test
  (testing "Non-contiguous installment sequences return error"
    (let [bad-installments [{:installment/seq 1
                             :installment/due-date #inst "2024-01-31"
                             :installment/principal-due 100000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 200000M}
                            {:installment/seq 3  ;; gap: 1, 3 instead of 1, 2
                             :installment/due-date #inst "2024-02-28"
                             :installment/principal-due 100000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 100000M}]
          result (sut/validate-boarding-data valid-contract-data valid-fees bad-installments)]
      (is (false? (:valid? result)))
      (is (some #(= :installment/seq (:field %)) (:errors result))))))

(deftest validate-non-chronological-dates-test
  (testing "Non-chronological due dates return error"
    (let [bad-installments [{:installment/seq 1
                             :installment/due-date #inst "2024-02-28"  ;; later date first
                             :installment/principal-due 100000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 200000M}
                            {:installment/seq 2
                             :installment/due-date #inst "2024-01-31"  ;; earlier date second
                             :installment/principal-due 100000M
                             :installment/profit-due 5000M
                             :installment/remaining-principal 100000M}]
          result (sut/validate-boarding-data valid-contract-data valid-fees bad-installments)]
      (is (false? (:valid? result)))
      (is (some #(= :installment/due-date (:field %)) (:errors result))))))

(deftest validate-missing-installment-fields-test
  (testing "Installments missing required fields return errors"
    (let [bad-installments [{:installment/seq 1}  ;; missing all other fields
                            {:installment/seq 2}]
          result (sut/validate-boarding-data valid-contract-data valid-fees bad-installments)]
      (is (false? (:valid? result)))
      ;; Each installment missing 4 fields (due-date, principal-due, profit-due, remaining-principal)
      (let [inst-errors (filter #(#{:installment/due-date :installment/principal-due
                                    :installment/profit-due :installment/remaining-principal}
                                  (:field %))
                                (:errors result))]
        (is (= 8 (count inst-errors)))))))

(deftest validate-fee-missing-fields-test
  (testing "Fees missing required fields return errors"
    (let [bad-fees [{:fee/type :management}]  ;; missing amount and due-date
          result (sut/validate-boarding-data valid-contract-data bad-fees valid-installments)]
      (is (false? (:valid? result)))
      (is (some #(= :fee/amount (:field %)) (:errors result)))
      (is (some #(= :fee/due-date (:field %)) (:errors result))))))

(deftest validate-fee-non-positive-amount-test
  (testing "Fee with non-positive amount returns error"
    (let [bad-fees [{:fee/type :management
                     :fee/amount -500M
                     :fee/due-date #inst "2024-01-01"}]
          result (sut/validate-boarding-data valid-contract-data bad-fees valid-installments)]
      (is (false? (:valid? result)))
      (is (some #(and (= :fee/amount (:field %))
                      (.contains (:message %) "positive"))
                (:errors result))))))

(deftest validate-valid-data-passes-test
  (testing "Valid contract data passes validation"
    (let [result (sut/validate-boarding-data valid-contract-data valid-fees valid-installments)]
      (is (true? (:valid? result))))))

(deftest validate-no-fees-passes-test
  (testing "Contract with no fees passes validation"
    (let [result (sut/validate-boarding-data valid-contract-data [] valid-installments)]
      (is (true? (:valid? result))))))

;; ============================================================
;; DB-Dependent Validation Tests
;; ============================================================

(deftest validate-duplicate-external-id-test
  (testing "Duplicate external-id is rejected when DB provided"
    (let [conn (get-test-conn)
          contract-id (random-uuid)]

      ;; Board a contract with external-id "TEST-001"
      (ops/board-contract conn
                          (assoc valid-contract-data :contract/id contract-id)
                          (mapv #(assoc % :fee/id (random-uuid)) valid-fees)
                          (mapv #(assoc % :installment/id (random-uuid)) valid-installments)
                          "test-user")

      ;; Try to validate another contract with the same external-id
      (let [db (d/db conn)
            result (sut/validate-boarding-data valid-contract-data valid-fees valid-installments db)]
        (is (false? (:valid? result)))
        (is (some #(and (= :contract/external-id (:field %))
                        (.contains (:message %) "already exists"))
                  (:errors result)))))))

;; ============================================================
;; CSV Parsing Tests
;; ============================================================

(deftest parse-schedule-csv-with-remaining-principal-test
  (testing "Parse schedule CSV with all 5 columns"
    (let [csv "Seq,Due Date,Principal Due,Profit Due,Remaining Principal
1,2024-01-31,100000,5000,200000
2,2024-02-28,100000,5000,100000"
          result (sut/parse-schedule-csv csv 200000M)]

      (is (= 2 (count result)))

      ;; First installment
      (let [inst (first result)]
        (is (= 1 (:installment/seq inst)))
        (is (= 100000M (:installment/principal-due inst)))
        (is (= 5000M (:installment/profit-due inst)))
        (is (= 200000M (:installment/remaining-principal inst)))
        (is (some? (:installment/id inst)))
        (is (some? (:installment/due-date inst))))

      ;; Second installment
      (let [inst (second result)]
        (is (= 2 (:installment/seq inst)))
        (is (= 100000M (:installment/principal-due inst)))
        (is (= 100000M (:installment/remaining-principal inst)))))))

(deftest parse-schedule-csv-without-remaining-principal-test
  (testing "Parse schedule CSV without remaining-principal column (computed)"
    (let [csv "Seq,Due Date,Principal Due,Profit Due
1,2024-01-31,100000,5000
2,2024-02-28,100000,5000"
          result (sut/parse-schedule-csv csv 200000M)]

      (is (= 2 (count result)))

      ;; Remaining principal computed: 200000 - 0 = 200000 (before first payment)
      (is (= 200000M (:installment/remaining-principal (first result))))
      ;; After first installment: 200000 - 100000 = 100000
      (is (= 100000M (:installment/remaining-principal (second result)))))))

(deftest parse-schedule-csv-comma-amounts-test
  (testing "Parse schedule CSV with comma-formatted amounts"
    (let [csv "Seq,Due Date,Principal Due,Profit Due,Remaining Principal
1,2024-01-31,\"1,000,000\",\"50,000\",\"10,000,000\""
          result (sut/parse-schedule-csv csv 10000000M)]

      (is (= 1 (count result)))
      (is (= 1000000M (:installment/principal-due (first result))))
      (is (= 50000M (:installment/profit-due (first result))))
      (is (= 10000000M (:installment/remaining-principal (first result)))))))

(deftest parse-payment-csv-test
  (testing "Parse payment history CSV"
    (let [csv "Date,External ID,Payment Summary,Amount,Paid By,Source,Reference
2024-01-15,LOAN-001,Transfer,50000,Customer,,FT-001
2024-02-15,LOAN-001,Transfer,60000,Customer,,FT-002
2024-01-02,LOAN-001,Funding,1000000,AlRaedah,Disbursement,WT-001"
          result (sut/parse-payment-csv csv)]

      (is (= 3 (count result)))

      ;; Should be sorted by date (2024-01-02 first)
      (is (= "Funding" (:payment-summary (first result))))
      (is (= 1000000M (:amount (first result))))

      ;; Second is 2024-01-15
      (is (= 50000M (:amount (second result))))
      (is (= "FT-001" (:reference (second result))))

      ;; Third is 2024-02-15
      (is (= 60000M (:amount (nth result 2)))))))

(deftest parse-payment-csv-empty-test
  (testing "Parse payment CSV with only header returns empty vector"
    (let [csv "Date,External ID,Payment Summary,Amount,Paid By,Source,Reference"
          result (sut/parse-payment-csv csv)]
      (is (= [] result)))))

;; ============================================================
;; Integration: board-new-contract
;; ============================================================

(deftest board-new-contract-success-test
  (testing "Board new contract creates contract, schedule, and fees"
    (let [conn (get-test-conn)
          result (sut/board-new-contract conn
                                         valid-contract-data
                                         valid-fees
                                         valid-installments
                                         "test-user")]

      ;; Should succeed
      (is (true? (:success? result)))
      (is (some? (:contract-id result)))

      ;; Verify contract exists in DB
      (let [db (d/db conn)
            contract-id (:contract-id result)
            c (contract/get-contract db contract-id)]
        (is (not (nil? c)))
        (is (= "TEST-001" (:contract/external-id c)))
        (is (= 200000M (:contract/principal c))))

      ;; Verify installments
      (let [db (d/db conn)
            installments (contract/get-installments db (:contract-id result))]
        (is (= 2 (count installments)))
        (is (= [1 2] (mapv :installment/seq installments))))

      ;; Verify fees
      (let [db (d/db conn)
            fees (contract/get-fees db (:contract-id result))]
        (is (= 1 (count fees)))
        (is (= :management (:fee/type (first fees)))))

      ;; Verify boarding transaction
      (let [db (d/db conn)
            txs (contract/get-events db (:contract-id result))]
        (is (= 1 (count txs)))
        (is (= :boarding (:type (first txs))))))))

(deftest board-new-contract-generates-ids-test
  (testing "Board new contract generates IDs when not provided"
    (let [conn (get-test-conn)
          result (sut/board-new-contract conn
                                         valid-contract-data  ;; no :contract/id
                                         valid-fees            ;; no :fee/id
                                         valid-installments    ;; no :installment/id
                                         "test-user")]
      (is (true? (:success? result)))
      (is (uuid? (:contract-id result))))))

(deftest board-new-contract-validation-failure-test
  (testing "Board new contract with invalid data returns errors without writing"
    (let [conn (get-test-conn)
          result (sut/board-new-contract conn
                                         {}  ;; missing everything
                                         valid-fees
                                         valid-installments
                                         "test-user")]
      ;; Should fail
      (is (false? (:success? result)))
      (is (seq (:errors result)))

      ;; Verify nothing was written to DB
      (let [db (d/db conn)
            contracts (contract/list-contracts db nil)]
        (is (= 0 (count contracts)))))))

(deftest board-new-contract-principal-mismatch-test
  (testing "Board new contract fails when installment principals don't sum to contract principal"
    (let [conn (get-test-conn)
          bad-contract (assoc valid-contract-data :contract/principal 500000M)
          result (sut/board-new-contract conn bad-contract valid-fees valid-installments "test-user")]
      (is (false? (:success? result)))
      (is (some #(.contains (:message %) "sum") (:errors result))))))

(deftest board-new-contract-duplicate-external-id-test
  (testing "Board new contract fails on duplicate external-id"
    (let [conn (get-test-conn)]
      ;; Board first contract
      (sut/board-new-contract conn valid-contract-data valid-fees valid-installments "test-user")

      ;; Attempt to board second with same external-id
      (let [result (sut/board-new-contract conn valid-contract-data valid-fees valid-installments "test-user")]
        (is (false? (:success? result)))
        (is (some #(.contains (:message %) "already exists") (:errors result)))))))

;; ============================================================
;; Integration: board-existing-contract
;; ============================================================

(deftest board-existing-contract-success-test
  (testing "Board existing contract with disbursement and payments"
    (let [conn (get-test-conn)
          ;; Use a unique external-id
          contract-data (assoc valid-contract-data :contract/external-id "EXISTING-001")
          disbursement {:amount 200000M
                        :date #inst "2024-01-02"
                        :reference "WT-HIST-001"
                        :iban "SA1234567890"
                        :bank "ANB"}
          ;; Two customer payments
          payments [{:date #inst "2024-01-15"
                     :external-id "EXISTING-001"
                     :payment-summary "Transfer"
                     :amount 50000M
                     :paid-by "Customer"
                     :source ""
                     :reference "FT-001"}
                    {:date #inst "2024-02-15"
                     :external-id "EXISTING-001"
                     :payment-summary "Transfer"
                     :amount 60000M
                     :paid-by "Customer"
                     :source ""
                     :reference "FT-002"}]
          result (sut/board-existing-contract conn contract-data valid-fees valid-installments
                                              payments disbursement "test-user")]

      ;; Should succeed
      (is (true? (:success? result)))
      (is (some? (:contract-id result)))
      (is (= 2 (:payments-processed result)))
      (is (= 0 (:payments-skipped result)))

      ;; Verify contract state reflects payments
      (let [db (d/db conn)
            state (contract/contract-state db (:contract-id result) #inst "2024-06-15")]
        ;; Total due: 1000M (fee) + 200000M (principal) + 10000M (profit) = 211000M
        ;; Total paid: 50000 + 60000 = 110000M
        ;; Outstanding = 211000 - 110000 = 101000M
        (is (= 101000M (:total-outstanding state))))

      ;; Verify disbursement recorded
      (let [db (d/db conn)
            events (contract/get-events db (:contract-id result))
            disb-events (filter #(= :disbursement (:event-type %)) events)]
        (is (= 1 (count disb-events)))))))

(deftest board-existing-contract-skips-principle-rows-test
  (testing "Board existing contract skips 'Principle' rows via map-tx-type"
    (let [conn (get-test-conn)
          contract-data (assoc valid-contract-data :contract/external-id "SKIP-001")
          payments [{:date #inst "2024-01-15"
                     :external-id "SKIP-001"
                     :payment-summary "Transfer"
                     :amount 50000M
                     :paid-by "Customer"
                     :source ""
                     :reference "FT-001"}
                    {:date #inst "2024-01-20"
                     :external-id "SKIP-001"
                     :payment-summary "Principle"  ;; should be skipped
                     :amount 100000M
                     :paid-by "AlRaedah"
                     :source ""
                     :reference "INTERNAL-001"}]
          result (sut/board-existing-contract conn contract-data valid-fees valid-installments
                                              payments nil "test-user")]

      (is (true? (:success? result)))
      (is (= 1 (:payments-processed result)))
      (is (= 1 (:payments-skipped result))))))

(deftest board-existing-contract-no-disbursement-test
  (testing "Board existing contract without disbursement"
    (let [conn (get-test-conn)
          contract-data (assoc valid-contract-data :contract/external-id "NODISB-001")
          result (sut/board-existing-contract conn contract-data valid-fees valid-installments
                                              [] nil "test-user")]

      (is (true? (:success? result)))
      (is (= 0 (:payments-processed result)))

      ;; No disbursement events
      (let [db (d/db conn)
            events (contract/get-events db (:contract-id result))
            disb-events (filter #(= :disbursement (:event-type %)) events)]
        (is (= 0 (count disb-events)))))))

(deftest board-existing-contract-validation-failure-test
  (testing "Board existing contract with invalid data returns errors"
    (let [conn (get-test-conn)
          result (sut/board-existing-contract conn {} valid-fees valid-installments
                                              [] nil "test-user")]
      (is (false? (:success? result)))
      (is (seq (:errors result))))))

;; ============================================================
;; Integration: CSV → board round-trip
;; ============================================================

(deftest csv-to-board-round-trip-test
  (testing "Parse schedule CSV then board the contract end-to-end"
    (let [conn (get-test-conn)
          schedule-csv "Seq,Due Date,Principal Due,Profit Due,Remaining Principal
1,2024-01-31,100000,5000,200000
2,2024-02-28,100000,5000,100000"
          installments (sut/parse-schedule-csv schedule-csv 200000M)
          contract-data (assoc valid-contract-data :contract/external-id "CSV-BOARD-001")
          result (sut/board-new-contract conn contract-data valid-fees installments "test-user")]

      (is (true? (:success? result)))

      ;; Verify full contract state
      (let [db (d/db conn)
            state (contract/contract-state db (:contract-id result) #inst "2024-06-15")]
        (is (= 200000M (get-in state [:contract :principal])))
        (is (= 2 (count (:installments state))))
        (is (= 211000M (:total-outstanding state)))))))
