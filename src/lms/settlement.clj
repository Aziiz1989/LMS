(ns lms.settlement
  "Settlement calculation — pure derivation from contract state.

   Derives the cost to close a contract on a given date. Nothing stored,
   everything computed from existing facts. Answers: 'what does the customer
   owe to close this contract today?'

   Settlement is a read-only computation that can be called at any time
   without side effects."
  (:require [lms.dates :as dates]))

;; ============================================================
;; Installment Helpers
;; ============================================================

(defn- prev-installment
  "Find the installment immediately before target-seq in sorted-insts.
   Does not assume contiguous :seq values."
  [sorted-insts target-seq]
  (->> sorted-insts
       (filter #(< (:seq %) target-seq))
       last))

(defn- period-days-for
  "Compute the period length in calendar days for a given installment."
  [inst sorted-insts start-date]
  (let [prev (prev-installment sorted-insts (:seq inst))
        p-start (if prev (:due-date prev) start-date)]
    (dates/days-between p-start (:due-date inst))))

;; ============================================================
;; Installment Classification
;; ============================================================

(defn- classify-installments
  "Classify installments as :past, :current, or :future relative to settlement-date.

   PAST:    due-date <= settlement-date  → full profit-due counts as accrued
   CURRENT: first installment where due-date > settlement-date → pro-rata
   FUTURE:  all remaining after current → 0 accrued"
  [installments settlement-date]
  (let [sorted (sort-by :due-date installments)
        ;; Split: past = due-date <= settlement-date, rest = due-date > settlement-date
        past (filter #(not (dates/after? (:due-date %) settlement-date)) sorted)
        future-all (filter #(dates/after? (:due-date %) settlement-date) sorted)
        current (first future-all)
        future-rest (rest future-all)]
    {:past past
     :current current
     :future future-rest}))

;; ============================================================
;; Penalty Calculation
;; ============================================================

(defn- walk-forward-profit
  "Compute total profit for n-days walking forward from the settlement date.

   Mirrors the accrued-profit approach: each period's profit is divided
   equally over its calendar days. Walks through the remaining portion of
   the current period, then into future installments. If n-days extends
   beyond the schedule, extrapolates using the last period's daily rate."
  [n-days current future-insts period-days accrued-days sorted-insts start-date]
  (if (zero? n-days)
    0M
    (with-precision 20 :rounding HALF_UP
      (loop [remaining n-days
             profit   0M
             periods  (cond-> []
                        ;; Remaining portion of current period
                        (and current (pos? period-days) (> period-days accrued-days))
                        (conj {:profit-due  (:profit-due current)
                               :period-days period-days
                               :available   (- period-days accrued-days)})
                        ;; Future installments
                        true
                        (into (for [inst future-insts
                                    :let [pd (period-days-for inst sorted-insts start-date)]
                                    :when (pos? pd)]
                                {:profit-due  (:profit-due inst)
                                 :period-days pd
                                 :available   pd})))
             last-daily nil]
        (if (zero? remaining)
          profit
          (if (empty? periods)
            ;; Beyond schedule: extrapolate from last period's daily rate
            (if last-daily
              (+ profit (* last-daily remaining))
              profit)
            (let [{:keys [profit-due period-days available]} (first periods)
                  daily     (/ profit-due period-days)
                  take-days (min remaining available)]
              (recur (- remaining take-days)
                     (+ profit (* daily take-days))
                     (rest periods)
                     daily))))))))

;; ============================================================
;; Settlement Calculation
;; ============================================================

(defn calculate-settlement
  "Derive settlement amount for a contract as of a given date.

  Pure derivation from facts — nothing stored.
  Pro-rata profit in the current period is computed via linear interpolation
  of the installment's profit-due (equivalent to Actual/360 given the
  installment schedule).

  Args:
  - state: contract state from contract/contract-state
  - settlement-date: java.util.Date
  - penalty-days: int (days of profit as penalty, provided by user)
  - opts: {:manual-override bigdec} optional accrued-unpaid-profit override

  Returns settlement breakdown map. Settlement amount is floored at 0M;
  if credit exceeds obligations, :refund-due contains the positive refund amount."
  [state settlement-date penalty-days & {:keys [manual-override]}]
  (let [contract (:contract state)
        start-date (:start-date contract)
        installments (:installments state)
        sorted-insts (sort-by :seq installments)

        ;; Classify installments
        {:keys [past current future]} (classify-installments sorted-insts settlement-date)

        ;; ── Period boundaries for current installment ──
        period-start (if current
                       (let [prev (prev-installment sorted-insts (:seq current))]
                         (if prev
                           (:due-date prev)
                           start-date))
                       ;; All installments are past — use last due-date as period-start
                       (when (seq past) (:due-date (last past))))

        period-end (when current (:due-date current))

        accrued-days (if period-start
                       (dates/days-between period-start settlement-date)
                       0)

        period-days (if (and period-start period-end)
                      (dates/days-between period-start period-end)
                      0)

        ;; ── Accrued profit ──
        past-profit (reduce + 0M (map :profit-due past))

        ;; Pro-rata: profit-due / period-days × accrued-days
        ;; Algebraically equivalent to the rate roundtrip but avoids
        ;; the non-terminating division of profit-due / remaining-principal.
        current-accrued
        (if (and current (pos? period-days))
          (with-precision 20 :rounding HALF_UP
            (* (/ (:profit-due current) period-days) accrued-days))
          0M)

        accrued-profit (+ past-profit current-accrued)

        ;; ── Amounts from contract state (nil-safe) ──
        total-principal-due (or (:total-principal-due state) 0M)
        total-principal-paid (or (:total-principal-paid state) 0M)
        outstanding-principal (- total-principal-due total-principal-paid)

        total-profit-paid (or (:total-profit-paid state) 0M)
        accrued-unpaid-profit (- accrued-profit total-profit-paid)

        total-fees-due (or (:total-fees-due state) 0M)
        total-fees-paid (or (:total-fees-paid state) 0M)
        outstanding-fees (- total-fees-due total-fees-paid)

        credit-balance (or (:credit-balance state) 0M)

        ;; ── Penalty ──
        ;; Walk forward from settlement date, same per-period pro-rata
        ;; logic as accrued profit. Handles different rates per period.
        penalty-amount
        (cond
          (zero? penalty-days) 0M

          ;; Normal: walk forward from settlement date through schedule
          current
          (walk-forward-profit penalty-days current future
                               period-days accrued-days
                               sorted-insts start-date)

          ;; All past: extrapolate from last installment's daily rate
          (seq past)
          (let [last-past (last past)
                lp-days (period-days-for last-past sorted-insts start-date)]
            (if (pos? lp-days)
              (with-precision 20 :rounding HALF_UP
                (* (/ (:profit-due last-past) lp-days) penalty-days))
              0M))

          :else 0M)

        ;; ── Manual override ──
        effective-accrued-unpaid (if manual-override
                                  manual-override
                                  accrued-unpaid-profit)

        ;; ── Unearned profit (informational) ──
        total-profit-due (or (:total-profit-due state) 0M)
        unearned-profit (- total-profit-due accrued-profit)

        ;; ── Settlement amount ──
        raw-settlement (- (+ outstanding-principal
                              effective-accrued-unpaid
                              outstanding-fees
                              penalty-amount)
                           credit-balance)
        settlement-amount (max 0M raw-settlement)]

    {:outstanding-principal outstanding-principal

     :accrued-profit accrued-profit
     :profit-already-paid total-profit-paid
     :accrued-unpaid-profit accrued-unpaid-profit
     :effective-accrued-unpaid-profit effective-accrued-unpaid
     :unearned-profit unearned-profit

     :outstanding-fees outstanding-fees

     :penalty-days penalty-days
     :penalty-amount penalty-amount

     :credit-balance credit-balance

     :settlement-amount settlement-amount
     :refund-due (when (neg? raw-settlement) (- raw-settlement))

     :current-period-start period-start
     :current-period-end period-end
     :accrued-days accrued-days

     :manual-override? (boolean manual-override)}))
