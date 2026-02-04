(ns lms.contract-test
  "Integration tests for contract queries and state derivation.

   These tests verify the complete flow: schema → data → waterfall → state.
   Uses in-memory Datomic for each test (isolated, no shared state)."
  (:require [clojure.test :refer [deftest is testing]]
            [lms.db :as db]
            [lms.contract :as sut]
            [lms.waterfall :as waterfall]
            [datomic.client.api :as d]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def test-as-of-date
  "Fixed date for testing: 2024-01-15 (after contract start, before most due dates)"
  #inst "2024-01-15T00:00:00.000-00:00")

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

;; ============================================================
;; Helper Functions
;; ============================================================

(defn create-test-contract
  "Create a minimal test contract with fees and installments.

   Returns contract-id."
  [conn contract-id]
  (d/transact conn
              {:tx-data [{:db/id "contract"
                          :contract/id contract-id
                          :contract/external-id (str "TEST-" (subs (str contract-id) 0 8))
                          :contract/customer-name "Test Customer Co."
                          :contract/customer-id "CR-123456"
                          :contract/status :active
                          :contract/start-date #inst "2024-01-01"
                          :contract/maturity-date #inst "2024-12-31"
                          :contract/principal 1200000M
                          :contract/security-deposit 60000M}

                         ;; Fee
                         {:fee/id (random-uuid)
                          :fee/contract "contract"
                          :fee/type :management
                          :fee/amount 5000M
                          :fee/due-date #inst "2024-01-01"}

                         ;; Installments
                         {:installment/id (random-uuid)
                          :installment/contract "contract"
                          :installment/seq 1
                          :installment/due-date #inst "2024-01-31"
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M
                          :installment/remaining-principal 1200000M}

                         {:installment/id (random-uuid)
                          :installment/contract "contract"
                          :installment/seq 2
                          :installment/due-date #inst "2024-02-28"
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M
                          :installment/remaining-principal 1100000M}

                         ;; Boarding transaction
                         {:db/id "datomic.tx"
                          :tx/type :boarding
                          :tx/contract "contract"
                          :tx/author "test"}]})
  contract-id)

(defn record-payment
  "Record a payment as a payment/* entity (matching operations/record-payment)."
  [conn contract-id amount reference]
  (d/transact conn
              {:tx-data [{:payment/id (random-uuid)
                          :payment/amount amount
                          :payment/date #inst "2024-01-15T00:00:00.000-00:00"
                          :payment/contract [:contract/id contract-id]
                          :payment/reference reference}
                         {:db/id "datomic.tx"
                          :tx/author "test"}]}))

;; ============================================================
;; Query Tests
;; ============================================================

(deftest get-contract-test
  (testing "Query contract by ID"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          db (d/db conn)
          contract (sut/get-contract db contract-id)]

      (is (not (nil? contract)))
      (is (= contract-id (:contract/id contract)))
      (is (.startsWith (:contract/external-id contract) "TEST-"))
      (is (= :active (:contract/status contract)))
      (is (= 1200000M (:contract/principal contract))))))

(deftest get-fees-test
  (testing "Query fees for contract"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          db (d/db conn)
          fees (sut/get-fees db contract-id)]

      (is (= 1 (count fees)))
      (let [fee (first fees)]
        (is (= :management (:fee/type fee)))
        (is (= 5000M (:fee/amount fee)))))))

(deftest get-installments-test
  (testing "Query installments for contract"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          db (d/db conn)
          installments (sut/get-installments db contract-id)]

      (is (= 2 (count installments)))
      (let [inst-1 (first installments)
            inst-2 (second installments)]
        (is (= 1 (:installment/seq inst-1)))
        (is (= 100000M (:installment/principal-due inst-1)))
        (is (= 10000M (:installment/profit-due inst-1)))
        (is (= 2 (:installment/seq inst-2)))))))

(deftest get-transactions-test
  (testing "Query transactions for contract"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          _ (record-payment conn contract-id 50000M "PAY-001")
          db (d/db conn)
          txs (sut/get-events db contract-id)]

      (is (= 2 (count txs)))  ;; boarding + payment
      (let [boarding-tx (first (filter #(= :boarding (:type %)) txs))
            payment-tx (first (filter #(= :payment (:event-type %)) txs))]
        (is (not (nil? boarding-tx)))
        (is (not (nil? payment-tx)))
        (is (= 50000M (:amount payment-tx)))
        (is (= "PAY-001" (:reference payment-tx)))))))

;; ============================================================
;; Contract State Tests
;; ============================================================

(deftest contract-state-no-payments
  (testing "Contract state with no payments"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          db (d/db conn)
          state (sut/contract-state db contract-id test-as-of-date)]

      ;; Contract
      (is (= contract-id (get-in state [:contract :id])))
      (is (.startsWith (get-in state [:contract :external-id]) "TEST-"))

      ;; Fees: unpaid
      (is (= 1 (count (:fees state))))
      (let [fee (first (:fees state))]
        (is (= 5000M (:amount fee)))
        (is (= 0M (:paid fee)))
        (is (= 5000M (:outstanding fee)))
        (is (= :unpaid (:status fee))))

      ;; Installments: scheduled
      (is (= 2 (count (:installments state))))
      (let [inst-1 (first (:installments state))]
        (is (= 1 (:seq inst-1)))
        (is (= 0M (:profit-paid inst-1)))
        (is (= 0M (:principal-paid inst-1)))
        (is (= 110000M (:outstanding inst-1)))
        (is (= :scheduled (:status inst-1))))

      ;; Totals
      (is (= 5000M (:total-fees-due state)))
      (is (= 0M (:total-fees-paid state)))
      (is (= 200000M (:total-principal-due state)))
      (is (= 0M (:total-principal-paid state)))
      (is (= 20000M (:total-profit-due state)))
      (is (= 0M (:total-profit-paid state)))
      (is (= 225000M (:total-outstanding state)))
      (is (= 0M (:credit-balance state))))))

(deftest contract-state-with-payment
  (testing "Contract state after payment"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          _ (record-payment conn contract-id 50000M "PAY-001")
          db (d/db conn)
          state (sut/contract-state db contract-id test-as-of-date)]

      ;; Fee: paid
      (let [fee (first (:fees state))]
        (is (= 5000M (:paid fee)))
        (is (= 0M (:outstanding fee)))
        (is (= :paid (:status fee))))

      ;; Installment 1: partial (profit paid + part of principal)
      (let [inst-1 (first (:installments state))]
        (is (= 1 (:seq inst-1)))
        (is (= 10000M (:profit-paid inst-1)))
        (is (= 35000M (:principal-paid inst-1)))
        (is (= 65000M (:outstanding inst-1)))
        (is (= :partial (:status inst-1))))

      ;; Installment 2: scheduled (no payment yet)
      (let [inst-2 (second (:installments state))]
        (is (= 2 (:seq inst-2)))
        (is (= 0M (:profit-paid inst-2)))
        (is (= 0M (:principal-paid inst-2)))
        (is (= 110000M (:outstanding inst-2)))
        (is (= :scheduled (:status inst-2))))

      ;; Totals
      (is (= 5000M (:total-fees-paid state)))
      (is (= 35000M (:total-principal-paid state)))
      (is (= 10000M (:total-profit-paid state)))
      (is (= 175000M (:total-outstanding state)))
      (is (= 0M (:credit-balance state))))))

(deftest contract-state-overpayment
  (testing "Contract state with overpayment creates credit balance"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          _ (record-payment conn contract-id 1000000M "PAY-LARGE")
          db (d/db conn)
          state (sut/contract-state db contract-id test-as-of-date)]

      ;; Fee: paid
      (let [fee (first (:fees state))]
        (is (= :paid (:status fee))))

      ;; Both installments: paid
      (let [inst-1 (first (:installments state))
            inst-2 (second (:installments state))]
        (is (= :paid (:status inst-1)))
        (is (= :paid (:status inst-2))))

      ;; Totals
      (is (= 0M (:total-outstanding state)))
      (is (= 775000M (:credit-balance state))))))  ;; 1000000 - 225000

(deftest contract-state-multiple-payments
  (testing "Contract state with multiple payments"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          _ (create-test-contract conn contract-id)
          _ (record-payment conn contract-id 50000M "PAY-001")
          _ (record-payment conn contract-id 70000M "PAY-002")
          db (d/db conn)
          state (sut/contract-state db contract-id test-as-of-date)]

      ;; Total of 120,000 paid
      ;; Fee: 5,000 (paid)
      ;; Inst 1: profit 10,000 + principal 100,000 (paid)
      ;; Inst 2: profit 5,000 (partial)

      (is (= 5000M (:total-fees-paid state)))
      (is (= 100000M (:total-principal-paid state)))
      (is (= 15000M (:total-profit-paid state)))
      (is (= 105000M (:total-outstanding state)))

      (let [inst-1 (first (:installments state))
            inst-2 (second (:installments state))]
        (is (= :paid (:status inst-1)))
        (is (= :partial (:status inst-2)))
        (is (= 5000M (:profit-paid inst-2)))
        (is (= 0M (:principal-paid inst-2)))))))

;; ============================================================
;; Status Derivation Tests
;; ============================================================

(deftest derive-status-paid
  (testing "Status is :paid when paid >= due"
    (let [inst {:installment/due-date #inst "2024-01-31"}
          status (sut/derive-status inst 110000M 110000M test-as-of-date)]
      (is (= :paid status)))))

(deftest derive-status-partial
  (testing "Status is :partial when 0 < paid < due"
    (let [inst {:installment/due-date #inst "2024-01-31"}
          status (sut/derive-status inst 50000M 110000M test-as-of-date)]
      (is (= :partial status)))))

(deftest derive-status-overdue
  (testing "Status is :overdue when paid = 0 and past due"
    (let [inst {:installment/due-date #inst "2024-01-31"}
          ;; Use a date after due date
          as-of #inst "2024-03-01"
          status (sut/derive-status inst 0M 110000M as-of)]
      (is (= :overdue status)))))

(deftest derive-status-scheduled
  (testing "Status is :scheduled when paid = 0 and not past due"
    (let [inst {:installment/due-date #inst "2024-12-31"}
          ;; Use a date before due date
          as-of #inst "2024-01-01"
          status (sut/derive-status inst 0M 110000M as-of)]
      (is (= :scheduled status)))))

;; ============================================================
;; List Contracts Tests
;; ============================================================

(deftest list-contracts-all
  (testing "List all contracts"
    (let [conn (get-test-conn)
          id-1 (random-uuid)
          id-2 (random-uuid)
          _ (create-test-contract conn id-1)
          _ (create-test-contract conn id-2)
          db (d/db conn)
          contracts (sut/list-contracts db nil)]

      (is (= 2 (count contracts)))
      (is (every? #(.startsWith (:external-id %) "TEST-") contracts))
      (is (every? #(= :active (:status %)) contracts)))))

(deftest list-contracts-with-filter
  (testing "List contracts filtered by status"
    (let [conn (get-test-conn)
          id-1 (random-uuid)
          _ (create-test-contract conn id-1)
          db (d/db conn)
          active-contracts (sut/list-contracts db :active)
          closed-contracts (sut/list-contracts db :closed)]

      (is (= 1 (count active-contracts)))
      (is (= 0 (count closed-contracts))))))
