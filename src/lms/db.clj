(ns lms.db
  "Datomic schema and connection management.

   This namespace defines the complete schema for the LMS:
   - Contract: identity + terms + step-up rules
   - Installment: repayment schedule
   - Fee: upfront/recurring fees
   - Payment: money received from customer (entity)
   - Disbursement: money sent out — loan funding or refund (entity)
   - Deposit: collateral movements (entity)
   - TX metadata: recording facts only (who, when, what operation)

   Philosophy: Store facts, derive state. Business events are entities.
   Recording metadata is on the transaction. All derived state (balances,
   statuses) is computed by querying facts."
  (:require [datomic.client.api :as d]))

;; ============================================================
;; Schema Definition
;; ============================================================

(def schema
  "Complete Datomic schema for LMS MVP.

   Based on PRD section 2. Schema follows event-sourcing principles:
   - Contracts and schedules are facts (they exist)
   - Transactions are facts (they happened)
   - Everything else is derived (paid amounts, balances, statuses)"

  [;; ════════════════════════════════════════════════════════════
   ;; CONTRACT
   ;; ════════════════════════════════════════════════════════════

   {:db/ident       :contract/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for contract"}

   {:db/ident       :contract/external-id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc         "Jira key or LOS reference (e.g., LOAN-2023-001)"}

   {:db/ident       :contract/customer-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer legal name"}

   {:db/ident       :contract/customer-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer identifier (CR number, national ID, etc.)"}

   {:db/ident       :contract/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract status: :active :closed :written-off :refinanced"}

   {:db/ident       :contract/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract start date (disbursement date)"}

   {:db/ident       :contract/maturity-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract maturity date (final payment date)"}

   {:db/ident       :contract/principal
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Principal amount financed (SAR)"}

   {:db/ident       :contract/security-deposit
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Required security deposit amount (SAR). Actual held amount is derived."}

   {:db/ident       :contract/step-up-terms
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string with step-up rules. Nil if flat rate. See PRD section 4 for format."}

   ;; Facility reference (optional - for contracts under a credit line)
   {:db/ident       :contract/facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to parent facility (credit line). Nil for standalone contracts."}

   ;; Commodity (Murabaha) - what was financed
   {:db/ident       :contract/commodity-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity quantity for Murabaha contracts."}

   {:db/ident       :contract/commodity-unit-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity unit price for Murabaha contracts."}

   {:db/ident       :contract/commodity-description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity description (e.g., 'Palm Oil', 'Wheat')."}

   {:db/ident       :contract/commodity-vendor
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity vendor/supplier name."}

   ;; Banking - where money flows
   {:db/ident       :contract/disbursement-iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer IBAN for disbursement."}

   {:db/ident       :contract/disbursement-bank
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Bank name for disbursement."}

   {:db/ident       :contract/virtual-iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Virtual IBAN assigned for payment collection."}

   ;; Contractual net amount
   {:db/ident       :contract/net-disbursement
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net financing amount (صافي مبلغ التمويل) from signed contract.
                     Contractual fact — what was agreed after fee deductions.
                     Derivable as principal - fees, but stored because it appears on
                     the signed contract and serves as reconciliation reference."}

   ;; Refinancing - relationship to prior contract
   {:db/ident       :contract/refinances
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract being refinanced. Nil if not a refinancing."}

   ;; ════════════════════════════════════════════════════════════
   ;; FACILITY (credit line)
   ;; Represents an approved credit line from LOS. LMS tracks utilization.
   ;; ════════════════════════════════════════════════════════════

   {:db/ident       :facility/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for facility"}

   {:db/ident       :facility/external-id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc         "LOS reference (e.g., PIP-1283621)"}

   {:db/ident       :facility/customer-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer identifier (CR number)"}

   {:db/ident       :facility/customer-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer name (denormalized for convenience)"}

   {:db/ident       :facility/limit
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Approved credit limit (SAR). Fact from LOS."}

   {:db/ident       :facility/funder
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Funding source code (e.g., SKFH, KFH-01)"}

   {:db/ident       :facility/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Facility status: :active :closed :suspended"}

   {:db/ident       :facility/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Facility creation timestamp"}

   ;; ════════════════════════════════════════════════════════════
   ;; INSTALLMENT (schedule)
   ;; ════════════════════════════════════════════════════════════

   {:db/ident       :installment/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for installment"}

   {:db/ident       :installment/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to parent contract"}

   {:db/ident       :installment/seq
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Installment sequence number (1, 2, 3, ...)"}

   {:db/ident       :installment/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Payment due date"}

   {:db/ident       :installment/principal-due
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Principal amount due for this installment (SAR)"}

   {:db/ident       :installment/profit-due
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Profit amount due for this installment (SAR). Can change with rate adjustment."}

   {:db/ident       :installment/remaining-principal
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Outstanding principal balance at start of this period.
                     Contractual fact from amortization schedule."}

   ;; ════════════════════════════════════════════════════════════
   ;; FEE (schedule)
   ;; ════════════════════════════════════════════════════════════

   {:db/ident       :fee/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for fee"}

   {:db/ident       :fee/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to parent contract"}

   {:db/ident       :fee/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Fee type: :management :late :processing :insurance"}

   {:db/ident       :fee/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Fee amount (SAR)"}

   {:db/ident       :fee/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Fee due date"}

   ;; ════════════════════════════════════════════════════════════
   ;; PAYMENT (money IN — business entity)
   ;; ════════════════════════════════════════════════════════════
   ;; Money received from customer. Always positive amounts.
   ;; Errors are retracted (rule 9), not reversed.
   ;; Date is the business date (when money arrived), not recording date.

   {:db/ident       :payment/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for payment"}

   {:db/ident       :payment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Payment amount (SAR). Always positive."}

   {:db/ident       :payment/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when money was received. Distinct from txInstant (recording date)."}

   {:db/ident       :payment/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this payment applies to"}

   {:db/ident       :payment/reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External reference (bank transfer ID, check number, etc.)"}

   {:db/ident       :payment/channel
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "How payment arrived (bank-transfer, check, cash, etc.)"}

   {:db/ident       :payment/source-contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract that funded this payment. Links inter-contract fund flows
                     (e.g., refi settlement where new contract pays off old).
                     Business fact — backed by signed refinancing agreement.
                     Same pattern as deposit/target-contract on deposit entities."}

   ;; ════════════════════════════════════════════════════════════
   ;; DISBURSEMENT (money OUT — business entity)
   ;; ════════════════════════════════════════════════════════════
   ;; Money sent out. Type distinguishes purpose:
   ;;   :funding — loan disbursement to customer/merchant
   ;;   :refund  — money returned to customer
   ;; Refund disbursements reduce effective payments in the waterfall.
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :disbursement/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for disbursement"}

   {:db/ident       :disbursement/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Disbursement type:
                     :funding       — loan disbursement to merchant (from principal)
                     :refund        — money returned to customer (reduces waterfall)
                     :excess-return — application excess returned (does NOT affect waterfall)"}

   {:db/ident       :disbursement/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Disbursement amount (SAR). Always positive."}

   {:db/ident       :disbursement/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when money was sent."}

   {:db/ident       :disbursement/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this disbursement is for"}

   {:db/ident       :disbursement/reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External reference (wire transfer ID, check number, etc.)"}

   {:db/ident       :disbursement/iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination IBAN (optional — for loan funding)"}

   {:db/ident       :disbursement/bank
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination bank name (optional — for loan funding)"}

   ;; ════════════════════════════════════════════════════════════
   ;; DEPOSIT (collateral movement — business entity)
   ;; ════════════════════════════════════════════════════════════
   ;; Security deposit movements. Type distinguishes direction:
   ;;   :received — customer provides collateral
   ;;   :refund   — collateral returned to customer
   ;;   :offset   — collateral applied to balance (flows through waterfall)
   ;;   :transfer — collateral moved between contracts
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :deposit/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for deposit event"}

   {:db/ident       :deposit/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Deposit event type: :received :refund :offset :transfer"}

   {:db/ident       :deposit/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Deposit amount (SAR). Always positive."}

   {:db/ident       :deposit/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when deposit event occurred."}

   {:db/ident       :deposit/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Primary contract (source contract for transfers)"}

   {:db/ident       :deposit/target-contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Target contract for transfers. Only present when type = :transfer."}

   {:db/ident       :deposit/reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External reference for deposit event"}

   {:db/ident       :deposit/source
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Source of deposit funds: :funding (from principal) or :customer.
                     Only meaningful for type :received. Omitted when source is obvious
                     from context or for non-received deposit types."}

   ;; ════════════════════════════════════════════════════════════
   ;; PRINCIPAL ALLOCATION (fees deducted from funding — waterfall source)
   ;; ════════════════════════════════════════════════════════════
   ;; Money from the principal allocated to settle waterfall obligations
   ;; (fees/costs) at origination. This is NOT a payment (no money from
   ;; outside) and NOT a fee attribute (the fee doesn't know how it was
   ;; settled). It's its own concept: an allocation of principal funds.
   ;;
   ;; Flows through waterfall as a 4th source alongside payments,
   ;; refund disbursements, and deposit offsets.
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :principal-allocation/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for principal allocation"}

   {:db/ident       :principal-allocation/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Amount allocated from principal to settle waterfall obligations (SAR).
                     Always positive. Covers fees/costs deducted from principal at
                     origination. Deposit deductions are handled by deposit entities."}

   {:db/ident       :principal-allocation/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date of allocation (typically origination date)."}

   {:db/ident       :principal-allocation/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract whose principal was allocated."}

   {:db/ident       :principal-allocation/reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External reference or description."}

   ;; ════════════════════════════════════════════════════════════
   ;; TRANSACTION METADATA (recording facts — on Datomic tx entity)
   ;; ════════════════════════════════════════════════════════════
   ;; These attributes describe WHO recorded WHAT and WHY.
   ;; Business facts live on entities above. TX metadata is about
   ;; the act of recording, not the business event itself.
   ;;
   ;; For entity-creating transactions (payments, deposits, etc.):
   ;;   tx/author + tx/note only. Contract ref is on the entity.
   ;; For admin events (boarding, rate-adjustment):
   ;;   tx/type + tx/contract + tx/author + tx/note.
   ;; For corrections (retractions):
   ;;   tx/reason + tx/corrects + tx/author + tx/note.

   {:db/ident       :tx/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Admin event type (not for entity-creating txs):
                     :boarding         — contract created
                     :rate-adjustment  — schedule profit amounts changed"}

   {:db/ident       :tx/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract for admin events only. Entity-creating txs have it on the entity."}

   {:db/ident       :tx/author
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "User ID from application auth (who performed this action)"}

   {:db/ident       :tx/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text note or reason"}

   {:db/ident       :tx/migrated-from
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Source ID from legacy system (recording fact: how data entered the system)"}

   {:db/ident       :tx/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Correction reason: :correction :duplicate-removal :erroneous-entry"}

   {:db/ident       :tx/corrects
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to entity being corrected (e.g., [:payment/id x])"}])

;; ============================================================
;; Connection Management
;; ============================================================

(def db-name "lms")

(def ^:dynamic *test-db-name* nil)

(defn get-client
  "Get Datomic Local client.

   For development, uses temporary directory for persistence across processes.
   For production, change :storage-dir to permanent location.

   Usage:
     (def client (get-client))"
  []
  (d/client {:server-type :datomic-local
             :storage-dir "/tmp/datomic-lms"  ;; Shared across processes
             :system "lms"}))

(defn create-database!
  "Create database if it doesn't exist. Idempotent.

   Returns true if database was created, false if already exists."
  []
  (let [client (get-client)]
    (d/create-database client {:db-name db-name})))

(defn get-connection
  "Get connection to database. Creates database if needed.

   Usage:
     (def conn (get-connection))
     (d/db conn) ;; => current db value"
  []
  (let [client (get-client)]
    (create-database!)
    (d/connect client {:db-name db-name})))

(defn install-schema
  "Install schema into database. Idempotent - safe to run multiple times.

   Datomic schema is additive: new attributes can be added at any time.
   Existing attributes cannot be removed or changed (only their doc strings
   can be updated).

   Usage:
     (def conn (get-connection))
     (install-schema conn)

   Returns transaction result."
  [conn]
  (d/transact conn {:tx-data schema}))

(defn delete-database!
  "Delete database. DESTRUCTIVE - use only for testing.

   Returns true if database was deleted."
  []
  (let [client (get-client)]
    (d/delete-database client {:db-name db-name})))

;; ============================================================
;; Schema Utilities
;; ============================================================

(defn entity-exists?
  "Check if entity with given lookup ref exists in database.

   Lookup ref is a vector like [:contract/id uuid] or [:fee/id uuid].

   Usage:
     (entity-exists? db [:contract/id contract-id])
     ;; => true if contract exists, false otherwise"
  [db lookup-ref]
  (not (nil? (d/pull db {:selector [:db/id]
                         :eid lookup-ref}))))

(defn recording-metadata
  "Build transaction metadata for the recording event.

   TX metadata describes who recorded a fact and why — not the business
   event itself. Business facts live on entities (payment/*, deposit/*, etc.).

   Usage:
     (recording-metadata \"user-1\")
     (recording-metadata \"user-1\" :note \"Monthly payment\")

   Returns map ready to be included in transact call."
  [author & {:keys [note]}]
  (cond-> {:db/id "datomic.tx"
           :tx/author author}
    note (assoc :tx/note note)))

;; ============================================================
;; Development Helpers
;; ============================================================

(comment
  ;; REPL workflow for development

  ;; 1. Create and connect to database
  (def conn (get-connection))

  ;; 2. Install schema
  (install-schema conn)

  ;; 3. Get current database value
  (def db (d/db conn))

  ;; 4. Query schema (introspection)
  (d/q '[:find ?ident ?doc
         :where
         [?e :db/ident ?ident]
         [?e :db/doc ?doc]
         [(namespace ?ident) ?ns]
         [(= ?ns "contract")]]
       db)

  ;; 5. Create a test contract
  (def test-contract-id (random-uuid))
  (d/transact conn
              {:tx-data [{:db/id "new-contract"
                          :contract/id test-contract-id
                          :contract/external-id "TEST-001"
                          :contract/customer-name "Test Customer"
                          :contract/customer-id "CR-123456"
                          :contract/status :active
                          :contract/start-date #inst "2024-01-01"
                          ;; maturity-date is now derived from installments
                          :contract/principal 1000000M
                          :contract/security-deposit 50000M}

                         {:db/id "datomic.tx"
                          :tx/type :boarding
                          :tx/contract "new-contract"
                          :tx/author "repl-user"}]})

  ;; 6. Query contract
  (d/q {:query '[:find (pull ?e [*])
                 :in $ ?id
                 :where [?e :contract/id ?id]]
        :args [(d/db conn) test-contract-id]})

  ;; 7. Delete database (testing only!)
  (delete-database!)

  )
