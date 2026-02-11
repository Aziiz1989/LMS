(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [lms.core :as core]
            [lms.db :as db]
            [lms.waterfall :as waterfall]
            [lms.contract :as contract]
            [lms.operations :as ops]
            [datomic.client.api :as d]
            [taoensso.timbre :as log]))

(set-refresh-dirs "src" "dev")

(defonce server (atom nil))

(defn start []
  (when-not @server
    (reset! server (core/start-server :port 3001))
    (log/info "Server started at http://localhost:3001")))

(defn stop []
  (when @server
    (.stop @server)
    (reset! server nil)
    (log/info "Server stopped")))

(defn restart []
  (stop)
  (start))

(defn reset
  "Stop server, reload all changed namespaces, restart server.
   The gold standard one-command dev reload."
  []
  (stop)
  (refresh :after 'user/start))

(comment
  (start)
  (stop)
  (reset)     ;; <-- use this: reloads changed code + restarts server
  (restart)   ;; <-- use this only if reset fails (skips code reload)
  )

;; ============================================================
;; Week 1 Verification
;; ============================================================

(defn verify-week-1
  "Verify Week 1 implementation: schema, waterfall, contract-state.

   This creates a test contract, records payments, and verifies that
   contract-state correctly derives all balances and statuses.

   Returns map with:
   - :success? true if verification passed
   - :contract-id the test contract ID
   - :state the computed contract state
   - :checks map of verification checks"
  []
  (log/info "Starting Week 1 verification...")

  ;; 1. Setup database
  (log/info "1. Creating database and installing schema...")
  (let [conn (db/get-connection)]
    (db/install-schema conn)
    (log/info "   ✓ Schema installed")

    ;; 2. Create test contract
    (log/info "2. Creating test contract...")
    (let [contract-id (random-uuid)
          fee-id (random-uuid)
          inst-1-id (random-uuid)
          inst-2-id (random-uuid)]

      (d/transact conn
                  {:tx-data [{:db/id "contract-temp"
                              :contract/id contract-id
                              :contract/external-id "VERIFY-001"
                              :contract/customer-name "Test Customer Co."
                              :contract/customer-id "CR-999999"
                              :contract/disbursed-at #inst "2024-01-02"
                              :contract/start-date #inst "2024-01-01"
                              :contract/principal 1200000M
                              :contract/security-deposit 60000M}

                             ;; Fee
                             {:fee/id fee-id
                              :fee/contract "contract-temp"
                              :fee/type :management
                              :fee/amount 5000M
                              :fee/due-date #inst "2024-01-01"}

                             ;; Installments
                             {:installment/id inst-1-id
                              :installment/contract  "contract-temp"
                              :installment/seq 1
                              :installment/due-date #inst "2024-01-31"
                              :installment/principal-due 100000M
                              :installment/profit-due 10000M}

                             {:installment/id inst-2-id
                              :installment/contract  "contract-temp"
                              :installment/seq 2
                              :installment/due-date #inst "2024-02-28"
                              :installment/principal-due 100000M
                              :installment/profit-due 10000M}

                             ;; Boarding transaction
                             {:db/id "datomic.tx"
                              :tx/type :boarding
                              :tx/contract  "contract-temp"
                              :tx/author "verify-script"}]})

      (log/info "   ✓ Contract created: VERIFY-001")

      ;; 3. Record payment
      (log/info "3. Recording payment...")
      (d/transact conn
                  {:tx-data [{:db/id "datomic.tx"
                              :tx/type :payment
                              :tx/contract [:contract/id contract-id]
                              :tx/amount 50000M
                              :tx/reference "VERIFY-PAY-001"
                              :tx/author "verify-script"}]})

      (log/info "   ✓ Payment recorded: 50,000 SAR")

      ;; 4. Compute contract state
      (log/info "4. Computing contract state...")
      (let [db (d/db conn)
            state (contract/contract-state db contract-id (java.util.Date.))]

        (log/info "   ✓ Contract state computed")

        ;; 5. Verify results
        (log/info "5. Verifying results...")

        (let [checks {:contract-exists? (not (nil? (:contract state)))

                      :fees-count (= 1 (count (:fees state)))
                      :fee-paid (let [fee (first (:fees state))]
                                  (= 5000M (:paid fee)))
                      :fee-status (let [fee (first (:fees state))]
                                    (= :paid (:status fee)))

                      :installments-count (= 2 (count (:installments state)))
                      :inst-1-status (let [inst (first (:installments state))]
                                       (= :partial (:status inst)))
                      :inst-1-profit-paid (let [inst (first (:installments state))]
                                            (= 10000M (:profit-paid inst)))
                      :inst-1-principal-paid (let [inst (first (:installments state))]
                                               (= 35000M (:principal-paid inst)))
                      :inst-2-status (let [inst (second (:installments state))]
                                       (= :scheduled (:status inst)))
                      :inst-2-paid (let [inst (second (:installments state))]
                                     (and (= 0M (:profit-paid inst))
                                          (= 0M (:principal-paid inst))))

                      :total-outstanding-correct (= (+ 1200000M 20000M 0M -50000M)
                                                    (:total-outstanding state))

                      :credit-balance-zero (= 0M (:credit-balance state))

                      :transactions-count (= 2 (count (:transactions state)))}

              all-pass? (every? true? (vals checks))]

          (doseq [[check result] checks]
            (if result
              (log/info (format "   ✓ %s" (name check)))
              (log/error (format "   ✗ %s FAILED" (name check)))))

          (if all-pass?
            (log/info "\n✅ Week 1 verification PASSED")
            (log/error "\n❌ Week 1 verification FAILED"))

          {:success? all-pass?
           :contract-id contract-id
           :state state
           :checks checks})))))

;; ============================================================
;; Week 3 Sample Data
;; ============================================================

(defn create-sample-contracts
  "Create sample contracts for UI testing.

   Creates 3 contracts with varying states:
   1. New contract with no payments
   2. Contract with partial payments
   3. Fully paid contract

   Returns vector of contract IDs."
  []
  (log/info "Creating sample contracts...")
  (let [conn (db/get-connection)]
    (db/install-schema conn)

    ;; Contract 1: New contract, no payments
    (let [contract-id-1 (random-uuid)]
      (ops/board-contract conn
                          {:contract/id contract-id-1
                           :contract/external-id "SAMPLE-001"
                           :contract/customer-name "ABC Trading Company"
                           :contract/customer-id "CR-100001"
                           :contract/disbursed-at #inst "2024-01-02"
                           :contract/start-date #inst "2024-01-01"
                           :contract/principal 1200000M
                           :contract/security-deposit 60000M}
                          [{:fee/id (random-uuid)
                            :fee/type :management
                            :fee/amount 5000M
                            :fee/due-date #inst "2024-01-01"}]
                          (for [i (range 1 13)]
                            {:installment/id (random-uuid)
                             :installment/seq i
                             :installment/due-date (java.util.Date/from
                                                    (java.time.Instant/parse
                                                     (format "2024-%02d-01T00:00:00Z" i)))
                             :installment/principal-due 100000M
                             :installment/profit-due 15000M})
                          "system")
      (log/info "Created SAMPLE-001" {:id contract-id-1}))

    ;; Contract 2: Partial payments
    (let [contract-id-2 (random-uuid)]
      (ops/board-contract conn
                          {:contract/id contract-id-2
                           :contract/external-id "SAMPLE-002"
                           :contract/customer-name "XYZ Manufacturing Ltd"
                           :contract/customer-id "CR-100002"
                           :contract/disbursed-at #inst "2024-01-02"
                           :contract/start-date #inst "2023-06-01"
                           :contract/principal 800000M
                           :contract/security-deposit 40000M}
                          [{:fee/id (random-uuid)
                            :fee/type :management
                            :fee/amount 3000M
                            :fee/due-date #inst "2023-06-01"}]
                          (for [i (range 1 13)]
                            {:installment/id (random-uuid)
                             :installment/seq i
                             :installment/due-date (java.util.Date/from
                                                    (.toInstant
                                                     (.atStartOfDay
                                                      (.plusMonths (java.time.LocalDate/of 2023 6 1) (dec i))
                                                      java.time.ZoneOffset/UTC)))
                             :installment/principal-due 66666M
                             :installment/profit-due 10000M})
                          "system")

      ;; Record some payments
      (ops/record-payment conn contract-id-2 3000M "PAY-001" "system")  ;; Fee
      (ops/record-payment conn contract-id-2 150000M "PAY-002" "system")  ;; 2 installments
      (ops/record-payment conn contract-id-2 150000M "PAY-003" "system")  ;; 2 more installments
      (ops/record-payment conn contract-id-2 50000M "PAY-004" "system")  ;; Partial

      (log/info "Created SAMPLE-002 with payments" {:id contract-id-2}))

    ;; Contract 3: Small contract, fully paid
    (let [contract-id-3 (random-uuid)]
      (ops/board-contract conn
                          {:contract/id contract-id-3
                           :contract/external-id "SAMPLE-003"
                           :contract/customer-name "Quick Loans Co"
                           :contract/customer-id "CR-100003"
                           :contract/disbursed-at #inst "2024-01-02"
                           :contract/start-date #inst "2024-01-01"
                           :contract/principal 300000M
                           :contract/security-deposit 15000M}
                          [{:fee/id (random-uuid)
                            :fee/type :management
                            :fee/amount 1500M
                            :fee/due-date #inst "2024-01-01"}]
                          (for [i (range 1 7)]
                            {:installment/id (random-uuid)
                             :installment/seq i
                             :installment/due-date (java.util.Date/from
                                                    (java.time.Instant/parse
                                                     (format "2024-%02d-01T00:00:00Z" i)))
                             :installment/principal-due 50000M
                             :installment/profit-due 5000M})
                          "system")

      ;; Pay everything
      (ops/record-payment conn contract-id-3 500000M "PAY-FULL" "system")

      (log/info "Created SAMPLE-003 (fully paid)" {:id contract-id-3}))

    (log/info "✅ Sample contracts created successfully")
    (log/info "View at: http://localhost:3000/contracts")))

(comment
  ;; Server management
  (start)
  (stop)
  (restart)

  ;; Week 1 verification
  (verify-week-1)

  ;; Week 3: Create sample data and start server
  (create-sample-contracts)
  (start)
  ;; Now visit: http://localhost:3000/contracts

  ;; Manual testing (namespaces already required above)

  ;; Get connection
  (def conn (db/get-connection))
  (db/install-schema conn)

  ;; Test waterfall (pure function, no DB)
  (def fees [{:fee/id (random-uuid)
              :fee/amount 1000M
              :fee/due-date #inst "2024-01-01"}])

  (def installments
    [{:installment/id (random-uuid)
      :installment/seq 1
      :installment/principal-due 100000M
      :installment/profit-due 10000M}])

  (waterfall/waterfall fees installments 50000M)
  ;; => {:allocations [...] :credit-balance 0M}

  ;; Test contract-state
  (def test-id (random-uuid))
  ;; ... create contract ...
  (contract/contract-state (d/db conn) test-id (java.util.Date.)))
