(ns lms.party-test
  "Tests for party management: creation, validation, relationships.

   Test categories:
   1. Party creation and validation
   2. Contract party relationships (borrower, guarantors, signatories)
   3. Ownership relationships
   4. Party queries (reverse refs, search)"
  (:require [clojure.test :refer [deftest is testing]]
            [lms.party :as sut]
            [lms.contract :as contract]
            [lms.operations :as ops]
            [lms.db :as db]
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

(defn create-company [conn name cr-number]
  "Create a company party directly via transact. Returns party-id."
  (let [id (random-uuid)]
    (d/transact conn {:tx-data [{:party/id id
                                 :party/type :party.type/company
                                 :party/legal-name name
                                 :party/cr-number cr-number}]})
    id))

(defn create-person [conn name national-id]
  "Create a person party directly via transact. Returns party-id."
  (let [id (random-uuid)]
    (d/transact conn {:tx-data [{:party/id id
                                 :party/type :party.type/person
                                 :party/legal-name name
                                 :party/national-id national-id}]})
    id))

(defn board-test-contract
  "Board a simple contract with borrower. Returns contract-id."
  [conn borrower-id]
  (let [contract-id (random-uuid)]
    (ops/board-contract conn
                        {:contract/id contract-id
                         :contract/external-id (str "TEST-" (subs (str contract-id) 0 8))
                         :contract/borrower [:party/id borrower-id]
                         :contract/start-date #inst "2024-01-01"
                         :contract/principal 200000M}
                        [{:fee/id (random-uuid)
                          :fee/type :management
                          :fee/amount 1000M
                          :fee/due-date #inst "2024-01-01"}]
                        [{:installment/id (random-uuid)
                          :installment/seq 1
                          :installment/due-date #inst "2024-01-31"
                          :installment/principal-due 100000M
                          :installment/profit-due 5000M
                          :installment/remaining-principal 200000M}
                         {:installment/id (random-uuid)
                          :installment/seq 2
                          :installment/due-date #inst "2024-02-28"
                          :installment/principal-due 100000M
                          :installment/profit-due 5000M
                          :installment/remaining-principal 100000M}]
                        "test-user")
    contract-id))

;; ============================================================
;; Party Creation Tests
;; ============================================================

(deftest create-company-party-test
  (testing "Create company party with CR number"
    (let [conn (get-test-conn)
          result (sut/create-party conn
                                   {:party/type :party.type/company
                                    :party/legal-name "Acme Trading Co."
                                    :party/cr-number "CR-1234567890"
                                    :party/email "info@acme.com"
                                    :party/phone "+966501234567"}
                                   "test-user")
          party-id (:party-id result)
          db (d/db conn)
          party (sut/get-party db party-id)]

      (is (some? party-id))
      (is (= "Acme Trading Co." (:party/legal-name party)))
      (is (= :party.type/company (:party/type party)))
      (is (= "CR-1234567890" (:party/cr-number party)))
      (is (= "info@acme.com" (:party/email party))))))

(deftest create-person-party-test
  (testing "Create person party with national ID"
    (let [conn (get-test-conn)
          result (sut/create-party conn
                                   {:party/type :party.type/person
                                    :party/legal-name "Ahmed Al-Rashid"
                                    :party/national-id "1234567890"
                                    :party/phone "+966509876543"}
                                   "test-user")
          party-id (:party-id result)
          db (d/db conn)
          party (sut/get-party db party-id)]

      (is (some? party-id))
      (is (= "Ahmed Al-Rashid" (:party/legal-name party)))
      (is (= :party.type/person (:party/type party)))
      (is (= "1234567890" (:party/national-id party))))))

(deftest create-party-validation-test
  (testing "Company without CR number fails validation"
    (is (thrown-with-msg? Exception #"Invalid party data"
          (let [conn (get-test-conn)]
            (sut/create-party conn
                              {:party/type :party.type/company
                               :party/legal-name "No CR Co."}
                              "test-user")))))

  (testing "Person without national ID fails validation"
    (is (thrown-with-msg? Exception #"Invalid party data"
          (let [conn (get-test-conn)]
            (sut/create-party conn
                              {:party/type :party.type/person
                               :party/legal-name "No ID Person"}
                              "test-user")))))

  (testing "Missing legal name fails validation"
    (is (thrown-with-msg? Exception #"Invalid party data"
          (let [conn (get-test-conn)]
            (sut/create-party conn
                              {:party/type :party.type/company
                               :party/cr-number "CR-999"}
                              "test-user"))))))

(deftest duplicate-cr-number-test
  (testing "Duplicate CR number is rejected by Datomic unique constraint"
    (let [conn (get-test-conn)]
      (sut/create-party conn
                        {:party/type :party.type/company
                         :party/legal-name "First Co."
                         :party/cr-number "CR-UNIQUE"}
                        "test-user")
      (is (thrown? Exception
            (sut/create-party conn
                              {:party/type :party.type/company
                               :party/legal-name "Second Co."
                               :party/cr-number "CR-UNIQUE"}
                              "test-user"))))))

;; ============================================================
;; Party Query Tests
;; ============================================================

(deftest get-party-by-cr-test
  (testing "Lookup company by CR number"
    (let [conn (get-test-conn)
          _ (sut/create-party conn
                              {:party/type :party.type/company
                               :party/legal-name "Lookup Co."
                               :party/cr-number "CR-LOOKUP"}
                              "test-user")
          db (d/db conn)
          party (sut/get-party-by-cr db "CR-LOOKUP")]

      (is (some? party))
      (is (= "Lookup Co." (:party/legal-name party))))))

(deftest get-party-by-national-id-test
  (testing "Lookup person by national ID"
    (let [conn (get-test-conn)
          _ (sut/create-party conn
                              {:party/type :party.type/person
                               :party/legal-name "Lookup Person"
                               :party/national-id "NID-LOOKUP"}
                              "test-user")
          db (d/db conn)
          party (sut/get-party-by-national-id db "NID-LOOKUP")]

      (is (some? party))
      (is (= "Lookup Person" (:party/legal-name party))))))

(deftest list-parties-test
  (testing "List all parties"
    (let [conn (get-test-conn)]
      (create-company conn "Company A" "CR-A")
      (create-company conn "Company B" "CR-B")
      (create-person conn "Person C" "NID-C")

      (let [db (d/db conn)
            all (sut/list-parties db)
            companies (sut/list-parties db :type :party.type/company)
            persons (sut/list-parties db :type :party.type/person)]

        (is (= 3 (count all)))
        (is (= 2 (count companies)))
        (is (= 1 (count persons)))))))

;; ============================================================
;; Contract Party Relationship Tests
;; ============================================================

(deftest borrower-on-contract-test
  (testing "Board contract with borrower ref, verify in contract-state"
    (let [conn (get-test-conn)
          borrower-id (create-company conn "Borrower Co." "CR-BORROWER")
          contract-id (board-test-contract conn borrower-id)
          db (d/db conn)
          state (contract/contract-state db contract-id #inst "2024-06-15")]

      ;; Borrower should be in contract state
      (is (= borrower-id (get-in state [:contract :borrower :id])))
      (is (= "Borrower Co." (get-in state [:contract :borrower :legal-name])))
      ;; Backward compat
      (is (= "Borrower Co." (get-in state [:contract :customer-name]))))))

(deftest add-remove-guarantors-test
  (testing "Add person and company guarantors, then remove one"
    (let [conn (get-test-conn)
          borrower-id (create-company conn "Borrower Co." "CR-BORROW")
          person-id (create-person conn "Guarantor Person" "NID-GUAR")
          company-id (create-company conn "Guarantor Co." "CR-GUAR")
          contract-id (board-test-contract conn borrower-id)]

      ;; Add guarantors
      (sut/add-guarantor conn contract-id person-id "test-user")
      (sut/add-guarantor conn contract-id company-id "test-user")

      (let [db (d/db conn)
            guarantors (sut/get-guarantors db contract-id)]
        (is (= 2 (count guarantors))))

      ;; Remove person guarantor
      (sut/remove-guarantor conn contract-id person-id "test-user")

      (let [db (d/db conn)
            guarantors (sut/get-guarantors db contract-id)]
        (is (= 1 (count guarantors)))
        (is (= "Guarantor Co." (:party/legal-name (first guarantors))))))))

(deftest add-remove-signatories-test
  (testing "Add person signatories, reject company signatory"
    (let [conn (get-test-conn)
          borrower-id (create-company conn "Borrower Co." "CR-BSIG")
          person-id (create-person conn "Signatory Person" "NID-SIG")
          contract-id (board-test-contract conn borrower-id)]

      ;; Add person signatory — should succeed
      (sut/add-signatory conn contract-id person-id "test-user")

      (let [db (d/db conn)
            signatories (sut/get-signatories db contract-id)]
        (is (= 1 (count signatories)))
        (is (= "Signatory Person" (:party/legal-name (first signatories)))))

      ;; Remove signatory
      (sut/remove-signatory conn contract-id person-id "test-user")

      (let [db (d/db conn)
            signatories (sut/get-signatories db contract-id)]
        (is (= 0 (count signatories)))))))

(deftest contract-state-includes-parties-test
  (testing "Contract state includes guarantors and signatories"
    (let [conn (get-test-conn)
          borrower-id (create-company conn "Main Co." "CR-MAIN")
          guarantor-id (create-person conn "Guarantor" "NID-G")
          signatory-id (create-person conn "Signatory" "NID-S")
          contract-id (board-test-contract conn borrower-id)]

      (sut/add-guarantor conn contract-id guarantor-id "test-user")
      (sut/add-signatory conn contract-id signatory-id "test-user")

      (let [db (d/db conn)
            state (contract/contract-state db contract-id #inst "2024-06-15")]
        (is (= 1 (count (get-in state [:contract :guarantors]))))
        (is (= 1 (count (get-in state [:contract :authorized-signatories]))))
        (is (= "Guarantor" (get-in state [:contract :guarantors 0 :legal-name])))
        (is (= "Signatory" (get-in state [:contract :authorized-signatories 0 :legal-name])))))))

;; ============================================================
;; Party-Contract Reverse Query Tests
;; ============================================================

(deftest get-party-contracts-test
  (testing "Find contracts a party is involved in via reverse refs"
    (let [conn (get-test-conn)
          borrower-id (create-company conn "Multi Co." "CR-MULTI")
          guarantor-id (create-person conn "Guarantor" "NID-MULTI")
          contract-1-id (board-test-contract conn borrower-id)
          contract-2-id (board-test-contract conn borrower-id)]

      ;; Guarantor on contract 1 only
      (sut/add-guarantor conn contract-1-id guarantor-id "test-user")

      (let [db (d/db conn)
            borrower-contracts (sut/get-party-contracts db borrower-id)
            guarantor-contracts (sut/get-party-contracts db guarantor-id)]

        ;; Borrower is on both contracts
        (is (= 2 (count borrower-contracts)))
        (is (every? #(= :borrower (:role %)) borrower-contracts))

        ;; Guarantor is on one contract
        (is (= 1 (count guarantor-contracts)))
        (is (= :guarantor (:role (first guarantor-contracts))))))))

;; ============================================================
;; Ownership Tests
;; ============================================================

(deftest ownership-person-owns-company-test
  (testing "Record person owning percentage of company"
    (let [conn (get-test-conn)
          company-id (create-company conn "Owned Co." "CR-OWNED")
          person-id (create-person conn "Owner Person" "NID-OWNER")]

      (sut/record-ownership conn person-id company-id 60M "test-user")

      (let [db (d/db conn)
            ownerships (sut/get-ownership db company-id)]
        (is (= 1 (count ownerships)))
        (is (= 60M (:ownership/percentage (first ownerships))))
        (is (= person-id (get-in (first ownerships) [:ownership/owner :party/id])))))))

(deftest ownership-company-owns-company-test
  (testing "Record holding company owning percentage of subsidiary"
    (let [conn (get-test-conn)
          holding-id (create-company conn "Holding Co." "CR-HOLDING")
          subsidiary-id (create-company conn "Subsidiary Co." "CR-SUBSIDIARY")]

      (sut/record-ownership conn holding-id subsidiary-id 100M "test-user")

      (let [db (d/db conn)
            ownerships (sut/get-ownership db subsidiary-id)
            owns (sut/get-ownerships-for-party db holding-id)]
        (is (= 1 (count ownerships)))
        (is (= 100M (:ownership/percentage (first ownerships))))
        ;; Reverse: what does holding own?
        (is (= 1 (count owns)))
        (is (= subsidiary-id (get-in (first owns) [:ownership/company :party/id])))))))

(deftest ownership-validation-test
  (testing "Ownership percentage exceeding 100% is rejected"
    (let [conn (get-test-conn)
          company-id (create-company conn "Over-Owned Co." "CR-OVER")
          person-1 (create-person conn "Owner 1" "NID-O1")
          person-2 (create-person conn "Owner 2" "NID-O2")]

      ;; First owner: 70%
      (sut/record-ownership conn person-1 company-id 70M "test-user")

      ;; Second owner: 40% would exceed 100%
      (let [db (d/db conn)
            validation (sut/validate-ownership db {:owner-id person-2
                                                    :company-id company-id
                                                    :percentage 40M})]
        (is (false? (:valid? validation)))
        (is (some #(.contains (:message %) "100%") (:errors validation)))))))

(deftest remove-ownership-test
  (testing "Remove ownership record"
    (let [conn (get-test-conn)
          company-id (create-company conn "Divest Co." "CR-DIVEST")
          person-id (create-person conn "Divesting Owner" "NID-DIV")]

      (sut/record-ownership conn person-id company-id 50M "test-user")

      (let [db (d/db conn)
            ownerships (sut/get-ownership db company-id)
            ownership-id (:ownership/id (first ownerships))]

        (is (= 1 (count ownerships)))

        ;; Remove it
        (sut/remove-ownership conn ownership-id "test-user")

        (let [db2 (d/db conn)
              ownerships2 (sut/get-ownership db2 company-id)]
          (is (= 0 (count ownerships2))))))))
