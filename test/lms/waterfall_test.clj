(ns lms.waterfall-test
  "Comprehensive tests for waterfall payment allocation.

   The waterfall function is the core business logic of the LMS. These
   tests ensure correctness across all scenarios:
   - Basic allocation (fees → installments)
   - Overpayment → credit balance
   - Partial payments
   - Profit before principal within installments
   - Multiple fees
   - Edge cases (zero payment, empty schedules)"
  (:require [clojure.test :refer [deftest is testing]]
            [lms.waterfall :as sut]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def fee-1000
  {:fee/id (random-uuid)
   :fee/amount 1000M
   :fee/due-date #inst "2024-01-01"})

(def fee-2000
  {:fee/id (random-uuid)
   :fee/amount 2000M
   :fee/due-date #inst "2024-01-15"})

(def inst-1
  {:installment/id (random-uuid)
   :installment/seq 1
   :installment/due-date #inst "2024-02-01"
   :installment/principal-due 100000M
   :installment/profit-due 10000M})

(def inst-2
  {:installment/id (random-uuid)
   :installment/seq 2
   :installment/due-date #inst "2024-03-01"
   :installment/principal-due 100000M
   :installment/profit-due 10000M})

;; ============================================================
;; Basic Allocation Tests
;; ============================================================

(deftest waterfall-basic-allocation
  (testing "Basic allocation: fee → installment profit → principal"
    (let [result (sut/waterfall [fee-1000] [inst-1] 50000M)]

      ;; Allocations
      (is (= 2 (count (:allocations result))))

      ;; Fee allocation
      (let [fee-alloc (first (:allocations result))]
        (is (= :fee (:type fee-alloc)))
        (is (= (:fee/id fee-1000) (:id fee-alloc)))
        (is (= 1000M (:amount fee-alloc))))

      ;; Installment allocation
      (let [inst-alloc (second (:allocations result))]
        (is (= :installment (:type inst-alloc)))
        (is (= (:installment/id inst-1) (:id inst-alloc)))
        (is (= 1 (:seq inst-alloc)))
        (is (= 10000M (:profit-paid inst-alloc)))
        (is (= 39000M (:principal-paid inst-alloc))))

      ;; No credit balance
      (is (= 0M (:credit-balance result)))

      ;; Verify total
      (is (:valid? (sut/verify-waterfall result 50000M))))))

(deftest waterfall-fees-first
  (testing "Fees are paid before installments"
    (let [result (sut/waterfall [fee-1000] [inst-1] 1000M)]

      ;; Only fee is paid
      (is (= 2 (count (:allocations result))))

      (let [fee-alloc (first (:allocations result))]
        (is (= 1000M (:amount fee-alloc))))

      (let [inst-alloc (second (:allocations result))]
        (is (= 0M (:profit-paid inst-alloc)))
        (is (= 0M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-profit-before-principal
  (testing "Within installment, profit is paid before principal"
    (let [result (sut/waterfall [] [inst-1] 105000M)]

      (let [inst-alloc (first (:allocations result))]
        ;; Profit paid in full
        (is (= 10000M (:profit-paid inst-alloc)))
        ;; Principal paid partially
        (is (= 95000M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-multiple-installments
  (testing "Installments paid in sequence order"
    (let [result (sut/waterfall [] [inst-1 inst-2] 115000M)]

      (is (= 2 (count (:allocations result))))

      ;; First installment paid in full
      (let [inst1-alloc (first (:allocations result))]
        (is (= 1 (:seq inst1-alloc)))
        (is (= 10000M (:profit-paid inst1-alloc)))
        (is (= 100000M (:principal-paid inst1-alloc))))

      ;; Second installment partially paid
      (let [inst2-alloc (second (:allocations result))]
        (is (= 2 (:seq inst2-alloc)))
        (is (= 5000M (:profit-paid inst2-alloc)))
        (is (= 0M (:principal-paid inst2-alloc))))

      (is (= 0M (:credit-balance result))))))

;; ============================================================
;; Overpayment Tests
;; ============================================================

(deftest waterfall-overpayment-credit-balance
  (testing "Overpayment creates credit balance"
    (let [result (sut/waterfall [fee-1000] [inst-1] 1000000M)]

      ;; Fee paid in full
      (let [fee-alloc (first (:allocations result))]
        (is (= 1000M (:amount fee-alloc))))

      ;; Installment paid in full
      (let [inst-alloc (second (:allocations result))]
        (is (= 10000M (:profit-paid inst-alloc)))
        (is (= 100000M (:principal-paid inst-alloc))))

      ;; Remaining goes to credit
      (is (= 889000M (:credit-balance result)))

      ;; Verify total
      (is (:valid? (sut/verify-waterfall result 1000000M))))))

(deftest waterfall-overpayment-no-schedule
  (testing "Payment with no fees or installments becomes full credit"
    (let [result (sut/waterfall [] [] 50000M)]
      (is (= 0 (count (:allocations result))))
      (is (= 50000M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result 50000M))))))

;; ============================================================
;; Partial Payment Tests
;; ============================================================

(deftest waterfall-partial-fee
  (testing "Partial fee payment"
    (let [result (sut/waterfall [fee-1000] [] 500M)]

      (let [fee-alloc (first (:allocations result))]
        (is (= 500M (:amount fee-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-partial-profit
  (testing "Partial installment profit payment (no principal)"
    (let [result (sut/waterfall [] [inst-1] 5000M)]

      (let [inst-alloc (first (:allocations result))]
        (is (= 5000M (:profit-paid inst-alloc)))
        (is (= 0M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-partial-principal
  (testing "Partial installment principal payment (profit paid first)"
    (let [result (sut/waterfall [] [inst-1] 15000M)]

      (let [inst-alloc (first (:allocations result))]
        ;; Profit paid in full
        (is (= 10000M (:profit-paid inst-alloc)))
        ;; Principal partially paid
        (is (= 5000M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result))))))

;; ============================================================
;; Zero Payment Tests
;; ============================================================

(deftest waterfall-zero-payment
  (testing "Zero payment allocates nothing"
    (let [result (sut/waterfall [fee-1000] [inst-1] 0M)]

      ;; Allocations exist but amounts are zero
      (is (= 2 (count (:allocations result))))

      (let [fee-alloc (first (:allocations result))]
        (is (= 0M (:amount fee-alloc))))

      (let [inst-alloc (second (:allocations result))]
        (is (= 0M (:profit-paid inst-alloc)))
        (is (= 0M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result 0M))))))

;; ============================================================
;; Multiple Fees Tests
;; ============================================================

(deftest waterfall-multiple-fees-by-due-date
  (testing "Multiple fees paid by due-date order"
    (let [result (sut/waterfall [fee-2000 fee-1000] [] 2500M)]

      ;; First fee (by date) paid in full
      (let [fee1-alloc (first (:allocations result))]
        (is (= (:fee/id fee-1000) (:id fee1-alloc)))
        (is (= 1000M (:amount fee1-alloc))))

      ;; Second fee partially paid
      (let [fee2-alloc (second (:allocations result))]
        (is (= (:fee/id fee-2000) (:id fee2-alloc)))
        (is (= 1500M (:amount fee2-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-multiple-fees-same-date
  (testing "Multiple fees with same due-date (stable sort)"
    (let [fee-a {:fee/id (random-uuid)
                 :fee/amount 1000M
                 :fee/due-date #inst "2024-01-01"}
          fee-b {:fee/id (random-uuid)
                 :fee/amount 2000M
                 :fee/due-date #inst "2024-01-01"}
          result (sut/waterfall [fee-a fee-b] [] 1500M)]

      ;; First fee (by input order) paid in full
      (let [fee1-alloc (first (:allocations result))]
        (is (= (:fee/id fee-a) (:id fee1-alloc)))
        (is (= 1000M (:amount fee1-alloc))))

      ;; Second fee partially paid
      (let [fee2-alloc (second (:allocations result))]
        (is (= (:fee/id fee-b) (:id fee2-alloc)))
        (is (= 500M (:amount fee2-alloc))))

      (is (= 0M (:credit-balance result))))))

;; ============================================================
;; Empty Schedule Tests
;; ============================================================

(deftest waterfall-no-fees
  (testing "No fees, only installments"
    (let [result (sut/waterfall [] [inst-1] 50000M)]

      (is (= 1 (count (:allocations result))))

      (let [inst-alloc (first (:allocations result))]
        (is (= 10000M (:profit-paid inst-alloc)))
        (is (= 40000M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-no-installments
  (testing "No installments, only fees"
    (let [result (sut/waterfall [fee-1000] [] 1000M)]

      (is (= 1 (count (:allocations result))))

      (let [fee-alloc (first (:allocations result))]
        (is (= 1000M (:amount fee-alloc))))

      (is (= 0M (:credit-balance result))))))

(deftest waterfall-empty-schedule
  (testing "No fees or installments"
    (let [result (sut/waterfall [] [] 0M)]
      (is (= 0 (count (:allocations result))))
      (is (= 0M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result 0M))))))

;; ============================================================
;; Helper Function Tests
;; ============================================================

(deftest allocation-for-fee-test
  (testing "Find fee allocation in result"
    (let [result (sut/waterfall [fee-1000] [] 1000M)
          alloc (sut/allocation-for-fee result (:fee/id fee-1000))]
      (is (not (nil? alloc)))
      (is (= :fee (:type alloc)))
      (is (= 1000M (:amount alloc))))))

(deftest allocation-for-installment-test
  (testing "Find installment allocation in result"
    (let [result (sut/waterfall [] [inst-1] 50000M)
          alloc (sut/allocation-for-installment result (:installment/id inst-1))]
      (is (not (nil? alloc)))
      (is (= :installment (:type alloc)))
      (is (= 10000M (:profit-paid alloc)))
      (is (= 40000M (:principal-paid alloc))))))

(deftest total-allocated-test
  (testing "Sum of all allocations"
    (let [result (sut/waterfall [fee-1000] [inst-1] 50000M)
          total (sut/total-allocated result)]
      (is (= 50000M total)))))

(deftest verify-waterfall-test
  (testing "Waterfall verification"
    (let [result (sut/waterfall [fee-1000] [inst-1] 1000000M)
          verification (sut/verify-waterfall result 1000000M)]

      (is (:valid? verification))
      (is (= 1000000M (:total-in verification)))
      (is (= 111000M (:total-allocated verification)))
      (is (= 889000M (:credit-balance verification)))
      (is (= 0M (:difference verification))))))

;; ============================================================
;; Complex Scenario Tests
;; ============================================================

(deftest waterfall-complex-scenario
  (testing "Complex scenario: multiple fees + multiple installments"
    (let [fees [fee-1000 fee-2000]
          installments [inst-1 inst-2]
          ;; Payment: 3000 + 110000 + 5000 = 118000
          result (sut/waterfall fees installments 118000M)]

      ;; Fee 1 paid in full
      (let [fee1-alloc (first (:allocations result))]
        (is (= 1000M (:amount fee1-alloc))))

      ;; Fee 2 paid in full
      (let [fee2-alloc (second (:allocations result))]
        (is (= 2000M (:amount fee2-alloc))))

      ;; Installment 1 paid in full
      (let [inst1-alloc (nth (:allocations result) 2)]
        (is (= 1 (:seq inst1-alloc)))
        (is (= 10000M (:profit-paid inst1-alloc)))
        (is (= 100000M (:principal-paid inst1-alloc))))

      ;; Installment 2 partially paid (profit only)
      (let [inst2-alloc (nth (:allocations result) 3)]
        (is (= 2 (:seq inst2-alloc)))
        (is (= 5000M (:profit-paid inst2-alloc)))
        (is (= 0M (:principal-paid inst2-alloc))))

      (is (= 0M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result 118000M))))))

;; ============================================================
;; Edge Cases
;; ============================================================

(deftest waterfall-exact-match
  (testing "Payment exactly matches total due"
    (let [total-due (+ (:fee/amount fee-1000)
                      (:installment/profit-due inst-1)
                      (:installment/principal-due inst-1))
          result (sut/waterfall [fee-1000] [inst-1] total-due)]

      ;; Everything paid exactly
      (is (= 2 (count (:allocations result))))
      (is (= 0M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result total-due))))))

(deftest waterfall-one-sar-short
  (testing "Payment is 1 SAR short of total"
    (let [total-due (+ (:fee/amount fee-1000)
                      (:installment/profit-due inst-1)
                      (:installment/principal-due inst-1))
          payment (- total-due 1M)
          result (sut/waterfall [fee-1000] [inst-1] payment)]

      ;; Fee paid in full, installment 1 SAR short on principal
      (let [inst-alloc (second (:allocations result))]
        (is (= 10000M (:profit-paid inst-alloc)))
        (is (= 99999M (:principal-paid inst-alloc))))

      (is (= 0M (:credit-balance result)))
      (is (:valid? (sut/verify-waterfall result payment))))))
