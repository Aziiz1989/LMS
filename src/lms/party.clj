(ns lms.party
  "Party queries, validation, and write operations.

   party/* entities represent legal entities (companies and natural persons).
   ownership/* entities record the fact that a party owns a share of a company.

   Contract relationships (borrower, guarantors, authorized-signatories) are
   direct refs on the contract entity — not managed here. This namespace
   handles party CRUD, ownership, and cross-cutting party queries."
  (:require [datomic.client.api :as d]
            [lms.db :as db]))

;; ============================================================
;; Party Queries
;; ============================================================

(def party-pull-pattern
  [:party/id :party/type :party/legal-name
   :party/cr-number :party/national-id
   :party/email :party/phone :party/address])

(defn get-party
  "Query party by UUID.

   Returns party map or nil if not found."
  [db party-id]
  (let [result (d/pull db {:selector party-pull-pattern
                           :eid [:party/id party-id]})]
    (when (:party/id result) result)))

(defn get-party-by-cr
  "Lookup company by Commercial Registration number.

   Returns party map or nil."
  [db cr-number]
  (let [result (d/pull db {:selector party-pull-pattern
                           :eid [:party/cr-number cr-number]})]
    (when (:party/id result) result)))

(defn get-party-by-national-id
  "Lookup person by national ID / Iqama number.

   Returns party map or nil."
  [db national-id]
  (let [result (d/pull db {:selector party-pull-pattern
                           :eid [:party/national-id national-id]})]
    (when (:party/id result) result)))

(defn list-parties
  "List all parties, optionally filtered by type.

   Args:
   - db: database value
   - :type party-type: optional :party.type/company or :party.type/person

   Returns sequence of party maps sorted by legal-name."
  [db & {:keys [type]}]
  (let [results (if type
                  (d/q {:query '[:find (pull ?p [:party/id :party/type :party/legal-name
                                                 :party/cr-number :party/national-id])
                                 :in $ ?type
                                 :where
                                 [?p :party/id _]
                                 [?p :party/type ?type]]
                        :args [db type]})
                  (d/q {:query '[:find (pull ?p [:party/id :party/type :party/legal-name
                                                 :party/cr-number :party/national-id])
                                 :where [?p :party/id _]]
                        :args [db]}))]
    (->> results
         (map first)
         (sort-by :party/legal-name))))

;; ============================================================
;; Contract-Party Queries (via direct refs)
;; ============================================================

(defn get-borrower
  "Get the borrower party for a contract.

   Returns party map or nil."
  [db contract-id]
  (let [result (d/q {:query '[:find (pull ?party [:party/id :party/type :party/legal-name
                                                  :party/cr-number])
                              :in $ ?cid
                              :where
                              [?c :contract/id ?cid]
                              [?c :contract/borrower ?party]]
                     :args [db contract-id]})]
    (ffirst result)))

(defn get-guarantors
  "Get all guarantor parties for a contract.

   Returns sequence of party maps."
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?party [:party/id :party/type :party/legal-name
                                          :party/cr-number :party/national-id])
                      :in $ ?cid
                      :where
                      [?c :contract/id ?cid]
                      [?c :contract/guarantors ?party]]
             :args [db contract-id]})
       (map first)
       (sort-by :party/legal-name)))

(defn get-signatories
  "Get all authorized signatory parties for a contract.

   Returns sequence of party maps."
  [db contract-id]
  (->> (d/q {:query '[:find (pull ?party [:party/id :party/type :party/legal-name
                                          :party/national-id])
                      :in $ ?cid
                      :where
                      [?c :contract/id ?cid]
                      [?c :contract/authorized-signatories ?party]]
             :args [db contract-id]})
       (map first)
       (sort-by :party/legal-name)))

(defn get-party-contracts
  "Find all contracts where this party has any role.

   Returns sequence of maps with :contract-id, :external-id, and :roles."
  [db party-id]
  (let [party-eid (ffirst (d/q {:query '[:find ?e
                                         :in $ ?pid
                                         :where [?e :party/id ?pid]]
                                :args [db party-id]}))
        borrower-cids
        (when party-eid
          (->> (d/q {:query '[:find ?cid
                              :in $ ?party
                              :where
                              [?c :contract/borrower ?party]
                              [?c :contract/id ?cid]]
                     :args [db party-eid]})
               (map first)))
        guarantor-cids
        (when party-eid
          (->> (d/q {:query '[:find ?cid
                              :in $ ?party
                              :where
                              [?c :contract/guarantors ?party]
                              [?c :contract/id ?cid]]
                     :args [db party-eid]})
               (map first)))
        signatory-cids
        (when party-eid
          (->> (d/q {:query '[:find ?cid
                              :in $ ?party
                              :where
                              [?c :contract/authorized-signatories ?party]
                              [?c :contract/id ?cid]]
                     :args [db party-eid]})
               (map first)))]
    (vec (concat
           (map (fn [cid] {:contract-id cid :role :borrower}) borrower-cids)
           (map (fn [cid] {:contract-id cid :role :guarantor}) guarantor-cids)
           (map (fn [cid] {:contract-id cid :role :authorized-signatory}) signatory-cids)))))

;; ============================================================
;; Ownership Queries
;; ============================================================

(defn get-ownership
  "Get all ownership records for a company.

   Returns sequence of maps with :id, :owner (party map), :percentage."
  [db company-party-id]
  (->> (d/q {:query '[:find (pull ?o [:ownership/id :ownership/percentage
                                      {:ownership/owner [:party/id :party/type
                                                         :party/legal-name
                                                         :party/cr-number
                                                         :party/national-id]}])
                      :in $ ?cid
                      :where
                      [?company :party/id ?cid]
                      [?o :ownership/company ?company]]
             :args [db company-party-id]})
       (map first)
       (sort-by :ownership/percentage >)))

(defn get-ownerships-for-party
  "Get all companies a party owns shares in.

   Returns sequence of maps with :id, :company (party map), :percentage."
  [db party-id]
  (->> (d/q {:query '[:find (pull ?o [:ownership/id :ownership/percentage
                                      {:ownership/company [:party/id :party/type
                                                           :party/legal-name
                                                           :party/cr-number]}])
                      :in $ ?pid
                      :where
                      [?owner :party/id ?pid]
                      [?o :ownership/owner ?owner]]
             :args [db party-id]})
       (map first)
       (sort-by :ownership/percentage >)))

;; ============================================================
;; Validation (pure functions)
;; ============================================================

(defn validate-party-data
  "Validate party data before creation.

   Checks:
   - legal-name present
   - type is :party.type/company or :party.type/person
   - company must have cr-number
   - person must have national-id

   Returns {:valid? true} or {:valid? false :errors [...]}"
  [data]
  (let [errors (atom [])]
    (when-not (:party/legal-name data)
      (swap! errors conj {:field :party/legal-name :message "Missing required field: legal-name"}))
    (let [t (:party/type data)]
      (when-not (#{:party.type/company :party.type/person} t)
        (swap! errors conj {:field :party/type :message "Type must be :party.type/company or :party.type/person"}))
      (when (= t :party.type/company)
        (when-not (:party/cr-number data)
          (swap! errors conj {:field :party/cr-number :message "Company must have cr-number"})))
      (when (= t :party.type/person)
        (when-not (:party/national-id data)
          (swap! errors conj {:field :party/national-id :message "Person must have national-id"}))))
    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

(defn validate-borrower
  "Validate that a party can be a borrower (must be company).

   Returns {:valid? true} or {:valid? false :errors [...]}"
  [db party-id]
  (let [party (get-party db party-id)]
    (cond
      (nil? party)
      {:valid? false :errors [{:field :contract/borrower
                               :message (str "Party not found: " party-id)}]}
      (not= :party.type/company (:party/type party))
      {:valid? false :errors [{:field :contract/borrower
                               :message "Borrower must be a company"}]}
      :else {:valid? true})))

(defn validate-signatory
  "Validate that a party can be an authorized signatory (must be person).

   Returns {:valid? true} or {:valid? false :errors [...]}"
  [db party-id]
  (let [party (get-party db party-id)]
    (cond
      (nil? party)
      {:valid? false :errors [{:field :contract/authorized-signatories
                               :message (str "Party not found: " party-id)}]}
      (not= :party.type/person (:party/type party))
      {:valid? false :errors [{:field :contract/authorized-signatories
                               :message "Authorized signatory must be a person"}]}
      :else {:valid? true})))

(defn validate-ownership
  "Validate ownership data.

   Checks:
   - Owner party exists
   - Company party exists and is type :party.type/company
   - Percentage is between 0 and 100 (exclusive of 0)
   - Total ownership of company does not exceed 100%

   Returns {:valid? true} or {:valid? false :errors [...]}"
  [db {:keys [owner-id company-id percentage]}]
  (let [errors (atom [])
        owner (get-party db owner-id)
        company (get-party db company-id)]
    (when-not owner
      (swap! errors conj {:field :ownership/owner :message (str "Owner party not found: " owner-id)}))
    (when-not company
      (swap! errors conj {:field :ownership/company :message (str "Company party not found: " company-id)}))
    (when (and company (not= :party.type/company (:party/type company)))
      (swap! errors conj {:field :ownership/company :message "Owned entity must be a company"}))
    (when-not (and percentage (pos? percentage) (<= percentage 100M))
      (swap! errors conj {:field :ownership/percentage :message "Percentage must be between 0 (exclusive) and 100"}))
    (when (and company percentage (pos? percentage))
      (let [existing (get-ownership db company-id)
            total-existing (reduce + 0M (map :ownership/percentage existing))]
        (when (> (+ total-existing percentage) 100M)
          (swap! errors conj {:field :ownership/percentage
                              :message (str "Total ownership would exceed 100% (existing: "
                                            total-existing "%, new: " percentage "%)")}))))
    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

;; ============================================================
;; Write Operations
;; ============================================================

(defn create-party
  "Create a new party entity.

   Validates data, generates UUID server-side, transacts.

   Args:
   - conn: Datomic connection
   - data: Map with :party/type, :party/legal-name, and type-specific fields
   - user-id: User performing the operation

   Returns: Transaction result map"
  [conn data user-id]
  (let [validation (validate-party-data data)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid party data" {:errors (:errors validation)
                                            :error :invalid-party-data}))))
  (let [party-id (random-uuid)
        party-entity (-> data
                         (assoc :party/id party-id)
                         (select-keys [:party/id :party/type :party/legal-name
                                       :party/cr-number :party/national-id
                                       :party/email :party/phone :party/address]))]
    (d/transact conn {:tx-data [party-entity
                                (db/recording-metadata user-id
                                                       :note (str "Party created: "
                                                                  (:party/legal-name data)))]})
    {:party-id party-id}))

(defn update-party
  "Update mutable party attributes (contact info, legal name).

   Args:
   - conn: Datomic connection
   - party-id: UUID of party to update
   - updates: Map of attributes to update (only contact/name fields)
   - user-id: User performing the operation

   Returns: Transaction result map"
  [conn party-id updates user-id]
  (let [allowed-keys #{:party/legal-name :party/email :party/phone :party/address}
        filtered (select-keys updates allowed-keys)]
    (when (empty? filtered)
      (throw (ex-info "No valid update fields provided"
                      {:error :no-valid-updates})))
    (d/transact conn {:tx-data [(assoc filtered :party/id party-id)
                                (db/recording-metadata user-id
                                                       :note (str "Party updated: " party-id))]})))

(defn add-guarantor
  "Add a guarantor to a contract.

   Guarantors can be persons or companies. Transacts an assert of
   :contract/guarantors ref with TX metadata.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - party-id: UUID of guarantor party
   - user-id: User performing the operation

   Returns: Transaction result map"
  [conn contract-id party-id user-id]
  (let [db (d/db conn)
        party (get-party db party-id)]
    (when-not party
      (throw (ex-info "Party not found" {:party-id party-id :error :party-not-found})))
    (d/transact conn {:tx-data [[:db/add [:contract/id contract-id]
                                 :contract/guarantors [:party/id party-id]]
                                {:db/id "datomic.tx"
                                 :tx/type :add-guarantor
                                 :tx/contract [:contract/id contract-id]
                                 :tx/author user-id}]})))

(defn remove-guarantor
  "Remove a guarantor from a contract.

   Retracts the :contract/guarantors ref with TX metadata.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - party-id: UUID of guarantor party to remove
   - user-id: User performing the operation
   - note: Optional reason for removal

   Returns: Transaction result map"
  [conn contract-id party-id user-id & {:keys [note]}]
  (d/transact conn {:tx-data [[:db/retract [:contract/id contract-id]
                               :contract/guarantors [:party/id party-id]]
                              (cond-> {:db/id "datomic.tx"
                                       :tx/type :release-guarantor
                                       :tx/contract [:contract/id contract-id]
                                       :tx/author user-id}
                                note (assoc :tx/note note))]}))

(defn add-signatory
  "Add an authorized signatory to a contract.

   Signatories must be persons. Validates type before transacting.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - party-id: UUID of signatory party (must be person)
   - user-id: User performing the operation

   Returns: Transaction result map"
  [conn contract-id party-id user-id]
  (let [db (d/db conn)
        validation (validate-signatory db party-id)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid signatory" {:errors (:errors validation)
                                           :error :invalid-signatory})))
    (d/transact conn {:tx-data [[:db/add [:contract/id contract-id]
                                 :contract/authorized-signatories [:party/id party-id]]
                                {:db/id "datomic.tx"
                                 :tx/type :add-signatory
                                 :tx/contract [:contract/id contract-id]
                                 :tx/author user-id}]})))

(defn remove-signatory
  "Remove an authorized signatory from a contract.

   Args:
   - conn: Datomic connection
   - contract-id: UUID of contract
   - party-id: UUID of signatory party to remove
   - user-id: User performing the operation
   - note: Optional reason for removal

   Returns: Transaction result map"
  [conn contract-id party-id user-id & {:keys [note]}]
  (d/transact conn {:tx-data [[:db/retract [:contract/id contract-id]
                               :contract/authorized-signatories [:party/id party-id]]
                              (cond-> {:db/id "datomic.tx"
                                       :tx/type :release-signatory
                                       :tx/contract [:contract/id contract-id]
                                       :tx/author user-id}
                                note (assoc :tx/note note))]}))

(defn record-ownership
  "Record an ownership stake.

   Creates an ownership/* entity linking owner party to company party
   with a percentage.

   Args:
   - conn: Datomic connection
   - owner-id: UUID of owner party (person or company)
   - company-id: UUID of company party
   - percentage: Ownership percentage (bigdec, 0-100)
   - user-id: User performing the operation

   Returns: Transaction result map"
  [conn owner-id company-id percentage user-id]
  (let [db (d/db conn)
        validation (validate-ownership db {:owner-id owner-id
                                              :company-id company-id
                                              :percentage percentage})]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid ownership data" {:errors (:errors validation)
                                                :error :invalid-ownership})))
    (let [ownership-id (random-uuid)]
      (d/transact conn {:tx-data [{:ownership/id ownership-id
                                   :ownership/owner [:party/id owner-id]
                                   :ownership/company [:party/id company-id]
                                   :ownership/percentage percentage}
                                  (db/recording-metadata user-id
                                                         :note (str "Ownership recorded: "
                                                                    percentage "% of "
                                                                    company-id))]}))))

(defn remove-ownership
  "Remove an ownership record (retract entity).

   Args:
   - conn: Datomic connection
   - ownership-id: UUID of ownership record to remove
   - user-id: User performing the operation
   - note: Optional reason for removal

   Returns: Transaction result map"
  [conn ownership-id user-id & {:keys [note]}]
  (d/transact conn {:tx-data [[:db/retractEntity [:ownership/id ownership-id]]
                              (cond-> {:db/id "datomic.tx"
                                       :tx/reason :correction
                                       :tx/corrects [:ownership/id ownership-id]
                                       :tx/author user-id}
                                note (assoc :tx/note note))]}))
