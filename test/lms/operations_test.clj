(ns lms.operations-test
  "Integration tests for write operations.

   These tests verify that operations correctly transact facts to Datomic
   and that contract-state correctly derives state from those facts.

   Test philosophy:
   - Each test gets fresh database (isolation)
   - Create contract → perform operation → verify state changed
   - Use fixed dates for reproducibility"
  (:require [clojure.test :refer [deftest is testing]]
            [lms.db :as db]
            [lms.contract :as contract]
            [lms.operations :as sut]
            [datomic.client.api :as d]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def test-as-of-date
  "Fixed date for testing: 2024-06-15 (mid-year)"
  #inst "2024-06-15T00:00:00.000-00:00")

(def test-borrower-id
  "Fixed UUID for test borrower party."
  #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

(defn create-test-borrower
  "Create a company party for use as borrower in tests."
  [conn]
  (d/transact conn
              {:tx-data [{:party/id test-borrower-id
                          :party/type :party.type/company
                          :party/legal-name "Test Customer Co."
                          :party/cr-number "CR-123456"}]}))

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

(defn create-simple-contract
  "Create a simple contract with 2 installments for testing.
   Note: status is derived. Contract starts as :pending until disbursement.
   Creates borrower party if needed.

   Returns contract-id."
  [conn]
  (create-test-borrower conn)
  (let [contract-id (random-uuid)]
    (sut/board-contract conn
                        {:contract/id contract-id
                         :contract/external-id (str "TEST-" (subs (str contract-id) 0 8))
                         :contract/borrower [:party/id test-borrower-id]
                         :contract/start-date #inst "2024-01-01"
                         :contract/principal 200000M
                         :contract/security-deposit 10000M}
                        [{:fee/id (random-uuid)
                          :fee/type :management
                          :fee/amount 1000M
                          :fee/due-date #inst "2024-01-01"}]
                        [{:installment/id (random-uuid)
                          :installment/seq 1
                          :installment/due-date #inst "2024-01-31"
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M
                          :installment/remaining-principal 200000M}
                         {:installment/id (random-uuid)
                          :installment/seq 2
                          :installment/due-date #inst "2024-02-28"
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M
                          :installment/remaining-principal 100000M}]
                        "test-user")
    contract-id))

;; ============================================================
;; Board Contract Tests
;; ============================================================

(deftest board-contract-test
  (testing "Board contract creates contract + schedule + boarding transaction"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          fee-id (random-uuid)
          inst-1-id (random-uuid)
          inst-2-id (random-uuid)]

      ;; Create borrower party and board contract
      (create-test-borrower conn)
      (sut/board-contract conn
                          {:contract/id contract-id
                           :contract/external-id "BOARD-TEST-001"
                           :contract/borrower [:party/id test-borrower-id]
                           :contract/start-date #inst "2024-01-01"
                           :contract/principal 1000000M
                           :contract/security-deposit 50000M}
                          [{:fee/id fee-id
                            :fee/type :management
                            :fee/amount 5000M
                            :fee/due-date #inst "2024-01-01"}]
                          [{:installment/id inst-1-id
                            :installment/seq 1
                            :installment/due-date #inst "2024-01-31"
                            :installment/principal-due 100000M
                            :installment/profit-due 10000M
                            :installment/remaining-principal 1000000M}
                           {:installment/id inst-2-id
                            :installment/seq 2
                            :installment/due-date #inst "2024-02-28"
                            :installment/principal-due 100000M
                            :installment/profit-due 10000M
                            :installment/remaining-principal 900000M}]
                          "test-user")

      ;; Verify contract exists
      (let [db (d/db conn)
            c (contract/get-contract db contract-id)]
        (is (not (nil? c)))
        (is (= "BOARD-TEST-001" (:contract/external-id c)))
        (is (= 1000000M (:contract/principal c))))

      ;; Verify fees created
      (let [db (d/db conn)
            fees (contract/get-fees db contract-id)]
        (is (= 1 (count fees)))
        (is (= :management (:fee/type (first fees)))))

      ;; Verify installments created
      (let [db (d/db conn)
            installments (contract/get-installments db contract-id)]
        (is (= 2 (count installments)))
        (is (= 1 (:installment/seq (first installments))))
        (is (= 2 (:installment/seq (second installments)))))

      ;; Verify boarding transaction
      (let [db (d/db conn)
            txs (contract/get-events db contract-id)]
        (is (= 1 (count txs)))
        (is (= :boarding (:type (first txs))))
        (is (= "test-user" (:author (first txs))))))))

;; ============================================================
;; Payment Tests
;; ============================================================

(deftest record-payment-test
  (testing "Record payment creates transaction and updates state"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)
          db-before (d/db conn)
          state-before (contract/contract-state db-before contract-id test-as-of-date)]

      ;; Record payment
      (sut/record-payment conn contract-id 50000M #inst "2024-01-15" "PAY-001" "test-user")

      ;; Verify transaction created
      (let [db-after (d/db conn)
            txs (contract/get-events db-after contract-id)
            payment-txs (filter #(= :payment (:event-type %)) txs)]
        (is (= 1 (count payment-txs)))
        (is (= 50000M (:amount (first payment-txs))))
        (is (= "PAY-001" (:reference (first payment-txs)))))

      ;; Verify state changed
      (let [db-after (d/db conn)
            state-after (contract/contract-state db-after contract-id test-as-of-date)]

        ;; Fee should be paid (1000M)
        (is (= :paid (:status (first (:fees state-after)))))

        ;; Installment 1 should be partial (10000 profit + 39000 principal = 49000M used)
        (let [inst-1 (first (:installments state-after))]
          (is (= :partial (:status inst-1)))
          (is (= 10000M (:profit-paid inst-1)))
          (is (= 39000M (:principal-paid inst-1))))

        ;; Total outstanding should decrease
        (is (< (:total-outstanding state-after)
               (:total-outstanding state-before)))))))

(deftest record-multiple-payments-test
  (testing "Multiple payments accumulate correctly"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Record three payments
      (sut/record-payment conn contract-id 50000M #inst "2024-01-15" "PAY-001" "test-user")
      (sut/record-payment conn contract-id 70000M #inst "2024-02-15" "PAY-002" "test-user")
      (sut/record-payment conn contract-id 100000M #inst "2024-03-15" "PAY-003" "test-user")

      ;; Total paid: 220,000
      ;; Fee: 1,000 (paid)
      ;; Inst 1: 110,000 (paid)
      ;; Inst 2: 109,000 (partial - profit fully paid, principal partially paid)

      (let [db (d/db conn)
            state (contract/contract-state db contract-id test-as-of-date)]

        ;; Fee paid
        (is (= :paid (:status (first (:fees state)))))

        ;; Inst 1 paid
        (is (= :paid (:status (first (:installments state)))))

        ;; Inst 2 partial (profit paid, principal partial)
        (let [inst-2 (second (:installments state))]
          (is (= :partial (:status inst-2)))
          (is (= 10000M (:profit-paid inst-2)))  ;; Profit fully paid
          (is (= 99000M (:principal-paid inst-2))))  ;; Principal partially paid

        ;; Outstanding: 1,000 (remaining principal on inst 2)
        (is (= 1000M (:total-outstanding state)))))))

(deftest record-payment-overpayment-test
  (testing "Overpayment creates credit balance"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Pay everything + extra
      (sut/record-payment conn contract-id 500000M #inst "2024-01-15" "PAY-LARGE" "test-user")

      ;; Total due: 1,000 (fee) + 220,000 (installments) = 221,000
      ;; Overpayment: 500,000 - 221,000 = 279,000

      (let [db (d/db conn)
            state (contract/contract-state db contract-id test-as-of-date)]

        (is (= :paid (:status (first (:fees state)))))
        (is (= :paid (:status (first (:installments state)))))
        (is (= :paid (:status (second (:installments state)))))

        (is (= 0M (:total-outstanding state)))
        (is (= 279000M (:credit-balance state)))))))

;; ============================================================
;; Preview Payment Tests
;; ============================================================

(deftest preview-payment-test
  (testing "Preview payment shows allocation without committing"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Preview payment (does NOT commit)
      (let [preview (sut/preview-payment conn contract-id 50000M)]

        ;; Should show before and after state
        (is (some? (:before preview)))
        (is (some? (:after preview)))
        (is (some? (:changes preview)))

        ;; Before: nothing paid
        (is (= 221000M (get-in preview [:before :total-outstanding])))

        ;; After: 50,000 paid
        (is (= 171000M (get-in preview [:after :total-outstanding])))

        ;; Changes should show fee paid + installment 1 partial payment
        (is (= 2 (count (:changes preview))))
        ;; First change: fee paid
        (let [fee-change (first (:changes preview))]
          (is (= :fee (:type fee-change)))
          (is (= 1000M (:amount fee-change))))
        ;; Second change: installment 1 partial
        (let [inst-change (second (:changes preview))]
          (is (= :installment (:type inst-change)))
          (is (= 1 (:seq inst-change)))
          (is (= 39000M (:principal-applied inst-change)))
          (is (= 10000M (:profit-applied inst-change)))))

      ;; Verify NO actual payment was recorded
      (let [db-after (d/db conn)
            txs (contract/get-events db-after contract-id)
            payment-txs (filter #(= :payment (:event-type %)) txs)]
        (is (= 0 (count payment-txs)))))))

;; ============================================================
;; Retraction Tests
;; ============================================================

(deftest retract-payment-test
  (testing "Retract payment removes it and restores prior state"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Record a payment
      (sut/record-payment conn contract-id 50000M #inst "2024-01-15" "PAY-001" "test-user")

      ;; Verify payment exists and affected state
      (let [db (d/db conn)
            state (contract/contract-state db contract-id test-as-of-date)
            payments (contract/get-payments db contract-id)]
        (is (= 1 (count payments)))
        (is (< (:total-outstanding state) 221000M))

        ;; Retract the payment
        (let [payment-id (:payment/id (first payments))]
          (sut/retract-payment conn payment-id :duplicate-removal "test-user"
                               :note "Duplicate of FT-ANB-123")

          ;; Verify payment is gone from current db
          (let [db-after (d/db conn)
                payments-after (contract/get-payments db-after contract-id)
                state-after (contract/contract-state db-after contract-id test-as-of-date)]

            ;; Payment no longer appears in queries
            (is (= 0 (count payments-after)))

            ;; State returns to pre-payment (full outstanding)
            (is (= 221000M (:total-outstanding state-after)))))))))

(deftest retract-contract-test
  (testing "Retract contract removes contract and all associated entities"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Add some activity to the contract
      (sut/record-payment conn contract-id 50000M #inst "2024-01-15" "PAY-001" "test-user")
      (sut/record-disbursement conn contract-id 200000M #inst "2024-01-02" "WIRE-001" "test-user")
      (sut/receive-deposit conn contract-id 10000M #inst "2024-01-15" "test-user")
      (sut/record-principal-allocation conn contract-id 1000M #inst "2024-01-01" "test-user")

      ;; Verify everything exists before retraction
      (let [db (d/db conn)]
        (is (some? (:db/id (contract/get-contract db contract-id))))
        (is (= 1 (count (contract/get-fees db contract-id))))
        (is (= 2 (count (contract/get-installments db contract-id))))
        (is (= 1 (count (contract/get-payments db contract-id))))
        (is (= 1 (count (contract/get-disbursements db contract-id))))
        (is (= 1 (count (contract/get-deposits db contract-id))))
        (is (= 1 (count (contract/get-principal-allocations db contract-id)))))

      ;; Retract the contract
      (sut/retract-contract conn contract-id :erroneous-entry "test-user"
                            :note "Contract boarded against wrong customer")

      ;; Verify everything is gone
      (let [db (d/db conn)]
        (is (nil? (:db/id (contract/get-contract db contract-id))))
        (is (empty? (contract/get-fees db contract-id)))
        (is (empty? (contract/get-installments db contract-id)))
        (is (empty? (contract/get-payments db contract-id)))
        (is (empty? (contract/get-disbursements db contract-id)))
        (is (empty? (contract/get-deposits db contract-id)))
        (is (empty? (contract/get-principal-allocations db contract-id)))

        ;; Contract no longer appears in list-contracts
        (is (empty? (filter #(= contract-id (:id %))
                            (contract/list-contracts db)))))))

  (testing "Retract contract with no activity removes contract + schedule only"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/retract-contract conn contract-id :erroneous-entry "test-user")

      (let [db (d/db conn)]
        (is (nil? (:db/id (contract/get-contract db contract-id))))
        (is (empty? (contract/get-fees db contract-id)))
        (is (empty? (contract/get-installments db contract-id))))))

  (testing "Retract contract throws when contract not found"
    (let [conn (get-test-conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not found"
                            (sut/retract-contract conn (random-uuid)
                                                  :erroneous-entry "test-user"))))))

;; ============================================================
;; Rate Adjustment Tests
;; ============================================================

(deftest adjust-rate-test
  (testing "Adjust rate updates installment profit-due"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)
          db-before (d/db conn)
          inst-before (contract/get-installments db-before contract-id)]

      ;; Both installments start with 10,000 profit-due
      (is (= 10000M (:installment/profit-due (first inst-before))))
      (is (= 10000M (:installment/profit-due (second inst-before))))

      ;; Adjust rate for installment 2 to 12% (from 10%)
      ;; New profit: 100,000 * 0.12 * (1/12) = 1,000
      (sut/adjust-rate conn contract-id 2 2 0.12M "Test adjustment" "test-user")

      ;; Verify installment 2 updated
      (let [db-after (d/db conn)
            inst-after (contract/get-installments db-after contract-id)]

        ;; Inst 1 unchanged
        (is (= 10000M (:installment/profit-due (first inst-after))))

        ;; Inst 2 changed to 1,000
        (is (= 1000M (:installment/profit-due (second inst-after)))))

      ;; Verify rate-adjustment transaction
      (let [db-after (d/db conn)
            txs (contract/get-events db-after contract-id)
            rate-txs (filter #(= :rate-adjustment (:type %)) txs)]
        (is (= 1 (count rate-txs)))
        (is (= "test-user" (:author (first rate-txs))))))))

(deftest adjust-rate-multiple-installments-test
  (testing "Adjust rate for range of installments"
    (let [conn (get-test-conn)
          contract-id (random-uuid)]

      ;; Create borrower party and contract with 4 installments
      (create-test-borrower conn)
      (sut/board-contract conn
                          {:contract/id contract-id
                           :contract/external-id (str "ADJ-" (subs (str contract-id) 0 8))
                           :contract/borrower [:party/id test-borrower-id]
                           :contract/start-date #inst "2024-01-01"
                           :contract/principal 400000M
                           :contract/security-deposit 10000M}
                          []
                          (for [i (range 1 5)]
                            {:installment/id (random-uuid)
                             :installment/seq i
                             :installment/due-date #inst "2024-01-31"
                             :installment/principal-due 100000M
                             :installment/profit-due 10000M
                             :installment/remaining-principal (* (- 5 i) 100000M)})
                          "test-user")

      ;; Adjust installments 2-3 to 8%
      (sut/adjust-rate conn contract-id 2 3 0.08M "Step-up discount" "test-user")

      (let [db (d/db conn)
            installments (contract/get-installments db contract-id)]

        ;; Inst 1: unchanged (10,000)
        (is (= 10000M (:installment/profit-due (nth installments 0))))

        ;; Inst 2-3: changed to 2/3 of remaining-principal * rate / 12
        (is (= 666.6666666M (:installment/profit-due (nth installments 1))))
        (is (= 666.6666666M (:installment/profit-due (nth installments 2))))

        ;; Inst 4: unchanged (10,000)
        (is (= 10000M (:installment/profit-due (nth installments 3))))))))

;; ============================================================
;; Security Deposit Tests
;; ============================================================

(deftest receive-deposit-test
  (testing "Receive deposit creates transaction and increases deposit-held"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)
          db-before (d/db conn)
          state-before (contract/contract-state db-before contract-id test-as-of-date)]

      ;; Initially no deposit held
      (is (= 0M (:deposit-held state-before)))

      ;; Receive deposit
      (sut/receive-deposit conn contract-id 10000M #inst "2024-01-15" "test-user")

      ;; Verify transaction
      (let [db-after (d/db conn)
            txs (contract/get-events db-after contract-id)
            deposit-txs (filter #(= :deposit (:event-type %)) txs)]
        (is (= 1 (count deposit-txs)))
        (is (= 10000M (:amount (first deposit-txs)))))

      ;; Verify deposit-held increased
      (let [db-after (d/db conn)
            state-after (contract/contract-state db-after contract-id test-as-of-date)]
        (is (= 10000M (:deposit-held state-after)))))))

(deftest refund-deposit-test
  (testing "Refund deposit decreases deposit-held"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Receive then refund
      (sut/receive-deposit conn contract-id 10000M #inst "2024-01-15" "test-user")
      (sut/refund-deposit conn contract-id 5000M #inst "2024-02-15" "Partial refund" "test-user")

      (let [db (d/db conn)
            state (contract/contract-state db contract-id test-as-of-date)]
        (is (= 5000M (:deposit-held state)))))))

(deftest offset-deposit-test
  (testing "Offset deposit reduces deposit-held and outstanding balance"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Receive deposit
      (sut/receive-deposit conn contract-id 10000M #inst "2024-01-15" "test-user")

      (let [db-before (d/db conn)
            state-before (contract/contract-state db-before contract-id test-as-of-date)]
        (is (= 10000M (:deposit-held state-before)))
        (is (= 221000M (:total-outstanding state-before))))

      ;; Offset deposit (flows through waterfall like payment)
      (sut/offset-deposit conn contract-id 10000M #inst "2024-02-15" "Apply to balance" "test-user")

      (let [db-after (d/db conn)
            state-after (contract/contract-state db-after contract-id test-as-of-date)]

        ;; Deposit-held reduced
        (is (= 0M (:deposit-held state-after)))

        ;; Outstanding reduced (fee 1,000 + inst1 profit 9,000 = 10,000 paid)
        (is (= 211000M (:total-outstanding state-after)))

        ;; Fee paid
        (is (= :paid (:status (first (:fees state-after)))))

        ;; Installment 1 partial
        (let [inst-1 (first (:installments state-after))]
          (is (= :partial (:status inst-1)))
          (is (= 9000M (:profit-paid inst-1)))
          (is (= 0M (:principal-paid inst-1))))))))

;; ============================================================
;; Disbursement Tests
;; ============================================================

(deftest record-disbursement-test
  (testing "Record disbursement creates audit trail transaction"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      ;; Record disbursement
      (sut/record-disbursement conn contract-id 200000M #inst "2024-01-02" "WIRE-123" "test-user")

      ;; Verify transaction created
      (let [db (d/db conn)
            txs (contract/get-events db contract-id)
            disb-txs (filter #(= :disbursement (:event-type %)) txs)]
        (is (= 1 (count disb-txs)))
        (is (= 200000M (:amount (first disb-txs))))
        (is (= "WIRE-123" (:reference (first disb-txs)))))))

  (testing "Record disbursement shifts installment dates but NOT fee dates"
    (let [conn (get-test-conn)
          contract-id (random-uuid)
          fee-id (random-uuid)]

      ;; Board contract WITH days-to-first-installment AND a fee
      ;; Schedule has first installment on Jan 31 (30 days after Jan 1)
      (create-test-borrower conn)
      (sut/board-contract conn
                          {:contract/id contract-id
                           :contract/external-id (str "TEST-SHIFT-" (subs (str contract-id) 0 8))
                           :contract/borrower [:party/id test-borrower-id]
                           :contract/start-date #inst "2024-01-01"
                           :contract/principal 200000M
                           :contract/days-to-first-installment 30}  ;; 30 days after disbursement
                          [{:fee/id fee-id
                            :fee/type :management
                            :fee/amount 5000M
                            :fee/due-date #inst "2024-05-06"}]  ;; Fee due May 6
                          [{:installment/id (random-uuid)
                            :installment/seq 1
                            :installment/due-date #inst "2024-01-31"  ;; Placeholder date
                            :installment/principal-due 100000M
                            :installment/profit-due 10000M
                            :installment/remaining-principal 200000M}
                           {:installment/id (random-uuid)
                            :installment/seq 2
                            :installment/due-date #inst "2024-02-28"  ;; Placeholder date
                            :installment/principal-due 100000M
                            :installment/profit-due 10000M
                            :installment/remaining-principal 100000M}]
                          "test-user")

      ;; Disburse on Jan 15 - first installment should shift to Feb 14 (Jan 15 + 30 days)
      ;; That's a shift of 14 days forward from Jan 31
      (sut/record-disbursement conn contract-id 200000M #inst "2024-01-15" "WIRE-456" "test-user")

      ;; Verify installment dates were shifted
      (let [db (d/db conn)
            installments (contract/get-installments db contract-id)
            inst1 (first (filter #(= 1 (:installment/seq %)) installments))
            inst2 (first (filter #(= 2 (:installment/seq %)) installments))]
        ;; First installment: Jan 15 + 30 = Feb 14
        (is (= #inst "2024-02-14" (:installment/due-date inst1))
            "First installment should be 30 days after disbursement")
        ;; Second installment: Feb 28 + 14 days shift = Mar 13
        (is (= #inst "2024-03-13" (:installment/due-date inst2))
            "Second installment should shift by same amount"))

      ;; Verify fee date was NOT shifted
      (let [db (d/db conn)
            fees (contract/get-fees db contract-id)
            fee (first fees)]
        (is (= #inst "2024-05-06" (:fee/due-date fee))
            "Fee due date must NOT be shifted by disbursement")))))

;; ============================================================
;; Principal Allocation Tests
;; ============================================================

(deftest record-principal-allocation-test
  (testing "Record principal allocation creates entity and is queryable"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/record-principal-allocation conn contract-id 1000M
                                       #inst "2024-01-15" "test-user"
                                       :reference "FUNDING-FEE-SETTLEMENT")

      (let [db (d/db conn)
            allocations (contract/get-principal-allocations db contract-id)]
        (is (= 1 (count allocations)))
        (let [pa (first allocations)]
          (is (= 1000M (:principal-allocation/amount pa)))
          (is (some? (:principal-allocation/id pa)))
          (is (= "FUNDING-FEE-SETTLEMENT" (:principal-allocation/reference pa)))))))

  (testing "Principal allocation appears in query-facts"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/record-principal-allocation conn contract-id 1000M
                                       #inst "2024-01-15" "test-user")

      (let [facts (contract/query-facts (d/db conn) contract-id)]
        (is (= 1 (count (:principal-allocations facts)))))))

  (testing "Principal allocation rejects non-positive amount"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"positive"
                            (sut/record-principal-allocation conn contract-id 0M
                                                             #inst "2024-01-15" "test-user")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"positive"
                            (sut/record-principal-allocation conn contract-id -100M
                                                             #inst "2024-01-15" "test-user"))))))

(deftest record-typed-principal-allocation-test
  (testing "Principal allocation with :type and :fee-id stores both attributes"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)
          fees (contract/get-fees (d/db conn) contract-id)
          fee-id (:fee/id (first fees))]

      (sut/record-principal-allocation conn contract-id 1000M
                                       #inst "2024-01-15" "test-user"
                                       :type :fee-settlement
                                       :fee-id fee-id
                                       :reference "FUNDING-FEE-SETTLEMENT")

      (let [db (d/db conn)
            allocations (contract/get-principal-allocations db contract-id)
            pa (first allocations)]
        (is (= 1 (count allocations)))
        (is (= :fee-settlement (:principal-allocation/type pa)))
        (is (= fee-id (get-in pa [:principal-allocation/fee :fee/id])))
        (is (= "FUNDING-FEE-SETTLEMENT" (:principal-allocation/reference pa))))))

  (testing "Principal allocation with :type :deposit stores type"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/record-principal-allocation conn contract-id 10000M
                                       #inst "2024-01-15" "test-user"
                                       :type :deposit
                                       :reference "FUNDING-DEPOSIT")

      (let [db (d/db conn)
            pa (first (contract/get-principal-allocations db contract-id))]
        (is (= :deposit (:principal-allocation/type pa)))
        (is (nil? (:principal-allocation/fee pa)))))))

(deftest record-excess-return-test
  (testing "Record excess return creates disbursement with type :excess-return"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/record-excess-return conn contract-id 35000M #inst "2024-01-15"
                                "WT-001-EXCESS" "test-user")

      (let [db (d/db conn)
            disbursements (contract/get-disbursements db contract-id)
            excess (filter #(= :excess-return (:disbursement/type %)) disbursements)]
        (is (= 1 (count excess)))
        (is (= 35000M (:disbursement/amount (first excess))))
        (is (= "WT-001-EXCESS" (:disbursement/reference (first excess))))))))

;; ============================================================
;; Deposit Source and Transfer Tests
;; ============================================================

(deftest receive-deposit-with-source-test
  (testing "Deposit with :source stores deposit/source attribute"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/receive-deposit conn contract-id 40000M #inst "2024-01-15" "test-user"
                           :source :funding)

      (let [db (d/db conn)
            deposits (contract/get-deposits db contract-id)
            funded (filter #(= :funding (:deposit/source %)) deposits)]
        (is (= 1 (count funded)))
        (is (= 40000M (:deposit/amount (first funded)))))))

  (testing "Deposit without :source omits deposit/source attribute"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/receive-deposit conn contract-id 10000M #inst "2024-01-15" "test-user")

      (let [db (d/db conn)
            deposits (contract/get-deposits db contract-id)]
        (is (= 1 (count deposits)))
        (is (nil? (:deposit/source (first deposits))))))))

(deftest transfer-deposit-test
  (testing "Transfer deposit creates entity with both contract refs"
    (let [conn (get-test-conn)
          source-id (create-simple-contract conn)
          target-id (create-simple-contract conn)]

      ;; First receive deposit on source contract
      (sut/receive-deposit conn source-id 30000M #inst "2024-01-15" "test-user")

      ;; Transfer from source to target
      (sut/transfer-deposit conn source-id target-id 30000M
                            #inst "2024-06-01" "test-user"
                            :reference "REFI-DEP-TRANSFER")

      ;; Verify transfer entity exists on source contract
      (let [db (d/db conn)
            source-deposits (contract/get-deposits db source-id)
            transfers (filter #(= :transfer (:deposit/type %)) source-deposits)]
        (is (= 1 (count transfers)))
        (is (= 30000M (:deposit/amount (first transfers))))
        (is (= "REFI-DEP-TRANSFER" (:deposit/reference (first transfers)))))

      ;; Verify transfer shows up on target contract too (via get-deposits OR clause)
      (let [db (d/db conn)
            target-deposits (contract/get-deposits db target-id)
            transfers-in (filter #(= :transfer (:deposit/type %)) target-deposits)]
        (is (= 1 (count transfers-in)))))))

;; ============================================================
;; Payment Source Contract Tests
;; ============================================================

(deftest record-payment-with-source-contract-test
  (testing "Payment with source-contract stores payment/source-contract ref"
    (let [conn (get-test-conn)
          old-contract-id (create-simple-contract conn)
          new-contract-id (create-simple-contract conn)]

      ;; Record settlement payment on old contract funded by new contract
      (sut/record-payment conn old-contract-id 100000M #inst "2024-06-01"
                          "REFI-SETTLE" "test-user"
                          :source-contract new-contract-id)

      (let [db (d/db conn)
            payments (contract/get-payments db old-contract-id)
            settlement (first payments)]
        (is (= 1 (count payments)))
        (is (= 100000M (:payment/amount settlement)))
        ;; source-contract should be a ref to the new contract
        (is (some? (:payment/source-contract settlement))))))

  (testing "Payment without source-contract omits the attribute"
    (let [conn (get-test-conn)
          contract-id (create-simple-contract conn)]

      (sut/record-payment conn contract-id 50000M #inst "2024-01-15"
                          "PAY-001" "test-user")

      (let [db (d/db conn)
            payments (contract/get-payments db contract-id)]
        (is (nil? (:payment/source-contract (first payments))))))))
