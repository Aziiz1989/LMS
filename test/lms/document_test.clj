(ns lms.document-test
  "Tests for document generation, signing, and contradiction detection.

   Covers:
   - Clearance letter generation + snapshot
   - Statement generation
   - Contract agreement generation + term snapshot
   - Signing lifecycle (record, duplicate prevention)
   - contract-signed? derivation
   - Contradiction detection (payment changes settlement)
   - Supersession
   - Document retraction"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [lms.db :as db]
            [lms.contract :as contract]
            [lms.operations :as ops]
            [lms.party :as party]
            [datomic.client.api :as d]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def test-borrower-id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
(def test-signatory-1-id #uuid "11111111-1111-1111-1111-111111111111")
(def test-signatory-2-id #uuid "22222222-2222-2222-2222-222222222222")

(defn get-test-conn
  "Fresh isolated database per test."
  []
  (let [client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system "lms"})
        test-db-name (str "doc-test-" (subs (str (random-uuid)) 0 8))]
    (d/create-database client {:db-name test-db-name})
    (let [conn (d/connect client {:db-name test-db-name})]
      (db/install-schema conn)
      conn)))

(defn setup-contract
  "Create borrower, signatories, and a contract with 2 installments.
   Returns contract-id."
  [conn]
  ;; Borrower (company)
  (d/transact conn
              {:tx-data [{:party/id test-borrower-id
                          :party/type :party.type/company
                          :party/legal-name "Test Customer Co."
                          :party/cr-number "CR-123456"}]})
  ;; Signatories (persons)
  (d/transact conn
              {:tx-data [{:party/id test-signatory-1-id
                          :party/type :party.type/person
                          :party/legal-name "Ahmed Al-Rashid"
                          :party/national-id "NID-001"}
                         {:party/id test-signatory-2-id
                          :party/type :party.type/person
                          :party/legal-name "Fatima Hassan"
                          :party/national-id "NID-002"}]})
  (let [contract-id (random-uuid)]
    (ops/board-contract conn
                        {:contract/id contract-id
                         :contract/external-id "DOC-TEST-001"
                         :contract/borrower [:party/id test-borrower-id]
                         :contract/start-date #inst "2024-01-01"
                         :contract/principal 200000M
                         :contract/security-deposit 10000M
                         :contract/authorized-signatories
                         [[:party/id test-signatory-1-id]
                          [:party/id test-signatory-2-id]]}
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
;; Clearance Letter Tests
;; ============================================================

(deftest generate-clearance-letter-test
  (testing "Clearance letter stores settlement-amount and EDN snapshot"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)
          settlement-date #inst "2024-01-15"
          penalty-days 30]

      (ops/generate-clearance-letter conn contract-id settlement-date
                                    penalty-days "test-user"
                                    :note "Test clearance")

      (let [db (d/db conn)
            letters (contract/get-clearance-letters db contract-id)]
        (is (= 1 (count letters)))

        (let [cl (first letters)]
          ;; First-class binding attribute
          (is (some? (:clearance-letter/settlement-amount cl)))
          (is (instance? java.math.BigDecimal (:clearance-letter/settlement-amount cl)))

          ;; Parameters stored
          (is (= settlement-date (:clearance-letter/settlement-date cl)))
          (is (= penalty-days (:clearance-letter/penalty-days cl)))

          ;; EDN snapshot is parseable and contains settlement-amount
          (let [snapshot (edn/read-string (:clearance-letter/snapshot cl))]
            (is (map? snapshot))
            (is (= (:clearance-letter/settlement-amount cl)
                    (:settlement-amount snapshot)))))))))

;; ============================================================
;; Statement Tests
;; ============================================================

(deftest generate-statement-test
  (testing "Statement stores contract-state snapshot as EDN"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)
          period-start #inst "2024-01-01"
          period-end #inst "2024-01-31"]

      (ops/generate-statement conn contract-id period-start period-end "test-user")

      (let [db (d/db conn)
            stmts (contract/get-statements db contract-id)]
        (is (= 1 (count stmts)))

        (let [s (first stmts)]
          (is (= period-start (:statement/period-start s)))
          (is (= period-end (:statement/period-end s)))

          ;; Snapshot is parseable contract-state
          (let [snapshot (edn/read-string (:statement/snapshot s))]
            (is (map? snapshot))
            (is (contains? snapshot :contract))
            (is (contains? snapshot :installments))))))))

;; ============================================================
;; Contract Agreement Tests
;; ============================================================

(deftest generate-contract-agreement-test
  (testing "Contract agreement snapshots terms at generation time"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            agreements (contract/get-contract-agreements db contract-id)]
        (is (= 1 (count agreements)))

        (let [ca (first agreements)
              snapshot (edn/read-string (:contract-agreement/snapshot ca))]
          ;; Snapshot has contract, fees, installments
          (is (contains? snapshot :contract))
          (is (contains? snapshot :fees))
          (is (contains? snapshot :installments))

          ;; Contract terms frozen
          (is (= 200000M (get-in snapshot [:contract :contract/principal])))
          (is (= "DOC-TEST-001" (get-in snapshot [:contract :contract/external-id])))

          ;; Schedule frozen
          (is (= 2 (count (:installments snapshot))))
          (is (= 1 (count (:fees snapshot)))))))))

;; ============================================================
;; Signing Tests
;; ============================================================

(deftest record-signing-test
  (testing "Record signing creates signing entity linked to document"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            ca (first (contract/get-contract-agreements db contract-id))
            ca-ref [:contract-agreement/id (:contract-agreement/id ca)]]

        ;; First signing
        (ops/record-signing conn ca-ref test-signatory-1-id
                            #inst "2024-01-10" :wet-ink "test-user")

        (let [db2 (d/db conn)
              signings (contract/get-signings db2 ca-ref)]
          (is (= 1 (count signings)))
          (is (= test-signatory-1-id
                 (get-in (first signings) [:signing/party :party/id])))
          (is (= :wet-ink (:signing/method (first signings)))))))))

(deftest duplicate-signing-rejected-test
  (testing "Same party cannot sign the same document twice"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            ca (first (contract/get-contract-agreements db contract-id))
            ca-ref [:contract-agreement/id (:contract-agreement/id ca)]]

        (ops/record-signing conn ca-ref test-signatory-1-id
                            #inst "2024-01-10" :wet-ink "test-user")

        ;; Second signing by same party should throw
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Duplicate signing"
                              (ops/record-signing conn ca-ref test-signatory-1-id
                                                  #inst "2024-01-11" :digital "test-user")))))))

;; ============================================================
;; contract-signed? Derivation Tests
;; ============================================================

(deftest contract-signed-test
  (testing "contract-signed? is false until all signatories sign"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            ca (first (contract/get-contract-agreements db contract-id))
            ca-ref [:contract-agreement/id (:contract-agreement/id ca)]]

        ;; No signings yet
        (is (not (contract/contract-signed? db contract-id)))

        ;; First signatory signs
        (ops/record-signing conn ca-ref test-signatory-1-id
                            #inst "2024-01-10" :wet-ink "test-user")
        (let [db2 (d/db conn)]
          (is (not (contract/contract-signed? db2 contract-id))))

        ;; Second signatory signs — now fully signed
        (ops/record-signing conn ca-ref test-signatory-2-id
                            #inst "2024-01-11" :wet-ink "test-user")
        (let [db3 (d/db conn)]
          (is (true? (contract/contract-signed? db3 contract-id))))))))

;; ============================================================
;; Contradiction Detection Tests
;; ============================================================

(deftest check-clearance-contradictions-test
  (testing "Contradiction detected when payment changes settlement"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)
          settlement-date #inst "2024-01-15"
          penalty-days 0]

      ;; Generate clearance letter (commits settlement-amount)
      (ops/generate-clearance-letter conn contract-id settlement-date
                                    penalty-days "test-user")

      ;; No contradiction yet
      (let [db (d/db conn)
            contradictions (contract/check-clearance-contradictions
                            db contract-id settlement-date)]
        (is (empty? contradictions)))

      ;; Record a payment — changes the settlement calculation
      (ops/record-payment conn contract-id 50000M #inst "2024-01-14"
                          "PAY-001" "test-user")

      ;; Now there should be a contradiction
      (let [db2 (d/db conn)
            contradictions (contract/check-clearance-contradictions
                            db2 contract-id settlement-date)]
        (is (= 1 (count contradictions)))
        (is (neg? (:difference (first contradictions))))))))

;; ============================================================
;; Supersession Tests
;; ============================================================

(deftest supersede-clearance-letter-test
  (testing "Superseded letter is no longer active"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)
          settlement-date #inst "2024-01-15"
          penalty-days 0]

      ;; Generate first letter
      (ops/generate-clearance-letter conn contract-id settlement-date
                                    penalty-days "test-user")

      (let [db (d/db conn)
            first-cl (first (contract/get-clearance-letters db contract-id))
            first-cl-id (:clearance-letter/id first-cl)]

        ;; Record a payment
        (ops/record-payment conn contract-id 50000M #inst "2024-01-14"
                            "PAY-001" "test-user")

        ;; Supersede with new letter
        (ops/supersede-clearance-letter conn contract-id first-cl-id
                                       settlement-date penalty-days "test-user"
                                       :note "Supersedes after payment")

        (let [db2 (d/db conn)
              all-letters (contract/get-clearance-letters db2 contract-id)
              active-letters (contract/get-active-clearance-letters db2 contract-id)]

          ;; Two total letters, but only one active
          (is (= 2 (count all-letters)))
          (is (= 1 (count active-letters)))

          ;; Active letter's supersedes ref points to old one
          (let [new-cl (first active-letters)]
            (is (some? (:clearance-letter/supersedes new-cl)))))))))

;; ============================================================
;; Document Retraction Tests
;; ============================================================

(deftest retract-document-test
  (testing "Retract document removes entity and associated signings"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            ca (first (contract/get-contract-agreements db contract-id))
            ca-id (:contract-agreement/id ca)
            ca-ref [:contract-agreement/id ca-id]]

        ;; Add a signing
        (ops/record-signing conn ca-ref test-signatory-1-id
                            #inst "2024-01-10" :wet-ink "test-user")

        ;; Verify exists
        (let [db2 (d/db conn)]
          (is (= 1 (count (contract/get-contract-agreements db2 contract-id))))
          (is (= 1 (count (contract/get-signings db2 ca-ref)))))

        ;; Retract
        (ops/retract-document conn :contract-agreement ca-id
                              :erroneous-entry "test-user"
                              :note "Generated in error")

        ;; Document is gone
        (let [db3 (d/db conn)]
          (is (empty? (contract/get-contract-agreements db3 contract-id)))
          ;; Signing entity also retracted (query directly — lookup ref
          ;; is unresolvable after retraction of the document)
          (is (empty? (d/q {:query '[:find ?s
                                     :in $ ?method
                                     :where [?s :signing/method ?method]]
                            :args [db3 :wet-ink]}))))))))

;; ============================================================
;; Integration: contract-state includes documents
;; ============================================================

(deftest contract-state-includes-documents-test
  (testing "contract-state includes :documents with all document types"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-clearance-letter conn contract-id #inst "2024-01-15"
                                    0 "test-user")
      (ops/generate-statement conn contract-id #inst "2024-01-01"
                              #inst "2024-01-31" "test-user")
      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            state (contract/contract-state db contract-id #inst "2024-06-15")]
        (is (map? (:documents state)))
        (is (= 1 (count (get-in state [:documents :clearance-letters]))))
        (is (= 1 (count (get-in state [:documents :statements]))))
        (is (= 1 (count (get-in state [:documents :contract-agreements]))))))))

;; ============================================================
;; Integration: get-events includes document events
;; ============================================================

(deftest get-events-includes-documents-test
  (testing "Event timeline includes document generation events"
    (let [conn (get-test-conn)
          contract-id (setup-contract conn)]

      (ops/generate-clearance-letter conn contract-id #inst "2024-01-15"
                                    0 "test-user")
      (ops/generate-contract-agreement conn contract-id "test-user")

      (let [db (d/db conn)
            events (contract/get-events db contract-id)
            event-types (set (map :event-type events))]
        (is (contains? event-types :clearance-letter))
        (is (contains? event-types :contract-agreement))))))
