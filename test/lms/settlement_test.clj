(ns lms.settlement-test
  "Tests for settlement calculation.

   Verifies the pure derivation logic: installment classification,
   rate derivation, pro-rata accrual, penalty, and manual override."
  (:require [clojure.test :refer [deftest is testing]]
            [lms.db :as db]
            [lms.contract :as contract]
            [lms.settlement :as sut]
            [datomic.client.api :as d]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(defn get-test-conn
  "Get a fresh database connection for testing."
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
;; Helper: Bullet / Balloon Schedule (WakeCap-like)
;; ============================================================

(defn create-bullet-contract
  "Create a bullet/balloon contract: 11 profit-only + 1 amortizing.

   Principal: 9,652,509.65 SAR
   Profit per period: 128,700.13
   Monthly installments starting 2025-08-07."
  [conn contract-id]
  (let [principal 9652509.65M
        profit 128700.13M
        start-date #inst "2025-07-07"
        ;; 12 monthly installments
        installments
        (for [i (range 1 13)]
          (let [month (+ 7 i)  ;; Aug=8 .. Jul=19 (wraps into next year)
                year (if (> month 12) 2026 2025)
                m (if (> month 12) (- month 12) month)
                due-date (.getTime
                           (doto (java.util.Calendar/getInstance)
                             (.clear)
                             (.set year (dec m) 7 0 0 0)))]
            {:installment/id (random-uuid)
             :installment/contract "contract"
             :installment/seq i
             :installment/due-date due-date
             :installment/remaining-principal principal
             :installment/principal-due (if (= i 12) principal 0M)
             :installment/profit-due profit}))]
    (d/transact conn
                {:tx-data (concat
                            [{:db/id "contract"
                              :contract/id contract-id
                              :contract/external-id "WAKECAP-001"
                              :contract/customer-name "WakeCap Saudi"
                              :contract/customer-id "CR-7016779188"
                              :contract/status :active
                              :contract/start-date start-date
                              :contract/principal principal}
                             {:db/id "datomic.tx"
                              :tx/type :boarding
                              :tx/contract "contract"
                              :tx/author "test"}]
                            installments)})
    contract-id))

;; ============================================================
;; Helper: Diminishing Schedule
;; ============================================================

(defn create-diminishing-contract
  "Create a diminishing balance contract.

   Principal: 1,200,000. Rate: 15% annual. 12 monthly installments.
   Equal principal repayment (100K/month), profit decreases."
  [conn contract-id]
  (let [principal 1200000M
        monthly-principal 100000M
        start-date #inst "2024-01-01"
        installments
        (for [i (range 1 13)]
          (let [remaining (- principal (* monthly-principal (dec i)))
                profit (with-precision 10 :rounding HALF_UP
                         (* remaining (/ 0.15M 12)))
                due-date (.getTime
                           (doto (java.util.Calendar/getInstance)
                             (.clear)
                             (.set 2024 (dec (inc i)) 1 0 0 0)))]
            {:installment/id (random-uuid)
             :installment/contract "contract"
             :installment/seq i
             :installment/due-date due-date
             :installment/remaining-principal remaining
             :installment/principal-due monthly-principal
             :installment/profit-due profit}))]
    (d/transact conn
                {:tx-data (concat
                            [{:db/id "contract"
                              :contract/id contract-id
                              :contract/external-id "DIM-001"
                              :contract/customer-name "Diminishing Co."
                              :contract/customer-id "CR-DIM-001"
                              :contract/status :active
                              :contract/start-date start-date
                              :contract/principal principal}
                             {:db/id "datomic.tx"
                              :tx/type :boarding
                              :tx/contract "contract"
                              :tx/author "test"}]
                            installments)})
    contract-id))

;; ============================================================
;; Helper: Simple 2-installment contract
;; ============================================================

(defn create-simple-contract
  "Simple contract for basic edge-case testing.

   Principal: 200,000. 2 monthly installments. 100K principal each.
   Profit: 10K each. Remaining-principal: 200K then 100K."
  [conn contract-id]
  (d/transact conn
              {:tx-data [{:db/id "contract"
                          :contract/id contract-id
                          :contract/external-id (str "SIMPLE-" (subs (str contract-id) 0 8))
                          :contract/customer-name "Simple Co."
                          :contract/customer-id "CR-SIMPLE"
                          :contract/status :active
                          :contract/start-date #inst "2024-01-01"
                          :contract/principal 200000M}

                         {:installment/id (random-uuid)
                          :installment/contract "contract"
                          :installment/seq 1
                          :installment/due-date #inst "2024-02-01"
                          :installment/remaining-principal 200000M
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M}

                         {:installment/id (random-uuid)
                          :installment/contract "contract"
                          :installment/seq 2
                          :installment/due-date #inst "2024-03-01"
                          :installment/remaining-principal 100000M
                          :installment/principal-due 100000M
                          :installment/profit-due 10000M}

                         {:db/id "datomic.tx"
                          :tx/type :boarding
                          :tx/contract "contract"
                          :tx/author "test"}]})
  contract-id)

(defn record-payment [conn contract-id amount reference]
  (d/transact conn
              {:tx-data [{:payment/id (random-uuid)
                          :payment/amount amount
                          :payment/date #inst "2024-01-15T00:00:00.000-00:00"
                          :payment/contract [:contract/id contract-id]
                          :payment/reference reference}
                         {:db/id "datomic.tx"
                          :tx/author "test"}]}))

;; ============================================================
;; Tests: Installment Classification
;; ============================================================

(deftest settlement-all-past
  (testing "Settlement after last installment — all installments are PAST"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Settle after all installments are due
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      ;; All profit accrued (no pro-rata needed)
      (is (= 20000M (:accrued-profit result)))
      ;; Nothing paid
      (is (= 200000M (:outstanding-principal result)))
      (is (= 20000M (:accrued-unpaid-profit result)))
      (is (= 0M (:unearned-profit result)))
      ;; Settlement = principal + profit
      (is (= 220000M (:settlement-amount result))))))

(deftest settlement-all-future
  (testing "Settlement before first installment — all are CURRENT/FUTURE"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Settle on Jan 15 (before Feb 1 first due date)
          settlement-date #inst "2024-01-15"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      ;; Current = installment 1, accrued-days = 14 (Jan 1 → Jan 15)
      (is (= 14 (:accrued-days result)))
      ;; Pro-rata: daily-profit × 14 days
      (is (pos? (:accrued-profit result)))
      ;; Unearned should be most of the total profit
      (is (pos? (:unearned-profit result)))
      ;; No penalty
      (is (= 0M (:penalty-amount result))))))

(deftest settlement-on-due-date
  (testing "Settlement date = installment due-date → that installment is PAST"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Settle exactly on Feb 1 (installment 1 due date)
          settlement-date #inst "2024-02-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      ;; Installment 1 is PAST (due-date <= settlement-date)
      ;; So accrued-profit includes full profit-due of inst 1
      ;; Plus pro-rata of inst 2 (0 days into period → 0 accrued from current)
      (is (>= (:accrued-profit result) 10000M)))))

(deftest settlement-mid-period
  (testing "Settlement mid-period — pro-rata Actual/360"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Settle Feb 15 — between inst 1 (Feb 1) and inst 2 (Mar 1)
          settlement-date #inst "2024-02-15"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      ;; Installment 1 is PAST (full 10K profit accrued)
      ;; Installment 2 is CURRENT, period-start = Feb 1, period-end = Mar 1
      ;; accrued-days = 14 (Feb 1 → Feb 15)
      (is (= #inst "2024-02-01" (:current-period-start result)))
      (is (= #inst "2024-03-01" (:current-period-end result)))
      (is (= 14 (:accrued-days result)))
      ;; Accrued = 10000 (past) + pro-rata(current)
      (is (> (:accrued-profit result) 10000M))
      (is (< (:accrued-profit result) 20000M)))))

;; ============================================================
;; Tests: Penalty
;; ============================================================

(deftest settlement-with-penalty
  (testing "Penalty = daily-profit × penalty-days"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          settlement-date #inst "2024-02-15"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 90)]

      (is (= 90 (:penalty-days result)))
      (is (pos? (:penalty-amount result))))))


;; ============================================================
;; Tests: Payments reduce settlement
;; ============================================================

(deftest settlement-after-payment
  (testing "Payments reduce outstanding principal and profit"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Pay first installment fully (profit 10K + principal 100K)
          _ (record-payment conn cid 110000M "PAY-001")
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      (is (= 100000M (:outstanding-principal result)))
      ;; Total profit accrued = 20K (all past), paid = 10K
      (is (= 20000M (:accrued-profit result)))
      (is (= 10000M (:profit-already-paid result)))
      (is (= 10000M (:accrued-unpaid-profit result)))
      ;; Settlement = 100K + 10K = 110K
      (is (= 110000M (:settlement-amount result))))))

(deftest settlement-fully-paid
  (testing "Fully paid contract — settlement is 0 or negative (credit)"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Pay everything
          _ (record-payment conn cid 220000M "PAY-FULL")
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      (is (= 0M (:outstanding-principal result)))
      (is (= 0M (:outstanding-fees result)))
      ;; Settlement floors at 0M; any overpayment shows in :refund-due
      (is (= 0M (:settlement-amount result))))))

;; ============================================================
;; Tests: Manual Override
;; ============================================================

(deftest settlement-manual-override
  (testing "Manual override replaces accrued-unpaid-profit"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0
                                           :manual-override 5000M)]

      (is (true? (:manual-override? result)))
      ;; Override replaces accrued-unpaid-profit in settlement-amount
      ;; settlement = 200K principal + 5K override + 0 fees + 0 penalty - 0 credit
      (is (= 205000M (:settlement-amount result)))
      ;; But accrued-unpaid-profit still shows the computed value
      (is (= 20000M (:accrued-unpaid-profit result))))))

;; ============================================================
;; Tests: Bullet (WakeCap-like) Schedule
;; ============================================================

(deftest settlement-bullet-mid-period
  (testing "Bullet schedule — settle mid-period, remaining-principal constant"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-bullet-contract conn cid)
          ;; Settle Dec 20, 2025 — between inst 5 (Dec 7) and inst 6 (Jan 7)
          settlement-date #inst "2025-12-20"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 90)]

      ;; 5 past installments, each with 128,700.13 profit
      (is (> (:accrued-profit result) (* 5 128700.13M)))
      ;; Outstanding principal = full amount (bullet — no principal paid yet)
      (is (= 9652509.65M (:outstanding-principal result)))
      ;; Penalty present
      (is (= 90 (:penalty-days result)))
      (is (pos? (:penalty-amount result))))))

;; ============================================================
;; Tests: Diminishing Schedule
;; ============================================================

(deftest settlement-diminishing
  (testing "Diminishing schedule — rate is constant, profit decreases"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-diminishing-contract conn cid)
          ;; Settle after 3 installments due (Apr 15, between inst 3 and inst 4)
          settlement-date #inst "2024-04-15"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      ;; 3 past installments, outstanding principal = 1,200,000 (nothing paid)
      (is (= 1200000M (:outstanding-principal result)))
      ;; Accrued profit = sum of first 3 profit-dues + pro-rata of 4th
      (is (pos? (:accrued-profit result)))
      ;; Unearned profit = remaining installments' profit
      (is (pos? (:unearned-profit result))))))

;; ============================================================
;; Tests: Fees included
;; ============================================================

(deftest settlement-with-fees
  (testing "Outstanding fees included in settlement"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Add a fee
          _ (d/transact conn {:tx-data [{:fee/id (random-uuid)
                                         :fee/contract [:contract/id cid]
                                         :fee/type :management
                                         :fee/amount 5000M
                                         :fee/due-date #inst "2024-01-01"}]})
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      (is (= 5000M (:outstanding-fees result)))
      ;; Settlement includes fees
      (is (= (+ 200000M 20000M 5000M) (:settlement-amount result))))))

;; ============================================================
;; Tests: Credit balance reduces settlement
;; ============================================================

(deftest settlement-with-credit-balance
  (testing "Overpayment creates credit-balance that reduces settlement"
    (let [conn (get-test-conn)
          cid (random-uuid)
          _ (create-simple-contract conn cid)
          ;; Overpay by 10K
          _ (record-payment conn cid 230000M "PAY-OVER")
          settlement-date #inst "2024-04-01"
          state (contract/contract-state (d/db conn) cid settlement-date)
          result (sut/calculate-settlement state settlement-date 0)]

      (is (= 10000M (:credit-balance result)))
      ;; Settlement floors at 0M; refund-due shows the overpayment
      (is (= 0M (:settlement-amount result)))
      (is (pos? (:refund-due result))))))
