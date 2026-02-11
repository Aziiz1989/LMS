(ns lms.db
  "Datomic schema and connection management.

   This namespace defines the complete schema for the LMS:
   - Party: legal entity (company or person)
   - Contract: identity + terms + step-up rules + party refs
   - Facility: credit line + party ref
   - Ownership: stake in a company (between parties)
   - Installment: repayment schedule
   - Fee: upfront/recurring fees
   - Payment: money received from customer (entity)
   - Disbursement: money sent out — loan funding or refund (entity)
   - Deposit: collateral movements (entity)
   - Inflow: money entering a contract's waterfall (component of payment, or independent)
   - Outflow: money leaving a contract (component of disbursement, or independent)
   - TX metadata: recording facts only (who, when, what operation)
   - Clearance letter: settlement communicated with legal authority
   - Statement: account snapshot over a period (informational)
   - Contract agreement: the agreement document generated for signing
   - Signing: the act of signing a document

   Philosophy: Store facts, derive state. Business events are entities.
   Recording metadata is on the transaction. All derived state (balances,
   statuses) is computed by querying facts.

   Documents (clearance-letter, statement, contract-agreement) each get
   their own namespace — different things with different data and different
   legal weight. Generation date is txInstant. Document number is derived."
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

   ;; Party references
   {:db/ident       :contract/borrower
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to party (company) that is the borrower on this contract."}

   {:db/ident       :contract/guarantors
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "References to parties (person or company) guaranteeing this contract."}

   {:db/ident       :contract/authorized-signatories
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "References to parties (persons) authorized to sign this contract."}

   {:db/ident       :contract/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Contract start date (disbursement date)"}

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
                     This is the documentary fact from the signed contract. It may differ
                     from principal - sum(fees) if fees were corrected after signing."}

   ;; Lifecycle facts (status is derived from these)
   {:db/ident       :contract/disbursed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When funding was disbursed. Nil = pre-disbursement (pending).
                     Once set, contract is active and payment schedule starts."}

   {:db/ident       :contract/written-off-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When board approved write-off. Nil = not written off.
                     Regulatory fact requiring approval, affects GL and SIMAH reporting."}

   {:db/ident       :contract/days-to-first-installment
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Days from disbursement date to first installment due date.
                     Contractual term. At disbursement, all schedule dates are
                     shifted so first installment falls on disbursed-at + this value."}

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

   {:db/ident       :facility/party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to party (company) this facility belongs to."}

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
   ;; PARTY (legal entity — company or natural person)
   ;; ════════════════════════════════════════════════════════════
   ;; Unified party concept following industry standard (BIAN, FIBO).
   ;; Type-specific attributes (cr-number, national-id) are only asserted
   ;; for the relevant party type.

   {:db/ident       :party/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for party"}

   {:db/ident       :party/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Party type: :party.type/company or :party.type/person"}

   {:db/ident       :party/legal-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Legal name — trade name for companies, full name for persons."}

   {:db/ident       :party/cr-number
    :db/valueType   :db.type/string
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc         "Commercial Registration number. Companies only."}

   {:db/ident       :party/national-id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db/doc         "National ID or Iqama number. Persons only."}

   {:db/ident       :party/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Contact email (optional)."}

   {:db/ident       :party/phone
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Contact phone (optional)."}

   {:db/ident       :party/address
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Contact address (optional)."}

   ;; ════════════════════════════════════════════════════════════
   ;; OWNERSHIP (stake in a company — between parties)
   ;; ════════════════════════════════════════════════════════════
   ;; Records the fact that a party (person or company) owns a
   ;; percentage of a company. Separate entity because it carries
   ;; its own data (percentage).

   {:db/ident       :ownership/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for ownership record"}

   {:db/ident       :ownership/owner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to party (person or company) that owns shares."}

   {:db/ident       :ownership/company
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to party (company) being owned."}

   {:db/ident       :ownership/percentage
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Ownership percentage (0-100)."}

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
    :db/doc         "Fee due date (legacy — derived from days-after-disbursement for new fees)"}

   {:db/ident       :fee/days-after-disbursement
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Days after disbursement when fee is due (0 = at disbursement)"}

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
   ;;   :funding — loan disbursement to borrower
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
                     :funding       — loan disbursement to borrower (from principal)
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
   ;; Records all deductions from principal at origination:
   ;; - :fee-settlement — flows through waterfall
   ;; - :installment-prepayment — flows through waterfall
   ;; - :deposit — does NOT flow through waterfall (deposit ledger is separate)
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :principal-allocation/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for principal allocation"}

   {:db/ident       :principal-allocation/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Amount allocated from principal (SAR). Always positive.
                     Covers fees, deposits, or installment prepayments deducted at origination."}

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

   {:db/ident       :principal-allocation/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Type: :fee-settlement, :deposit, :installment-prepayment.
                     Determines whether allocation flows through waterfall.
                     :fee-settlement and :installment-prepayment flow through waterfall.
                     :deposit does not (deposit ledger is separate)."}

   {:db/ident       :principal-allocation/fee
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Fee entity settled by this allocation (for :fee-settlement type)."}

   ;; ════════════════════════════════════════════════════════════
   ;; INFLOW (money entering contract waterfall)
   ;; ════════════════════════════════════════════════════════════
   ;; An inflow records money entering a contract's waterfall.
   ;; Sources:
   ;;   :funding    — principal disbursed at origination
   ;;   :customer   — customer payment received
   ;;   :deposit    — deposit offset applied to balance
   ;;   :settlement — money from another contract (refi)
   ;; Inflows on payments/disbursements use component refs (cascade retract).
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :inflow/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for inflow"}

   {:db/ident       :inflow/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Inflow amount (SAR). Always positive."}

   {:db/ident       :inflow/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when money entered the contract waterfall."}

   {:db/ident       :inflow/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this inflow belongs to."}

   {:db/ident       :inflow/source
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Source of inflow: :funding :customer :deposit :settlement"}

   {:db/ident       :inflow/source-contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Source contract for settlement inflows (refi).
                     Only present when source = :settlement."}

   ;; Component ref on payment — inflows cascade with payment retraction
   {:db/ident       :payment/inflows
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true
    :db/doc         "Component inflows created by this payment.
                     Cascade retracts with the payment entity."}

   ;; ════════════════════════════════════════════════════════════
   ;; OUTFLOW (money leaving the contract)
   ;; ════════════════════════════════════════════════════════════
   ;; An outflow records money genuinely leaving the contract.
   ;; Types:
   ;;   :borrower   — disbursement to borrower at origination
   ;;   :settlement — money to another contract (refi)
   ;;   :refund     — money returned to customer (origination excess or overpayment)
   ;; Fee settlement, deposit collection, and prepayment are NOT outflows —
   ;; they are waterfall-derived allocations within the contract.
   ;; Outflows on disbursements use component refs (cascade retract).
   ;; Always positive amounts. Errors are retracted.

   {:db/ident       :outflow/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for outflow"}

   {:db/ident       :outflow/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Outflow amount (SAR). Always positive."}

   {:db/ident       :outflow/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when money left the contract."}

   {:db/ident       :outflow/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this outflow belongs to."}

   {:db/ident       :outflow/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Outflow type: :borrower :settlement :refund"}

   {:db/ident       :outflow/target-contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Target contract for settlement outflows (refi).
                     Only present when type = :settlement."}

   ;; Component ref on disbursement — outflows cascade with disbursement retraction
   {:db/ident       :disbursement/outflows
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true
    :db/doc         "Component outflows created by this disbursement.
                     Cascade retracts with the disbursement entity."}

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
                     :rate-adjustment  — schedule profit amounts changed
                     :write-off        — board-approved write-off decision"}

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
    :db/doc         "Reference to entity being corrected (e.g., [:payment/id x])"}

   ;; ════════════════════════════════════════════════════════════
   ;; CLEARANCE LETTER (settlement communicated with legal authority)
   ;; ════════════════════════════════════════════════════════════
   ;; Records the act of communicating a settlement amount to a borrower.
   ;; The settlement-amount is the binding legal commitment — what we told them.
   ;; The snapshot captures the full calculation breakdown as EDN for forensics.
   ;; Generation date is txInstant (no separate date attribute — the act happens
   ;; in the system). Document number is derived from contract external-id + txInstant.
   ;; Supersession (not retraction) for letters already sent — the original is a fact.

   {:db/ident       :clearance-letter/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for clearance letter"}

   {:db/ident       :clearance-letter/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this clearance letter pertains to"}

   {:db/ident       :clearance-letter/settlement-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Effective date of settlement calculation. Distinct from txInstant
                     (when the letter was generated). This is the date for which the
                     settlement was computed — 'as of this date, you owe X.'"}

   {:db/ident       :clearance-letter/penalty-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Penalty days used in settlement calculation.
                     Input parameter that produced the settlement-amount."}

   {:db/ident       :clearance-letter/settlement-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "THE binding settlement amount communicated (SAR).
                     First-class attribute for queryability and contradiction detection.
                     This is the legal commitment — what we told the merchant."}

   {:db/ident       :clearance-letter/snapshot
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string of full calculate-settlement result at generation time.
                     Forensic record — the complete breakdown (outstanding-principal,
                     accrued-profit, unearned-profit, fees, penalty, etc.).
                     Same pattern as :contract/step-up-terms."}

   {:db/ident       :clearance-letter/supersedes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to prior clearance letter this one replaces.
                     The original was sent — that's a fact. This new letter supersedes it.
                     Use retraction only for letters never sent (recording error)."}

   ;; ════════════════════════════════════════════════════════════
   ;; STATEMENT (account snapshot over a period — informational)
   ;; ════════════════════════════════════════════════════════════
   ;; Records the act of generating an account statement for a period.
   ;; Statements are informational, not binding. The snapshot captures
   ;; contract-state at period-end as EDN.

   {:db/ident       :statement/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for statement"}

   {:db/ident       :statement/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this statement pertains to"}

   {:db/ident       :statement/period-start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Reporting period start date"}

   {:db/ident       :statement/period-end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Reporting period end date"}

   {:db/ident       :statement/snapshot
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string of contract-state at period-end.
                     Full account snapshot — balances, payment allocations, statuses."}

   ;; ════════════════════════════════════════════════════════════
   ;; CONTRACT AGREEMENT (the agreement document for signing)
   ;; ════════════════════════════════════════════════════════════
   ;; Records the act of generating the contract agreement document.
   ;; The snapshot freezes the contract terms at generation time — principal,
   ;; schedule, fees, parties, commodity info. This IS the signed agreement,
   ;; independent of later changes (rate adjustments, corrections).

   {:db/ident       :contract-agreement/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for contract agreement"}

   {:db/ident       :contract-agreement/contract
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to contract this agreement pertains to"}

   {:db/ident       :contract-agreement/snapshot
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string of contract terms at generation time.
                     Contains: principal, schedule (installments), fees, parties,
                     commodity info, step-up terms — everything on the paper.
                     Frozen at generation — immune to later rate adjustments."}

   ;; ════════════════════════════════════════════════════════════
   ;; SIGNING (the act of signing a document)
   ;; ════════════════════════════════════════════════════════════
   ;; Each signing is a separate fact: person X signed document Y on date Z.
   ;; Multiple signatories = multiple signing entities.
   ;; "Fully signed" is derived: all :contract/authorized-signatories have signed.
   ;; "Is this contract signed?" is a derivation, not a stored status.
   ;; signing/date IS a business date (unlike document generation) because
   ;; signing happens in the real world and may be recorded later.

   {:db/ident       :signing/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique identifier for signing act"}

   {:db/ident       :signing/document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to document being signed. Polymorphic — can point to
                     clearance-letter, statement, or contract-agreement entity.
                     The referenced entity's attributes reveal its type."}

   {:db/ident       :signing/party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to party (person) who signed.
                     Must be one of the contract's authorized-signatories."}

   {:db/ident       :signing/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Business date — when the signature was obtained.
                     Distinct from txInstant because signing happens in the real world
                     and may be recorded later (pen touched paper Monday, entered Tuesday)."}

   {:db/ident       :signing/method
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Signing method: :wet-ink :digital :otp"}

   {:db/ident       :signing/reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External reference for digital/OTP signing
                     (certificate ID, OTP session, etc.)"}])

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

  ;; 5. Create a test party + contract
  ;; Note: status is derived, not stored. maturity-date is derived from installments.
  (def test-party-id (random-uuid))
  (d/transact conn
              {:tx-data [{:party/id test-party-id
                          :party/type :party.type/company
                          :party/legal-name "Test Customer"
                          :party/cr-number "CR-123456"}]})

  (def test-contract-id (random-uuid))
  (d/transact conn
              {:tx-data [{:db/id "new-contract"
                          :contract/id test-contract-id
                          :contract/external-id "TEST-001"
                          :contract/borrower [:party/id test-party-id]
                          :contract/start-date #inst "2024-01-01"
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
  (delete-database!))
