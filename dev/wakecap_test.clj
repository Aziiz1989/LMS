(ns wakecap-test
  "Test scenario using real WakeCap customer data from screenshots.

   Usage in REPL:
     (require '[wakecap-test :refer :all])
     (setup!)
     (show-facility-state)
     (show-contract-state)

   After loading payments:
     (apply-payments! payments-data)
     (show-contract-state)"
  (:require [clojure.string :as str]
            [lms.db :as db]
            [lms.contract :as contract]
            [lms.operations :as ops]
            [datomic.client.api :as d]))

;; ═══════════════════════════════════════════════════════════════
;; IDs (fixed for reproducibility)
;; ═══════════════════════════════════════════════════════════════

(def facility-id #uuid "f1283621-0000-0000-0000-000000000001")
(def contract-id #uuid "c1283621-0000-0000-0000-000000000001")

;; ═══════════════════════════════════════════════════════════════
;; Connection (memoized)
;; ═══════════════════════════════════════════════════════════════

(defonce conn (atom nil))

(defn get-conn []
  (or @conn
      (reset! conn (db/get-connection))))

(defn current-db []
  (d/db (get-conn)))

;; ═══════════════════════════════════════════════════════════════
;; Setup: Create facility and contract from screenshot data
;; ═══════════════════════════════════════════════════════════════

(defn setup!
  "Initialize database with WakeCap facility and contract.

   Data from screenshots:
   - Facility: PIP-1283621, limit 10M SAR, funder SKFH
   - Contract: 3290-H-2025, principal 9,652,509.65 SAR
   - Disbursement: 07/07/2025
   - Structure: 11 profit-only + 1 amortizing (11*p, 1*a)
   - Rate: 16% flat annual
   - Commodity: Palm Oil from Nafaes
   - Management fee: 277,509.66 SAR (due 3 months after start: 07/10/2025)"
  []
  ;; Reset database for clean test
  (db/delete-database!)
  (reset! conn nil)
  (let [conn (get-conn)]
    (db/install-schema conn)
    (println "✓ Database ready with schema")

    ;; ─────────────────────────────────────────────────────────────
    ;; Create Facility (from LOS import - PIP-1283621)
    ;; ─────────────────────────────────────────────────────────────
    (ops/create-facility conn
      {:id facility-id
       :external-id "PIP-1283621"
       :customer-id "7016779188"
       :customer-name "WakeCap Saudi Information Systems Technology Company"
       :limit 10000000M
       :funder "SKFH"}
      "system-import")
    (println "✓ Facility PIP-1283621 created")

    ;; ─────────────────────────────────────────────────────────────
    ;; Calculate installment schedule
    ;; Disbursement: 07/07/2025
    ;; First installment due: 07/08/2025 (1 month after)
    ;; Last installment due: 07/07/2026 (12 months)
    ;; ─────────────────────────────────────────────────────────────
    (let [principal 9652509.65M
          ;; 16% annual flat rate = monthly profit of principal * 16% / 12
          monthly-profit 128700.13M
          start-date #inst "2025-07-07"  ;; Disbursement date

          ;; Generate 12 installments (11 profit-only, 1 amortizing)
          ;; Due on 7th of each month
          installments
          (for [seq (range 1 13)
                :let [;; Due date: 7th of each month starting August 2025
                      year (if (> (+ 7 seq) 12) 2026 2025)
                      month (if (> (+ 7 seq) 12)
                              (- (+ 7 seq) 12)
                              (+ 7 seq))
                      due-date (java.util.Date. (- year 1900) (dec month) 7)
                      ;; Installments 1-11: profit only, 12: full principal + profit
                      principal-due (if (= seq 12) principal 0M)]]
            {:installment/id (java.util.UUID/randomUUID)
             :installment/seq seq
             :installment/due-date due-date
             :installment/principal-due principal-due
             :installment/profit-due monthly-profit
             :installment/remaining-principal principal})

          ;; Management fee with VAT - due 3 months after start (07/10/2025)
          management-fee
          {:fee/id (java.util.UUID/randomUUID)
           :fee/type :management
           :fee/amount 277509.66M
           :fee/due-date #inst "2025-10-07"}]

      ;; ─────────────────────────────────────────────────────────────
      ;; Board the contract with enriched data
      ;; ─────────────────────────────────────────────────────────────
      (ops/board-contract conn
        {:contract/id contract-id
         :contract/external-id "3290-H-2025"
         :contract/customer-name "WakeCap Saudi Information Systems Technology Company"
         :contract/customer-id "7016779188"
         :contract/start-date start-date
         :contract/principal principal
         :contract/security-deposit 0M
         ;; Facility link
         :contract/facility [:facility/id facility-id]
         ;; Commodity (Murabaha)
         :contract/commodity-description "Palm Oil"
         :contract/commodity-quantity 2591.567M
         :contract/commodity-unit-price 3724.585M
         :contract/commodity-vendor "Nafaes"
         ;; Banking
         :contract/disbursement-iban "SA2420000002680766869940"
         :contract/disbursement-bank "Riyad Bank"
         :contract/virtual-iban "SA1030100809000012836211"}
        [management-fee]
        installments
        "system-import")

      (println "✓ Contract 3290-H-2025 created with 12 installments")
      (println "\nReady! Try:")
      (println "  (show-facility-state)")
      (println "  (show-contract-state)"))))

;; ═══════════════════════════════════════════════════════════════
;; Display functions
;; ═══════════════════════════════════════════════════════════════

(defn show-facility-state
  "Display facility state with utilization."
  []
  (let [state (contract/facility-state (current-db) facility-id)]
    (println "\n═══════════════════════════════════════════════════════════")
    (println "FACILITY STATE")
    (println "═══════════════════════════════════════════════════════════")
    (println "External ID:  " (:external-id state))
    (println "Customer:     " (:customer-name state))
    (println "CR Number:    " (:customer-id state))
    (println "Funder:       " (:funder state))
    (println "Status:       " (:status state))
    (println "─────────────────────────────────────────────────────────────")
    (println "Limit:        " (format "%,.2f SAR" (double (:limit state))))
    (println "Utilization:  " (format "%,.2f SAR" (double (:utilization state))))
    (println "Available:    " (format "%,.2f SAR" (double (:available state))))
    (println "─────────────────────────────────────────────────────────────")
    (println "Contracts:    " (count (:contracts state)))
    (doseq [c (:contracts state)]
      (println "  •" (:external-id c) "-"
               (format "%,.2f SAR" (double (:principal c)))
               "(" (name (:status c)) ")"))
    state))

(defn show-contract-state
  "Display contract state with schedule."
  []
  (let [state (contract/contract-state (current-db) contract-id (java.util.Date.))]
    (println "\n═══════════════════════════════════════════════════════════")
    (println "CONTRACT STATE")
    (println "═══════════════════════════════════════════════════════════")
    (println "External ID:  " (get-in state [:contract :external-id]))
    (println "Customer:     " (get-in state [:contract :customer-name]))
    (println "Status:       " (get-in state [:contract :status]))
    (println "Start Date:   " (get-in state [:contract :start-date]))
    (println "Maturity:     " (get-in state [:contract :maturity-date]) "(derived)")

    (println "\n─── CONTRACT TERMS ───")
    (println "Principal:    " (format "%,.2f SAR" (double (get-in state [:contract :principal]))))
    (println "Facility:     " (get-in state [:contract :facility-id]))

    (println "\n─── COMMODITY (MURABAHA) ───")
    (let [c (get-in state [:contract :commodity])]
      (println "Description:  " (:description c))
      (println "Quantity:     " (:quantity c))
      (println "Unit Price:   " (:unit-price c))
      (println "Vendor:       " (:vendor c)))

    (println "\n─── BANKING ───")
    (println "Disbursement: " (get-in state [:contract :disbursement-iban]))
    (println "              " (get-in state [:contract :disbursement-bank]))
    (println "Collection:   " (get-in state [:contract :virtual-iban]))

    (println "\n─── FEES ───")
    (doseq [fee (:fees state)]
      (println (format "  %-12s %12.2f SAR  Paid: %12.2f  Status: %s"
                       (name (:type fee))
                       (double (:amount fee))
                       (double (:paid fee))
                       (name (:status fee)))))

    (println "\n─── INSTALLMENTS ───")
    (println "  #   Due Date      Principal        Profit         Paid    Status")
    (println "  ─── ──────────── ──────────────── ───────────── ─────────── ───────────")
    (doseq [inst (:installments state)]
      (println (format "  %2d  %tF  %14.2f  %12.2f  %10.2f  %s"
                       (:seq inst)
                       (:due-date inst)
                       (double (:principal-due inst))
                       (double (:profit-due inst))
                       (double (:total-paid inst))
                       (name (:status inst)))))

    (println "\n─── TOTALS ───")
    (println (format "Total Fees Due:      %14.2f SAR" (double (:total-fees-due state))))
    (println (format "Total Principal Due: %14.2f SAR" (double (:total-principal-due state))))
    (println (format "Total Profit Due:    %14.2f SAR" (double (:total-profit-due state))))
    (println "                     ──────────────────")
    (let [total (+ (:total-fees-due state)
                   (:total-principal-due state)
                   (:total-profit-due state))]
      (println (format "Total Receivable:    %14.2f SAR" (double total))))
    (println)
    (println (format "Total Paid:          %14.2f SAR"
                     (double (+ (:total-fees-paid state)
                               (:total-principal-paid state)
                               (:total-profit-paid state)))))
    (println (format "Outstanding:         %14.2f SAR" (double (:total-outstanding state))))
    (println (format "Credit Balance:      %14.2f SAR" (double (:credit-balance state))))
    state))

;; ═══════════════════════════════════════════════════════════════
;; Payments Data (from bank statement)
;; ═══════════════════════════════════════════════════════════════
;;
;; Timeline (due dates):
;;   Jul 7, 2025  - Disbursement (contract start)
;;   Aug 7, 2025  - Installment 1 due (profit only: 128,700.13)
;;   Sep 7, 2025  - Installment 2 due (profit only: 128,700.13)
;;   Oct 7, 2025  - Management fee due (277,509.66) + Installment 3 (128,700.13)
;;   Nov 7, 2025  - Installment 4 due
;;   ...
;;
;; Key insight: Management fee was paid in 3 parts (92,503.22 each).
;; But the first two "MGMT-FEE" payments went to PROFIT because
;; installments 1 & 2 were due before the fee (Oct 7)!
;; The waterfall allocates by due-date order.

(def payments-data
  "Actual payment records from bank statement.
   Note: Labels are what the customer intended, but waterfall
   allocates by due-date regardless of label.

   The 3 'Management Fee' payments (92,503.22 x 3 = 277,509.66)
   don't all go to the fee - the first ones go to earlier-due installments!"
  [{:seq 1 :date "2025-07-24" :amount 92503.22M  :reference "PAY-22" :label "Management Fee" :bank "Riyad Bank"}
   {:seq 2 :date "2025-08-07" :amount 128700.13M :reference "PAY-27" :label "Transfer"       :bank "Riyad Bank"}
   {:seq 3 :date "2025-08-24" :amount 92503.22M  :reference "PAY-29" :label "Management Fee" :bank "Riyad Bank"}
   {:seq 4 :date "2025-09-08" :amount 128700.13M :reference "PAY-34" :label "Transfer"       :bank "Al Rajhi Bank"}
   {:seq 5 :date "2025-09-29" :amount 92503.22M  :reference "PAY-36" :label "Management Fee" :bank "Riyad Bank"}
   {:seq 6 :date "2025-10-06" :amount 128700.13M :reference "PAY-39" :label "Transfer"       :bank "Riyad Bank"}
   {:seq 7 :date "2025-11-05" :amount 128700.13M :reference "PAY-42" :label "Transfer"       :bank "Al Rajhi Bank"}
   {:seq 8 :date "2026-01-12" :amount 128700.13M :reference "PAY-46" :label "Transfer"       :bank "Riyad Bank"}])

;; ═══════════════════════════════════════════════════════════════
;; Step-by-step Event Replay
;; ═══════════════════════════════════════════════════════════════
;;
;; Each function represents one event in chronological order.
;; Run them in sequence to see how state evolves.

(defn event-0-setup!
  "Initialize: Create facility and board contract.
   This is the starting point - disbursement happened."
  []
  (setup!)
  (println "\n═══════════════════════════════════════════════════════════")
  (println "EVENT 0: Contract boarded (disbursement: Jul 7, 2025)")
  (println "═══════════════════════════════════════════════════════════")
  (println "Schedule:")
  (println "  • Installments 1-11: Profit only (128,700.13 each)")
  (println "  • Installment 12: Principal (9,652,509.65) + Profit")
  (println "  • Management fee: 277,509.66 (due Oct 7)")
  (println "\nNext: (event-1-payment!)"))

(defn- parse-date
  "Parse date string (YYYY-MM-DD) to java.util.Date."
  [date-str]
  (let [[year month day] (map #(Integer/parseInt %) (str/split date-str #"-"))]
    (java.util.Date. (- year 1900) (dec month) day)))

(defn- record-event-payment!
  "Helper to record a payment and show allocation result."
  [payment-seq]
  (let [payment (nth payments-data (dec payment-seq))
        {:keys [date amount reference label]} payment
        original-date (parse-date date)]
    (println (format "\n═══════════════════════════════════════════════════════════"))
    (println (format "EVENT %d: Payment received" payment-seq))
    (println (format "═══════════════════════════════════════════════════════════"))
    (println (format "Date:      %s" date))
    (println (format "Amount:    %,.2f SAR" (double amount)))
    (println (format "Reference: %s" reference))
    (println (format "Label:     %s (customer's intent)" label))
    (println)

    ;; Record the payment with business date for proper tracking
    (ops/record-payment (get-conn) contract-id amount original-date reference "system-import"
                        :note (format "Label: %s" label))

    ;; Show where it actually went
    (let [state (contract/contract-state (current-db) contract-id (java.util.Date.))
          fees (:fees state)
          installments (:installments state)]
      (println "─── ALLOCATION RESULT ───")
      (println "Fees:")
      (doseq [fee fees]
        (println (format "  %-12s  Due: %tF  Paid: %,.2f / %,.2f  %s"
                         (name (:type fee))
                         (:due-date fee)
                         (double (:paid fee))
                         (double (:amount fee))
                         (name (:status fee)))))
      (println "\nInstallments (showing first 6):")
      (doseq [inst (take 6 installments)]
        (println (format "  #%2d  Due: %tF  Paid: %,.2f / %,.2f  %s"
                         (:seq inst)
                         (:due-date inst)
                         (double (:total-paid inst))
                         (double (+ (:principal-due inst) (:profit-due inst)))
                         (name (:status inst)))))
      (println "\nTotals:")
      (println (format "  Outstanding: %,.2f SAR" (double (:total-outstanding state))))
      (println (format "  Credit:      %,.2f SAR" (double (:credit-balance state)))))

    (when (< payment-seq (count payments-data))
      (println (format "\nNext: (event-%d-payment!)" (inc payment-seq))))))

(defn event-1-payment!
  "Jul 24: First 'Management Fee' payment (92,503.22).

   SURPRISE: This goes to Installment 1 profit, NOT the fee!
   Because Inst 1 is due Aug 7, before fee due Oct 7."
  []
  (record-event-payment! 1))

(defn event-2-payment!
  "Aug 7: Transfer payment (128,700.13).

   Completes Inst 1 profit and starts on Inst 2."
  []
  (record-event-payment! 2))

(defn event-3-payment!
  "Aug 24: Second 'Management Fee' payment (92,503.22).

   Still goes to installment profit (Inst 2), not the fee!"
  []
  (record-event-payment! 3))

(defn event-4-payment!
  "Sep 8: Transfer payment (128,700.13).

   Completes Inst 2 and starts on next due items."
  []
  (record-event-payment! 4))

(defn event-5-payment!
  "Sep 29: Third 'Management Fee' payment (92,503.22).

   By now, Inst 1 & 2 are paid. This should finally
   start paying the actual management fee (due Oct 7)."
  []
  (record-event-payment! 5))

(defn event-6-payment!
  "Oct 6: Transfer payment (128,700.13)."
  []
  (record-event-payment! 6))

(defn event-7-payment!
  "Nov 5: Transfer payment (128,700.13)."
  []
  (record-event-payment! 7))

(defn event-8-payment!
  "Jan 12: Transfer payment (128,700.13).

   Note: Gap in Dec - no payment received that month."
  []
  (record-event-payment! 8))

(defn replay-all!
  "Run all 8 payment events in sequence."
  []
  (event-0-setup!)
  (doseq [n (range 1 9)]
    (Thread/sleep 100)  ;; Small pause for readability
    ((resolve (symbol (str "event-" n "-payment!"))))))

;; ═══════════════════════════════════════════════════════════════
;; Payment functions (batch mode)
;; ═══════════════════════════════════════════════════════════════

(defn apply-payment!
  "Record a single payment.

   Args:
   - amount: Payment amount (bigdec)
   - reference: Bank reference string
   - date: Payment date string (optional, defaults to now)"
  [amount reference & {:keys [date]}]
  (ops/record-payment (get-conn) contract-id amount
                      (if date (parse-date date) (java.util.Date.))
                      reference "system-import")
  (println "✓ Payment recorded:" reference "-" (format "%,.2f SAR" (double amount))))

(defn apply-payments!
  "Apply multiple payments from a vector of payment maps.

   Each payment map should have:
   {:amount 123456.78M :reference \"PAY-001\" :date \"2025-01-15\"}"
  [payments]
  (doseq [{:keys [amount reference date]} payments]
    (apply-payment! amount reference :date date))
  (println "\n✓ All payments applied. Run (show-contract-state) to see results."))

;; ═══════════════════════════════════════════════════════════════
;; Quick helpers
;; ═══════════════════════════════════════════════════════════════

(defn preview-payment
  "Preview what would happen if we apply a payment."
  [amount]
  (let [preview (ops/preview-payment (get-conn) contract-id amount)]
    (println "\n─── PAYMENT PREVIEW ───")
    (println "Amount:" (format "%,.2f SAR" (double amount)))
    (println "\nBefore:")
    (println "  Outstanding:" (format "%,.2f" (double (get-in preview [:before :total-outstanding]))))
    (println "\nAfter:")
    (println "  Outstanding:" (format "%,.2f" (double (get-in preview [:after :total-outstanding]))))
    (println "  Credit Balance:" (format "%,.2f" (double (get-in preview [:after :credit-balance]))))
    (println "\nAllocations:")
    (doseq [change (:changes preview)]
      (println " " (:description change) "-" (format "%,.2f" (double (:amount change)))))
    preview))

(defn transactions
  "Show all transactions for the contract."
  []
  (let [txs (contract/get-events (current-db) contract-id)]
    (println "\n─── TRANSACTIONS ───")
    (doseq [tx txs]
      (println (format "%tF  %-15s  %12s  %s"
                       (:date tx)
                       (name (:event-type tx))
                       (if (:amount tx) (format "%,.2f" (double (:amount tx))) "-")
                       (or (:reference tx) ""))))
    txs))

;; ═══════════════════════════════════════════════════════════════
;; REPL bootstrap
;; ═══════════════════════════════════════════════════════════════

(comment
  ;; ─────────────────────────────────────────────────────────────
  ;; RECOMMENDED: Step-by-step event replay
  ;; ─────────────────────────────────────────────────────────────

  ;; Start with setup (creates facility + contract)
  (event-0-setup!)

  ;; Then run each payment event in order:
  (event-1-payment!)   ;; Jul 24: "Mgmt Fee" 92,503.22 → goes to Inst 1!
  (event-2-payment!)   ;; Aug 7:  Transfer 128,700.13
  (event-3-payment!)   ;; Aug 24: "Mgmt Fee" 92,503.22 → goes to Inst 2!
  (event-4-payment!)   ;; Sep 8:  Transfer 128,700.13
  (event-5-payment!)   ;; Sep 29: "Mgmt Fee" 92,503.22 → finally to fee
  (event-6-payment!)   ;; Oct 6:  Transfer 128,700.13
  (event-7-payment!)   ;; Nov 5:  Transfer 128,700.13
  (event-8-payment!)   ;; Jan 12: Transfer 128,700.13

  ;; Or run all events at once:
  (replay-all!)

  ;; ─────────────────────────────────────────────────────────────
  ;; View state at any point
  ;; ─────────────────────────────────────────────────────────────

  (show-facility-state)
  (show-contract-state)
  (transactions)

  ;; ─────────────────────────────────────────────────────────────
  ;; Preview what-if scenarios
  ;; ─────────────────────────────────────────────────────────────

  ;; What if customer pays the full remaining balance?
  (preview-payment 9652509.65M)

  ;; ─────────────────────────────────────────────────────────────
  ;; Batch mode (alternative to step-by-step)
  ;; ─────────────────────────────────────────────────────────────

  (setup!)
  (apply-payments! payments-data)
  (show-contract-state)
  )
