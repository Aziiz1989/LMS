(ns lms.waterfall
  "Payment waterfall allocation logic.

   PURE FUNCTION. No database dependencies, no side effects.

   The waterfall defines how customer payments are allocated:
   1. Fees (by due-date, oldest first)
   2. Installments (by seq, earliest first)
      - Within each installment: profit before principal

   This is the core business rule of the system. Everything else
   derives from waterfall allocations.

   Philosophy: This function takes data and returns data. It computes
   how money should flow, but doesn't change anything. The caller
   (operations layer) uses the allocation result to update state or
   present information to users.")

;; ============================================================
;; Core Waterfall Function
;; ============================================================

(defn- due-date-comparator
  "Compare two items by due date for sorting.
   Returns negative if a < b, positive if a > b, zero if equal."
  [a b]
  (let [date-a (or (:fee/due-date a) (:installment/due-date a))
        date-b (or (:fee/due-date b) (:installment/due-date b))]
    (compare (.getTime ^java.util.Date date-a)
             (.getTime ^java.util.Date date-b))))

(defn waterfall
  "Allocate payment amount to fees and installments using waterfall logic.

   Input:
   - fees: sequence of maps with :fee/id, :fee/amount, :fee/due-date
   - installments: sequence of maps with :installment/id, :installment/seq,
                   :installment/due-date, :installment/principal-due, :installment/profit-due
   - total-payments: bigdec amount to allocate

   Returns:
   {:allocations [...] :credit-balance 0M}

   Allocation map structure:
   - For fees: {:type :fee :id fee-id :amount paid-amount}
   - For installments: {:type :installment :id inst-id :seq seq
                       :profit-paid amount :principal-paid amount}

   Waterfall order:
   1. ALL items (fees AND installments) sorted by due-date, oldest first
   2. Fees before installments on same due-date (stable sort)
   3. Within installment: profit-due before principal-due

   Any remaining amount after all fees and installments are paid becomes
   credit-balance (overpayment).

   Examples:

   ;; Fee due Jan 1, Installment due Feb 1 - fee paid first
   (waterfall
     [{:fee/id fee1-id :fee/amount 1000M :fee/due-date #inst \"2024-01-01\"}]
     [{:installment/id inst1-id :installment/seq 1 :installment/due-date #inst \"2024-02-01\"
       :installment/principal-due 100000M :installment/profit-due 10000M}]
     50000M)
   => {:allocations [{:type :fee :id fee1-id :amount 1000M}
                     {:type :installment :id inst1-id :seq 1
                      :profit-paid 10000M :principal-paid 39000M}]
       :credit-balance 0M}

   ;; Fee due AFTER installment - installment paid first!
   (waterfall
     [{:fee/id fee1-id :fee/amount 1000M :fee/due-date #inst \"2024-03-01\"}]
     [{:installment/id inst1-id :installment/seq 1 :installment/due-date #inst \"2024-02-01\"
       :installment/principal-due 100000M :installment/profit-due 10000M}]
     50000M)
   => {:allocations [{:type :installment :id inst1-id :seq 1
                      :profit-paid 10000M :principal-paid 40000M}
                     {:type :fee :id fee1-id :amount 0M}]
       :credit-balance 0M}
   ;; Installment paid first because it's due before the fee!"

  [fees installments total-payments]

  ;; Tag items with their type for processing
  (let [tagged-fees (map #(assoc % :_type :fee) fees)
        tagged-installments (map #(assoc % :_type :installment) installments)

        ;; Merge and sort ALL items by due-date
        ;; Fees come first when same due-date (concat order preserved by stable sort)
        all-items (sort due-date-comparator (concat tagged-fees tagged-installments))

        ;; Allocate through all items in due-date order
        {final-allocations :allocations
         final-remaining :remaining}
        (reduce
          (fn [{:keys [allocations remaining]} item]
            (case (:_type item)
              ;; Fee allocation
              :fee
              (let [fee-amount (:fee/amount item)
                    payment-to-fee (min remaining fee-amount)]
                {:allocations (conj allocations
                                    {:type :fee
                                     :id (:fee/id item)
                                     :amount payment-to-fee})
                 :remaining (- remaining payment-to-fee)})

              ;; Installment allocation (profit before principal)
              :installment
              (let [profit-due (:installment/profit-due item)
                    principal-due (:installment/principal-due item)

                    ;; Pay profit first
                    payment-to-profit (min remaining profit-due)
                    remaining-after-profit (- remaining payment-to-profit)

                    ;; Then pay principal
                    payment-to-principal (min remaining-after-profit principal-due)
                    remaining-after-principal (- remaining-after-profit payment-to-principal)]

                {:allocations (conj allocations
                                    {:type :installment
                                     :id (:installment/id item)
                                     :seq (:installment/seq item)
                                     :profit-paid payment-to-profit
                                     :principal-paid payment-to-principal})
                 :remaining remaining-after-principal})))
          {:allocations []
           :remaining total-payments}
          all-items)]

    ;; Return allocations + credit balance
    {:allocations final-allocations
     :credit-balance final-remaining}))

;; ============================================================
;; Allocation Queries
;; ============================================================

(defn allocation-for-fee
  "Find allocation for specific fee in waterfall result.

   Returns allocation map or nil if fee received no payment.

   Usage:
     (def result (waterfall fees installments 100000M))
     (allocation-for-fee result fee-id)
     ;; => {:type :fee :id fee-id :amount 1000M}"
  [{:keys [allocations]} fee-id]
  (->> allocations
       (filter #(= :fee (:type %)))
       (filter #(= fee-id (:id %)))
       first))

(defn allocation-for-installment
  "Find allocation for specific installment in waterfall result.

   Returns allocation map or nil if installment received no payment.

   Usage:
     (def result (waterfall fees installments 100000M))
     (allocation-for-installment result inst-id)
     ;; => {:type :installment :id inst-id :seq 1
           :profit-paid 10000M :principal-paid 50000M}"
  [{:keys [allocations]} installment-id]
  (->> allocations
       (filter #(= :installment (:type %)))
       (filter #(= installment-id (:id %)))
       first))

(defn total-allocated
  "Sum of all allocated amounts (excluding credit balance).

   Usage:
     (def result (waterfall fees installments 100000M))
     (total-allocated result)
     ;; => 95000M (if 5000M is credit-balance)"
  [{:keys [allocations]}]
  (reduce
    (fn [sum alloc]
      (case (:type alloc)
        :fee (+ sum (:amount alloc))
        :installment (+ sum (:profit-paid alloc) (:principal-paid alloc))))
    0M
    allocations))

(defn verify-waterfall
  "Verify waterfall allocations sum correctly.

   Returns map with:
   - :valid? true if allocations + credit = total
   - :total-in the input amount
   - :total-allocated sum of allocations
   - :credit-balance remaining amount
   - :difference any discrepancy (should be 0)

   This is a sanity check for testing and debugging.

   Usage:
     (def result (waterfall fees installments 100000M))
     (verify-waterfall result 100000M)
     ;; => {:valid? true :total-in 100000M
           :total-allocated 95000M :credit-balance 5000M :difference 0M}"
  [result total-in]
  (let [allocated (total-allocated result)
        credit (:credit-balance result)
        total-out (+ allocated credit)
        difference (- total-in total-out)]
    {:valid? (zero? difference)
     :total-in total-in
     :total-allocated allocated
     :credit-balance credit
     :difference difference}))

;; ============================================================
;; Development Examples
;; ============================================================

(comment
  ;; Example 1: Basic allocation - fee due before installment
  (def fees [{:fee/id (random-uuid)
              :fee/amount 2500M
              :fee/due-date #inst "2024-01-01"}])

  (def installments
    [{:installment/id (random-uuid)
      :installment/seq 1
      :installment/due-date #inst "2024-02-01"
      :installment/principal-due 100000M
      :installment/profit-due 10000M}
     {:installment/id (random-uuid)
      :installment/seq 2
      :installment/due-date #inst "2024-03-01"
      :installment/principal-due 100000M
      :installment/profit-due 10000M}])

  ;; Payment covers fee + first installment profit + part of principal
  (def result (waterfall fees installments 50000M))
  ;; => {:allocations [{:type :fee :id ... :amount 2500M}
  ;;                   {:type :installment :id ... :seq 1
  ;;                    :profit-paid 10000M :principal-paid 37500M}
  ;;                   {:type :installment :id ... :seq 2
  ;;                    :profit-paid 0M :principal-paid 0M}]
  ;;     :credit-balance 0M}

  (verify-waterfall result 50000M)
  ;; => {:valid? true ...}


  ;; Example 2: Overpayment
  (def result2 (waterfall fees installments 1000000M))
  ;; Pays all fees and all installments, remainder goes to credit-balance
  (:credit-balance result2)
  ;; => 777500M


  ;; Example 3: Fee due AFTER installments (realistic for management fees)
  ;; Fee due in October, installments due Aug/Sep
  (def late-fee [{:fee/id (random-uuid)
                  :fee/amount 5000M
                  :fee/due-date #inst "2024-10-01"}])

  (def early-installments
    [{:installment/id (random-uuid)
      :installment/seq 1
      :installment/due-date #inst "2024-08-01"
      :installment/principal-due 0M
      :installment/profit-due 10000M}
     {:installment/id (random-uuid)
      :installment/seq 2
      :installment/due-date #inst "2024-09-01"
      :installment/principal-due 0M
      :installment/profit-due 10000M}])

  (def result3 (waterfall late-fee early-installments 15000M))
  ;; Installments paid first (they're due earlier), then fee
  ;; => {:allocations [{:type :installment :id ... :seq 1
  ;;                    :profit-paid 10000M :principal-paid 0M}
  ;;                   {:type :installment :id ... :seq 2
  ;;                    :profit-paid 5000M :principal-paid 0M}
  ;;                   {:type :fee :id ... :amount 0M}]
  ;;     :credit-balance 0M}
  ;; Fee gets nothing because payment exhausted on earlier-due installments!


  ;; Example 4: Multiple fees (same due-date, stable sort)
  (def multiple-fees
    [{:fee/id (random-uuid)
      :fee/amount 1000M
      :fee/due-date #inst "2024-01-01"}
     {:fee/id (random-uuid)
      :fee/amount 2000M
      :fee/due-date #inst "2024-01-01"}])

  (def result4 (waterfall multiple-fees [] 1500M))
  ;; First fee gets 1000M, second fee gets 500M
  ;; => {:allocations [{:type :fee :id ... :amount 1000M}
  ;;                   {:type :fee :id ... :amount 500M}]
  ;;     :credit-balance 0M}

  )
