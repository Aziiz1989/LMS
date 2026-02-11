(ns lms.handlers
  "HTTP request handlers for LMS.

   This namespace contains Ring handlers that:
   1. Extract params from request
   2. Query database
   3. Compute state (call contract/contract-state)
   4. Pass data to views
   5. Return Ring response

   Philosophy:
   - Handlers coordinate I/O and orchestrate calls
   - All business logic is in contract/operations namespaces
   - All presentation logic is in views namespace
   - Handlers are thin glue code"
  (:require [lms.contract :as contract]
            [lms.dates :as dates]
            [lms.operations :as ops]
            [lms.boarding :as boarding]
            [lms.party :as party]
            [lms.settlement :as settlement]
            [lms.views :as views]
            [lms.pdf :as pdf]
            [datomic.client.api :as d]
            [ring.util.response :as response]
            [hiccup2.core :as h]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.ring :as ring-sse]
            [jsonista.core :as json]))

;; ============================================================
;; Datastar SSE Helpers
;; ============================================================

(defn sse-response
  "Return a Datastar SSE response that patches a single HTML fragment then closes.
   `html` is a Hiccup-rendered string. The fragment's root element must have an `id`
   attribute so Datastar knows which DOM node to morph."
  [request html]
  (ring-sse/->sse-response request
                           {ring-sse/on-open
                            (fn [sse]
                              (d*/patch-elements! sse html)
                              (d*/close-sse! sse))}))

(defn sse-ok
  "SSE response: patch multiple HTML fragments, optionally merge signals, then close.
   Used after successful mutations to update page sections and close modals."
  [request {:keys [fragments signals]}]
  (ring-sse/->sse-response request
                           {ring-sse/on-open
                            (fn [sse]
                              (doseq [html fragments]
                                (d*/patch-elements! sse html))
                              (when signals
                                (d*/patch-signals! sse (json/write-value-as-string signals)))
                              (d*/close-sse! sse))}))

(defn sse-error
  "SSE response: show error flash message via Datastar fragment morph."
  [request message]
  (ring-sse/->sse-response request
                           {ring-sse/on-open
                            (fn [sse]
                              (d*/patch-elements! sse
                                                  (str (h/html (views/flash-message {:type :error :message message}))))
                              (d*/close-sse! sse))}))

(defn sse-redirect
  "SSE response: client-side redirect via Datastar. Used when the current page
   entity no longer exists (e.g. after contract retraction)."
  [request url]
  (ring-sse/->sse-response request
                           {ring-sse/on-open
                            (fn [sse]
                              (d*/redirect! sse url)
                              (d*/close-sse! sse))}))

(defn- contract-fragments
  "Re-query contract state and render updated page sections as SSE fragment data.
   Returns map with :fragments and :signals for use with sse-ok.

   Options:
   - :flash       - flash message map {:type :success :message \"...\"}
   - :close-modal - signal name to set false (e.g. \"showPaymentModal\")
   - :sections    - set of sections to re-render (default #{:summary :fees :installments})
   - :signals     - additional signals map to merge into response"
  [conn contract-id {:keys [flash close-modal sections signals]
                     :or {sections #{:summary :fees :installments}}}]
  (let [db (d/db conn)
        state (contract/contract-state db contract-id (java.util.Date.))
        frags (cond-> []
                flash
                (conj (str (h/html (views/flash-message flash))))
                (:summary sections)
                (conj (str (h/html (views/contract-summary state))))
                (:fees sections)
                (conj (str (h/html (views/fees-table (:fees state)))))
                (:installments sections)
                (conj (str (h/html (views/installments-table (:installments state)))))
                (:documents sections)
                (conj (str (h/html (views/document-list-section
                                    contract-id (:documents state)))))
                (:parties sections)
                (conj (str (h/html (views/parties-section
                                    contract-id (:contract state)))))
                (:origination-form sections)
                (conj (str (h/html (views/origination-form contract-id state)))))]
    {:fragments frags
     :signals (merge (when close-modal {close-modal false})
                     signals)}))

;; ============================================================
;; Contract List Handlers
;; ============================================================

(defn list-contracts-handler
  "Handle GET /contracts - list all contracts.

   Note: Status filtering is not currently supported because status is derived
   from payments and requires computing contract-state for each contract.
   All contracts are listed; status is shown on detail page.

   Returns: HTML page with contract list"
  [request]
  (try
    (let [db (d/db (:conn request))
          contracts (contract/list-contracts db)]

      (log/info "Listed contracts" {:count (count contracts)})

      (-> (response/response
           (views/contract-list-page contracts))
          (response/content-type "text/html; charset=utf-8")))

    (catch Exception e
      (log/error e "Error listing contracts")
      (-> (response/response (views/error-500-page e))
          (response/status 500)))))

;; ============================================================
;; Contract Detail Handlers
;; ============================================================

(defn view-contract-handler
  "Handle GET /contracts/:id - view contract detail.

   Path params:
   - id: contract UUID

   Supports flash messages for displaying errors/success after redirects.

   Returns: HTML page with contract detail"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str)
                           (catch Exception _ nil))
          flash (:flash request)]

      (if-not contract-id
        (do
          (log/warn "Invalid contract ID" {:id contract-id-str})
          (-> (response/response (views/error-404-page))
              (response/status 404)
              (response/content-type "text/html; charset=utf-8")))

        (let [db (d/db (:conn request))
              c (contract/get-contract db contract-id)]

          (if-not c
            (do
              (log/warn "Contract not found" {:id contract-id})
              (-> (response/response (views/error-404-page))
                  (response/status 404)
                  (response/content-type "text/html; charset=utf-8")))

            (let [state (contract/contract-state db contract-id (java.util.Date.))]
              (log/info "Viewed contract" {:id contract-id
                                           :external-id (get-in state [:contract :external-id])})

              (-> (response/response
                   (views/contract-detail-page state flash))
                  (response/content-type "text/html; charset=utf-8")))))))

    (catch Exception e
      (log/error e "Error viewing contract")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

;; ============================================================
;; Home Page Handler
;; ============================================================

(defn home-handler
  "Handle GET / - redirect to contracts list."
  [_request]
  (response/redirect "/contracts"))

;; ============================================================
;; Payment Handlers
;; ============================================================

(defn preview-payment-handler
  "Handle POST /contracts/:id/preview-payment - show payment allocation preview.

   Form params:
   - amount: payment amount in SAR

   Returns: HTML fragment with preview"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          amount-str (get-in request [:form-params "amount"])
          amount (when amount-str
                   (try (bigdec amount-str) (catch Exception _ nil)))]

      (log/info "Preview payment request" {:contract-id contract-id-str
                                           :amount-str amount-str
                                           :form-params (:form-params request)})

      (if (and contract-id amount (pos? amount))
        (let [preview (ops/preview-payment (:conn request) contract-id amount)]
          (log/info "Preview generated" {:changes (count (:changes preview))})
          (sse-response request (str (h/html (views/payment-preview preview)))))
        (do
          (log/warn "Invalid preview params" {:contract-id contract-id
                                              :amount amount})
          (sse-response request "<div id=\"preview-area\"></div>"))))

    (catch Exception e
      (log/error e "Error previewing payment")
      (sse-response request (str "<div id=\"preview-area\" class=\"preview-section\" style=\"color: red;\">Error: " (.getMessage e) "</div>")))))

(defn record-payment-handler
  "Handle POST /contracts/:id/record-payment - record payment to contract.

   Creates a payment/* entity with the given business date and amount.

   Form params:
   - amount: payment amount in SAR
   - reference: payment reference/ID
   - note: optional note about payment
   - original-date: business date — when money was received (YYYY-MM-DD)

   Returns: Redirect to contract detail page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          amount-str (get-in request [:form-params "amount"])
          reference (get-in request [:form-params "reference"])
          note (get-in request [:form-params "note"])
          date-str (get-in request [:form-params "original-date"])
          amount (when amount-str
                   (try (bigdec amount-str) (catch Exception _ nil)))
          date (when date-str
                 (try (dates/->date (dates/parse-date date-str))
                      (catch Exception _ nil)))]

      (if (and contract-id amount (pos? amount) reference date)
        (do
          (ops/record-payment (:conn request) contract-id amount date reference "web-user"
                              :note (when-not (str/blank? note) note))
          (log/info "Payment recorded" {:contract-id contract-id
                                        :amount amount
                                        :reference reference
                                        :date date
                                        :note note})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message (str "Payment of SAR " amount " recorded")}
                                       :close-modal "showPaymentModal"})))

        (do
          (log/warn "Invalid payment data" {:contract-id contract-id-str
                                            :amount amount-str
                                            :reference reference
                                            :date date-str})
          (sse-error request "Invalid payment data. Check all required fields."))))

    (catch Exception e
      (log/error e "Error recording payment")
      (sse-error request (str "Error recording payment: " (.getMessage e))))))

(defn retract-payment-handler
  "Handle POST /contracts/:id/retract-payment - retract a payment (data correction).

   Uses [:db/retractEntity] to remove the payment entity. TX metadata records
   the correction reason and who performed it. Datomic history preserves
   retracted datoms for forensics.

   Form params:
   - payment-id: UUID of payment to retract
   - reason: correction reason keyword (correction, duplicate-removal, erroneous-entry)
   - note: optional free-text explanation

   Returns: Redirect to contract detail page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          payment-id-str (get-in request [:form-params "payment-id"])
          payment-id (when payment-id-str
                       (try (parse-uuid payment-id-str) (catch Exception _ nil)))
          reason-str (get-in request [:form-params "reason"])
          reason (when (and reason-str (not (str/blank? reason-str)))
                   (keyword reason-str))
          note (get-in request [:form-params "note"])]

      (if (and contract-id payment-id reason)
        (do
          (ops/retract-payment (:conn request) payment-id reason "web-user"
                               :note (when-not (str/blank? note) note))
          (log/info "Payment retracted" {:contract-id contract-id
                                         :payment-id payment-id
                                         :reason reason})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message "Payment retracted"}
                                       :close-modal "showRetractPaymentModal"})))

        (do
          (log/warn "Invalid retraction data" {:contract-id contract-id-str
                                               :payment-id payment-id-str
                                               :reason reason-str})
          (sse-error request "Invalid retraction data. Please select a reason."))))

    (catch Exception e
      (log/error e "Error retracting payment")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn retract-contract-handler
  "Handle POST /contracts/:id/retract-contract - retract a contract (data correction).

   Retracts the contract and ALL associated entities (fees, installments,
   payments, disbursements, deposits, inflows, outflows) atomically.
   TX metadata records the correction reason and who performed it.
   Datomic history preserves all retracted datoms for forensics.

   Form params:
   - reason: correction reason keyword (correction, duplicate-removal, erroneous-entry)
   - note: optional free-text explanation

   Returns: Redirect to contracts list"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          reason-str (get-in request [:form-params "reason"])
          reason (when (and reason-str (not (str/blank? reason-str)))
                   (keyword reason-str))
          note (get-in request [:form-params "note"])]

      (if (and contract-id reason)
        (do
          (ops/retract-contract (:conn request) contract-id reason "web-user"
                                :note (when-not (str/blank? note) note))
          (log/info "Contract retracted" {:contract-id contract-id
                                          :reason reason})
          (sse-redirect request "/contracts"))

        (do
          (log/warn "Invalid contract retraction data" {:contract-id contract-id-str
                                                        :reason reason-str})
          (sse-error request "Invalid retraction data. Please select a reason."))))

    (catch Exception e
      (log/error e "Error retracting contract")
      (sse-error request (str "Error: " (.getMessage e))))))

;; ============================================================
;; Settlement Handlers
;; ============================================================

(defn calculate-settlement-handler
  "Handle POST /contracts/:id/calculate-settlement — compute settlement breakdown.

   Settlement is a read-only derivation: what does the customer owe to close
   the contract on a given date? Nothing is stored.

   Form params:
   - settlement-date: date string (YYYY-MM-DD)
   - penalty-days: integer (days of profit as penalty)
   - manual-override: optional bigdec override for accrued unpaid profit

   Returns: HTML fragment (settlement-result) for HTMX swap"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          date-str (get-in request [:form-params "settlement-date"])
          penalty-str (get-in request [:form-params "penalty-days"])
          override-str (get-in request [:form-params "manual-override"])
          settlement-date (when date-str
                            (try (dates/->date (dates/parse-date date-str))
                                 (catch Exception _ nil)))
          penalty-days (when penalty-str
                         (try (Integer/parseInt penalty-str)
                              (catch Exception _ nil)))
          manual-override (when (and override-str (not (str/blank? override-str)))
                            (try (bigdec override-str)
                                 (catch Exception _ nil)))]

      (if (and contract-id settlement-date penalty-days)
        (let [db (d/db (:conn request))
              state (contract/contract-state db contract-id settlement-date)
              result (if manual-override
                       (settlement/calculate-settlement state settlement-date penalty-days
                                                        :manual-override manual-override)
                       (settlement/calculate-settlement state settlement-date penalty-days))]
          (log/info "Settlement calculated" {:contract-id contract-id
                                             :date date-str
                                             :penalty-days penalty-days
                                             :settlement-amount (:settlement-amount result)})
          (sse-response request (str (h/html (views/settlement-result result)))))

        (do
          (log/warn "Invalid settlement params" {:contract-id contract-id-str
                                                 :date date-str
                                                 :penalty-days penalty-str})
          (sse-response request "<div id=\"settlement-result-area\" style=\"padding: 1rem; color: #991b1b; background: #fef2f2; border-radius: 0.375rem;\">Please provide a valid date and penalty days.</div>"))))

    (catch Exception e
      (log/error e "Error calculating settlement")
      (sse-response request (str "<div id=\"settlement-result-area\" style=\"padding: 1rem; color: #991b1b; background: #fef2f2; border-radius: 0.375rem;\">Error: " (.getMessage e) "</div>")))))

;; ============================================================
;; Boarding Handlers
;; ============================================================

(defn- parse-fee-params
  "Extract fee data from repeating form fields.

   Ring collects same-named params as vectors when multiple values exist,
   or as a single string when only one. This normalizes both cases.

   Form params: fee-type[], fee-amount[], fee-days-after-disbursement[]

   Returns: Sequence of fee maps with :fee/* keys"
  [form-params]
  (let [normalize (fn [v] (if (string? v) [v] (vec v)))
        types (some-> (get form-params "fee-type[]") normalize)
        amounts (some-> (get form-params "fee-amount[]") normalize)
        days (some-> (get form-params "fee-days-after-disbursement[]") normalize)]
    (when (and types amounts days)
      (->> (map vector types amounts days)
           (remove (fn [[_ amt _]] (str/blank? amt)))
           (mapv (fn [[type-str amount-str days-str]]
                   {:fee/type (keyword type-str)
                    :fee/amount (bigdec amount-str)
                    :fee/days-after-disbursement (parse-long days-str)}))))))

(defn- parse-contract-params
  "Extract contract data from form params.

   Returns: Map with :contract/* keys (only non-blank values included)"
  [form-params]
  (let [get-param (fn [k] (let [v (get form-params k)]
                            (when-not (str/blank? v) v)))]
    ;; Note: status is derived (not stored), maturity-date is derived from installments
    (cond-> {:contract/external-id (get-param "external-id")
             :contract/borrower (when-let [pid (get-param "borrower-party-id")]
                                  [:party/id (parse-uuid pid)])
             :contract/principal (when-let [p (get-param "principal")]
                                   (bigdec p))}

      (get-param "security-deposit")
      (assoc :contract/security-deposit (bigdec (get-param "security-deposit")))

      (get-param "facility-id")
      (assoc :contract/facility [:facility/external-id (get-param "facility-id")])

      (get-param "commodity-description")
      (assoc :contract/commodity-description (get-param "commodity-description"))

      (get-param "commodity-vendor")
      (assoc :contract/commodity-vendor (get-param "commodity-vendor"))

      (get-param "commodity-quantity")
      (assoc :contract/commodity-quantity (bigdec (get-param "commodity-quantity")))

      (get-param "commodity-unit-price")
      (assoc :contract/commodity-unit-price (bigdec (get-param "commodity-unit-price")))

      (get-param "disbursement-iban")
      (assoc :contract/disbursement-iban (get-param "disbursement-iban"))

      (get-param "disbursement-bank")
      (assoc :contract/disbursement-bank (get-param "disbursement-bank"))

      (get-param "virtual-iban")
      (assoc :contract/virtual-iban (get-param "virtual-iban"))

      (get-param "days-to-first-installment")
      (assoc :contract/days-to-first-installment (Long/parseLong (get-param "days-to-first-installment"))))))

(defn- read-upload
  "Read content from a multipart file upload param.

   Ring wraps uploads as {:tempfile File :filename String :content-type String :size Long}.
   Returns the file content as a string, or nil if no file uploaded."
  [param]
  (when (and param (:tempfile param) (pos? (:size param)))
    (slurp (:tempfile param))))

(defn new-contract-handler
  "Handle GET /contracts/new — render boarding form.

   Query params:
   - type: \"new\" (default) or \"existing\"

   Returns: HTML page with boarding form"
  [request]
  (let [type (or (get-in request [:query-params "type"]) "new")]
    (-> (response/response (views/boarding-form-page type))
        (response/content-type "text/html; charset=utf-8"))))

(defn board-contract-handler
  "Handle POST /contracts/board — board a new contract.

   Extracts contract data, fees, and schedule CSV from form/multipart params.
   Calls boarding/board-new-contract.

   On success: redirects to contract detail page.
   On failure: re-renders form with validation errors.

   Returns: Redirect or HTML page"
  [request]
  (try
    (let [params (merge (:form-params request) (:multipart-params request))
          contract-data (parse-contract-params params)
          fees (or (parse-fee-params params) [])
          schedule-csv (read-upload (get params "schedule-csv"))
          principal (:contract/principal contract-data)]

      (if-not (and schedule-csv principal)
        (-> (response/response
             (views/boarding-form-page "new"
                                       {:errors [{:field :schedule-csv :message "Schedule CSV file is required"}
                                                 (when-not principal
                                                   {:field :contract/principal :message "Principal is required"})]
                                        :values params}))
            (response/content-type "text/html; charset=utf-8"))

        (let [installments (boarding/parse-schedule-csv schedule-csv principal)
              result (boarding/board-new-contract (:conn request) contract-data fees installments "web-user")]

          (if (:success? result)
            (do
              (log/info "Contract boarded via web" {:contract-id (:contract-id result)
                                                    :external-id (:contract/external-id contract-data)})
              (response/redirect (str "/contracts/" (:contract-id result))))

            (do
              (log/warn "Boarding validation failed" {:errors (:errors result)})
              (-> (response/response
                   (views/boarding-form-page "new"
                                             {:errors (:errors result) :values params}))
                  (response/content-type "text/html; charset=utf-8")))))))

    (catch Exception e
      (log/error e "Error boarding contract")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

(defn board-existing-contract-handler
  "Handle POST /contracts/board-existing — board an existing loan with payment history.

   Same as board-contract-handler, plus:
   - Parses payment history CSV
   - Extracts historical disbursement details
   - Calls boarding/board-existing-contract

   Returns: Redirect or HTML page"
  [request]
  (try
    (let [params (merge (:form-params request) (:multipart-params request))
          contract-data (parse-contract-params params)
          fees (or (parse-fee-params params) [])
          schedule-csv (read-upload (get params "schedule-csv"))
          payment-csv (read-upload (get params "payment-csv"))
          principal (:contract/principal contract-data)

          ;; Parse disbursement fields
          get-param (fn [k] (let [v (get params k)]
                              (when-not (str/blank? v) v)))
          disbursement (when (get-param "disb-amount")
                         {:amount (bigdec (get-param "disb-amount"))
                          :date (when (get-param "disb-date")
                                  (dates/->date (dates/parse-date (get-param "disb-date"))))
                          :reference (or (get-param "disb-reference") "BOARDING-HISTORICAL")
                          :iban (get-param "disb-iban")
                          :bank (get-param "disb-bank")})]

      (if-not (and schedule-csv principal)
        (-> (response/response
             (views/boarding-form-page "existing"
                                       {:errors [{:field :schedule-csv :message "Schedule CSV file is required"}
                                                 (when-not principal
                                                   {:field :contract/principal :message "Principal is required"})]
                                        :values params}))
            (response/content-type "text/html; charset=utf-8"))

        (let [installments (boarding/parse-schedule-csv schedule-csv principal)
              payments (if payment-csv
                         (boarding/parse-payment-csv payment-csv)
                         [])
              result (boarding/board-existing-contract
                      (:conn request) contract-data fees installments
                      payments disbursement "web-user")]

          (if (:success? result)
            (do
              (log/info "Existing contract boarded via web"
                        {:contract-id (:contract-id result)
                         :external-id (:contract/external-id contract-data)
                         :payments-processed (:payments-processed result)
                         :payments-skipped (:payments-skipped result)})
              (response/redirect (str "/contracts/" (:contract-id result))))

            (do
              (log/warn "Existing boarding validation failed" {:errors (:errors result)})
              (-> (response/response
                   (views/boarding-form-page "existing"
                                             {:errors (:errors result) :values params}))
                  (response/content-type "text/html; charset=utf-8")))))))

    (catch Exception e
      (log/error e "Error boarding existing contract")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

;; ============================================================
;; Origination Handlers
;; ============================================================

(defn originate-handler
  "Handle POST /contracts/:id/originate — execute funding-day operations.

   Each origination step is a separate business fact, called individually.
   Steps: funding inflow → borrower disbursement → deposit (optional) →
   settlement (optional) → refund (optional) → set disbursed-at.

   Fee settlement, deposit funding, and installment prepayment are NOT
   explicit steps — the waterfall derives them from available funds.

   Form params:
   - date: origination date (YYYY-MM-DD) — required
   - disbursement-amount: amount wired to borrower — required
   - disbursement-reference: wire transfer reference — required
   - disbursement-iban: destination IBAN
   - disbursement-bank: destination bank
   - deposit-from-funding: deposit amount deducted from principal
   - refund-amount: excess returned to customer

   Returns: SSE response updating contract page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          conn (:conn request)
          params (:form-params request)
          get-param (fn [k] (let [v (get params k)]
                              (when-not (str/blank? v) v)))
          date-str (get-param "date")
          date (when date-str
                 (try (dates/->date (dates/parse-date date-str))
                      (catch Exception _ nil)))
          disbursement-amount (when-let [s (get-param "disbursement-amount")]
                                (try (bigdec s) (catch Exception _ nil)))
          disbursement-reference (get-param "disbursement-reference")
          disbursement-iban (get-param "disbursement-iban")
          disbursement-bank (get-param "disbursement-bank")
          deposit-from-funding (when-let [s (get-param "deposit-from-funding")]
                                 (try (bigdec s) (catch Exception _ nil)))
          refund-amount (when-let [s (get-param "refund-amount")]
                          (try (bigdec s) (catch Exception _ nil)))
          db (d/db conn)
          contract (contract/get-contract db contract-id)
          principal (:contract/principal contract)
          steps (atom [])]

      (if (and contract-id date disbursement-amount disbursement-reference)
        (do
          ;; 1. Funding inflow — principal enters waterfall
          (ops/record-funding-inflow conn contract-id principal date "web-user")
          (swap! steps conj :funding-inflow)

          ;; 2. Borrower disbursement (with outflow component)
          (ops/record-disbursement conn contract-id disbursement-amount date
                                   disbursement-reference "web-user"
                                   :iban disbursement-iban
                                   :bank disbursement-bank)
          (swap! steps conj :borrower-disbursement)

          ;; 3. Deposit from funding (optional — deposit ledger entity)
          (when (and deposit-from-funding (pos? deposit-from-funding))
            (ops/receive-deposit conn contract-id deposit-from-funding date "web-user"
                                 :source :funding)
            (swap! steps conj :deposit))

          ;; 4. Refund (optional — excess returned to customer)
          (when (and refund-amount (pos? refund-amount))
            (ops/record-refund conn contract-id refund-amount date
                               (str disbursement-reference "-REFUND") "web-user")
            (swap! steps conj :refund))

          ;; 5. Set disbursed-at + shift installment dates
          (ops/set-disbursed-at conn contract-id date "web-user")
          (swap! steps conj :disbursed-at)

          (log/info "Contract originated via web" {:contract-id contract-id
                                                   :steps @steps})
          (sse-ok request
                  (contract-fragments conn contract-id
                                      {:flash {:type :success
                                               :message (str "Contract originated. Steps: "
                                                             (str/join ", " (map name @steps)))}
                                       :close-modal "showOriginationModal"})))

        (do
          (log/warn "Invalid origination data" {:contract-id contract-id-str
                                                :date date-str
                                                :disbursement-amount (get-param "disbursement-amount")
                                                :disbursement-reference (get-param "disbursement-reference")})
          (sse-error request "Invalid origination data. Date, disbursement amount, and reference are required."))))

    (catch Exception e
      (log/error e "Error originating contract")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn retract-origination-handler
  "Handle POST /contracts/:id/retract-origination — retract origination entities.

   Retracts all origination-created entities individually:
   funding inflows, borrower disbursements, deposits from funding,
   settlement outflows/inflows, refund disbursements, and unsets disbursed-at.

   Form params:
   - reason: correction reason keyword (correction, duplicate-removal, erroneous-entry)
   - note: optional free-text explanation

   Returns: SSE response updating contract page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          conn (:conn request)
          reason-str (get-in request [:form-params "reason"])
          reason (when (and reason-str (not (str/blank? reason-str)))
                   (keyword reason-str))
          note (get-in request [:form-params "note"])
          note-text (when-not (str/blank? note) note)]

      (if (and contract-id reason)
        (let [db (d/db conn)
              inflows (contract/get-inflows db contract-id)
              outflows (contract/get-outflows db contract-id)
              disbursements (contract/get-disbursements db contract-id)
              deposits (contract/get-deposits db contract-id)
              steps (atom [])]

          ;; Retract funding inflows
          (doseq [i (filter #(= :funding (:inflow/source %)) inflows)]
            (ops/retract-inflow conn (:inflow/id i) reason "web-user" :note note-text)
            (swap! steps conj :retract-funding-inflow))

          ;; Retract settlement outflows (also retracts inflow on old contract)
          (doseq [o (filter #(= :settlement (:outflow/type %)) outflows)]
            (ops/retract-settlement conn (:outflow/id o) "web-user" reason :note note-text)
            (swap! steps conj :retract-settlement))

          ;; Retract funding disbursements (component outflows cascade)
          (doseq [d (filter #(#{:funding :refund} (:disbursement/type %)) disbursements)]
            (ops/retract-disbursement conn (:disbursement/id d) reason "web-user" :note note-text)
            (swap! steps conj :retract-disbursement))

          ;; Retract funding deposits
          (doseq [d (filter #(= :funding (:deposit/source %)) deposits)]
            (ops/retract-deposit conn (:deposit/id d) reason "web-user" :note note-text)
            (swap! steps conj :retract-deposit))

          ;; Unset disbursed-at
          (when (:contract/disbursed-at (d/pull (d/db conn) [:contract/disbursed-at]
                                                [:contract/id contract-id]))
            (ops/unset-disbursed-at conn contract-id "web-user" :note note-text)
            (swap! steps conj :unset-disbursed-at))

          (log/info "Origination retracted" {:contract-id contract-id
                                             :reason reason
                                             :steps @steps})
          (sse-ok request
                  (contract-fragments conn contract-id
                                      {:flash {:type :success :message "Origination retracted successfully."}
                                       :close-modal "showRetractOriginationModal"
                                       :sections #{:summary :fees :installments :origination-form}})))

        (do
          (log/warn "Invalid retraction data" {:contract-id contract-id-str
                                               :reason reason-str})
          (sse-error request "Please select a reason for correction."))))

    (catch Exception e
      (log/error e "Error retracting origination")
      (sse-error request (str "Error: " (.getMessage e))))))

;; ============================================================
;; History Tab Handler
;; ============================================================

(defn history-tab-handler
  "Handle GET /contracts/:id/history-tab — SSE fragment for history tab.

   Query params:
   - page: page number (default 1)
   - entity-types: comma-separated (payment,installment,fee,...)
   - from-date: filter from date (YYYY-MM-DD)
   - to-date: filter to date (YYYY-MM-DD)

   Returns: SSE fragment morphed into tab content area."
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          params (:query-params request)
          page (try (Integer/parseInt (or (get params "page") "1"))
                    (catch Exception _ 1))
          per-page 25
          entity-types-str (get params "entity-types")
          entity-types (when (and entity-types-str (not (str/blank? entity-types-str)))
                         (set (map keyword (str/split entity-types-str #","))))
          from-date (when-let [d (get params "from-date")]
                      (try (dates/->date (dates/parse-date d))
                           (catch Exception _ nil)))
          to-date (when-let [d (get params "to-date")]
                    (try (dates/->date (dates/parse-date d))
                         (catch Exception _ nil)))]

      (if-not contract-id
        (sse-response request "<div id=\"history-tab-content\">Invalid contract ID</div>")

        (let [conn (:conn request)
              db (d/db conn)
              raw-history (contract/get-comprehensive-history
                           conn db contract-id
                           {:entity-types entity-types
                            :from-date from-date
                            :to-date to-date})
              entity-ids (contract/get-contract-entity-ids db contract-id)
              entity-labels (contract/build-entity-label-cache db entity-ids)
              formatted (contract/format-history-for-display raw-history entity-labels)
              ;; Paginate (most recent first)
              reversed (vec (reverse formatted))
              total (count reversed)
              total-pages (max 1 (int (Math/ceil (/ total (double per-page)))))
              safe-page (min page total-pages)
              offset (* (dec safe-page) per-page)
              page-items (subvec reversed
                                 (min offset total)
                                 (min (+ offset per-page) total))
              filters {:entity-types entity-types
                       :from-date (get params "from-date")
                       :to-date (get params "to-date")}
              pagination {:page safe-page
                          :per-page per-page
                          :total total
                          :total-pages total-pages}]

          (log/info "History tab loaded" {:contract-id contract-id
                                          :total-txs total
                                          :page safe-page})

          (sse-response request
                        (str (h/html
                              (views/history-tab-content
                               contract-id page-items filters pagination)))))))

    (catch Throwable e
      (log/error e "Error loading history tab")
      (sse-response request
                    (str "<div id=\"history-tab-content\" style=\"padding: 1rem; color: #991b1b; background: #fef2f2; border-radius: 0.375rem;\">Error loading history: "
                         (.getMessage e) "</div>")))))

;; ============================================================
;; Party Handlers
;; ============================================================

(defn list-parties-handler
  "Handle GET /parties - list all parties.

   Query params:
   - type: optional filter — \"company\" or \"person\"

   Returns: HTML page with party list"
  [request]
  (try
    (let [db (d/db (:conn request))
          type-str (get-in request [:query-params "type"])
          party-type (when type-str
                       (keyword "party.type" type-str))
          parties (party/list-parties db :type party-type)]
      (log/info "Listed parties" {:count (count parties) :type party-type})
      (-> (response/response
           (views/party-list-page parties))
          (response/content-type "text/html; charset=utf-8")))
    (catch Exception e
      (log/error e "Error listing parties")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

(defn new-party-handler
  "Handle GET /parties/new - render party creation form.

   Returns: HTML page with party form"
  [_request]
  (-> (response/response (views/party-form-page nil))
      (response/content-type "text/html; charset=utf-8")))

(defn create-party-handler
  "Handle POST /parties - create a new party.

   Form params:
   - type: \"company\" or \"person\"
   - legal-name: party legal name
   - cr-number: Commercial Registration (companies)
   - national-id: National ID (persons)
   - email, phone, address: optional contact info

   Returns: Redirect to party detail or re-render form with errors"
  [request]
  (try
    (let [params (:form-params request)
          get-param (fn [k] (let [v (get params k)]
                              (when-not (str/blank? v) v)))
          party-type (when-let [t (get-param "type")]
                       (keyword "party.type" t))
          data (cond-> {:party/type party-type
                        :party/legal-name (get-param "legal-name")}
                 (get-param "cr-number")
                 (assoc :party/cr-number (get-param "cr-number"))

                 (get-param "national-id")
                 (assoc :party/national-id (get-param "national-id"))

                 (get-param "email")
                 (assoc :party/email (get-param "email"))

                 (get-param "phone")
                 (assoc :party/phone (get-param "phone"))

                 (get-param "address")
                 (assoc :party/address (get-param "address")))
          validation (party/validate-party-data data)]

      (if-not (:valid? validation)
        (do
          (log/warn "Party validation failed" {:errors (:errors validation)})
          (-> (response/response
               (views/party-form-page nil {:errors (:errors validation) :values params}))
              (response/content-type "text/html; charset=utf-8")))

        (let [result (party/create-party (:conn request) data "web-user")
              party-id (:party-id result)]
          (log/info "Party created" {:party-id party-id
                                     :type party-type
                                     :name (get-param "legal-name")})
          (response/redirect (str "/parties/" party-id)))))

    (catch Exception e
      (log/error e "Error creating party")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

(defn view-party-handler
  "Handle GET /parties/:id - view party detail.

   Path params:
   - id: party UUID

   Returns: HTML page with party detail"
  [request]
  (try
    (let [party-id-str (get-in request [:path-params :id])
          party-id (try (parse-uuid party-id-str) (catch Exception _ nil))]

      (if-not party-id
        (-> (response/response (views/error-404-page))
            (response/status 404)
            (response/content-type "text/html; charset=utf-8"))

        (let [db (d/db (:conn request))
              p (party/get-party db party-id)]

          (if-not p
            (-> (response/response (views/error-404-page))
                (response/status 404)
                (response/content-type "text/html; charset=utf-8"))

            (let [contracts (party/get-party-contracts db party-id)
                  ownerships (if (= :party.type/company (:party/type p))
                               (party/get-ownership db party-id)
                               [])
                  owns (party/get-ownerships-for-party db party-id)]
              (log/info "Viewed party" {:id party-id
                                        :name (:party/legal-name p)})
              (-> (response/response
                   (views/party-detail-page p contracts ownerships owns))
                  (response/content-type "text/html; charset=utf-8")))))))

    (catch Exception e
      (log/error e "Error viewing party")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

(defn update-party-handler
  "Handle POST /parties/:id/update - update party mutable fields.

   Form params:
   - legal-name, email, phone, address

   Returns: SSE redirect to party detail on success, SSE error on failure"
  [request]
  (try
    (let [party-id-str (get-in request [:path-params :id])
          party-id (try (parse-uuid party-id-str) (catch Exception _ nil))
          params (:form-params request)
          get-param (fn [k] (let [v (get params k)]
                              (when-not (str/blank? v) v)))
          updates (cond-> {}
                    (get-param "legal-name")
                    (assoc :party/legal-name (get-param "legal-name"))

                    (get-param "email")
                    (assoc :party/email (get-param "email"))

                    (get-param "phone")
                    (assoc :party/phone (get-param "phone"))

                    (get-param "address")
                    (assoc :party/address (get-param "address")))]

      (if-not party-id
        (sse-error request "Invalid party ID.")

        (do
          (party/update-party (:conn request) party-id updates "web-user")
          (log/info "Party updated" {:party-id party-id :fields (keys updates)})
          (sse-redirect request (str "/parties/" party-id)))))

    (catch Exception e
      (log/error e "Error updating party")
      (sse-error request (str "Error updating party: " (.getMessage e))))))

(defn add-guarantor-handler
  "Handle POST /contracts/:id/guarantors - add guarantor to contract.

   Form params:
   - party-id: UUID of party to add as guarantor

   Returns: SSE response updating parties section"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          party-id-str (get-in request [:form-params "party-id"])
          party-id (when party-id-str
                     (try (parse-uuid party-id-str) (catch Exception _ nil)))]

      (if (and contract-id party-id)
        (do
          (party/add-guarantor (:conn request) contract-id party-id "web-user")
          (log/info "Guarantor added" {:contract-id contract-id :party-id party-id})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message "Guarantor added"}
                                       :sections #{:parties}
                                       :signals {"guarantorPartyId" ""
                                                 "guarantorSearch" ""
                                                 "showGuarantorResults" false}})))

        (sse-error request "Invalid party ID.")))

    (catch Exception e
      (log/error e "Error adding guarantor")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn remove-guarantor-handler
  "Handle POST /contracts/:id/guarantors/:party-id/remove - remove guarantor.

   Returns: SSE response updating parties section"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          party-id-str (get-in request [:path-params :party-id])
          party-id (when party-id-str
                     (try (parse-uuid party-id-str) (catch Exception _ nil)))]

      (if (and contract-id party-id)
        (do
          (party/remove-guarantor (:conn request) contract-id party-id "web-user")
          (log/info "Guarantor removed" {:contract-id contract-id :party-id party-id})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message "Guarantor removed"}
                                       :sections #{:parties}})))

        (sse-error request "Invalid party ID.")))

    (catch Exception e
      (log/error e "Error removing guarantor")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn add-signatory-handler
  "Handle POST /contracts/:id/signatories - add authorized signatory.

   Form params:
   - party-id: UUID of person party to add as signatory

   Returns: SSE response updating parties section"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          party-id-str (get-in request [:form-params "party-id"])
          party-id (when party-id-str
                     (try (parse-uuid party-id-str) (catch Exception _ nil)))]

      (if (and contract-id party-id)
        (do
          (party/add-signatory (:conn request) contract-id party-id "web-user")
          (log/info "Signatory added" {:contract-id contract-id :party-id party-id})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message "Authorized signatory added"}
                                       :sections #{:parties}
                                       :signals {"signatoryPartyId" ""
                                                 "signatorySearch" ""
                                                 "showSignatoryResults" false}})))

        (sse-error request "Invalid party ID.")))

    (catch Exception e
      (log/error e "Error adding signatory")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn remove-signatory-handler
  "Handle POST /contracts/:id/signatories/:party-id/remove - remove signatory.

   Returns: SSE response updating parties section"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          party-id-str (get-in request [:path-params :party-id])
          party-id (when party-id-str
                     (try (parse-uuid party-id-str) (catch Exception _ nil)))]

      (if (and contract-id party-id)
        (do
          (party/remove-signatory (:conn request) contract-id party-id "web-user")
          (log/info "Signatory removed" {:contract-id contract-id :party-id party-id})
          (sse-ok request
                  (contract-fragments (:conn request) contract-id
                                      {:flash {:type :success :message "Authorized signatory removed"}
                                       :sections #{:parties}})))

        (sse-error request "Invalid party ID.")))

    (catch Exception e
      (log/error e "Error removing signatory")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn- ownership-fragments
  "Re-query ownership data and render updated ownership section.
   Returns map with :fragments and :signals for sse-ok."
  [conn company-id flash & {:keys [signals]}]
  (let [db (d/db conn)
        ownerships (party/get-ownership db company-id)]
    {:fragments (cond-> []
                  flash
                  (conj (str (h/html (views/flash-message flash))))
                  true
                  (conj (str (h/html (views/ownership-section company-id ownerships)))))
     :signals signals}))

(defn add-ownership-handler
  "Handle POST /parties/:id/ownership - record ownership stake.

   Form params:
   - owner-party-id: UUID of owner party
   - percentage: ownership percentage

   Returns: SSE response updating ownership section"
  [request]
  (try
    (let [company-id-str (get-in request [:path-params :id])
          company-id (try (parse-uuid company-id-str) (catch Exception _ nil))
          params (:form-params request)
          owner-id-str (get params "owner-party-id")
          owner-id (when owner-id-str
                     (try (parse-uuid owner-id-str) (catch Exception _ nil)))
          pct-str (get params "percentage")
          percentage (when pct-str
                       (try (bigdec pct-str) (catch Exception _ nil)))]

      (if (and company-id owner-id percentage)
        (let [db (d/db (:conn request))
              validation (party/validate-ownership db {:owner-id owner-id
                                                       :company-id company-id
                                                       :percentage percentage})]
          (if-not (:valid? validation)
            (sse-error request (str/join "; " (map :message (:errors validation))))

            (do
              (party/record-ownership (:conn request) owner-id company-id percentage "web-user")
              (log/info "Ownership recorded" {:company company-id :owner owner-id :pct percentage})
              (sse-ok request
                      (ownership-fragments (:conn request) company-id
                                           {:type :success :message "Ownership recorded"}
                                           :signals {"ownerPartyId" ""
                                                     "ownerSearch" ""
                                                     "showOwnerResults" false})))))

        (sse-error request "Invalid ownership data.")))

    (catch Exception e
      (log/error e "Error recording ownership")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn remove-ownership-handler
  "Handle POST /parties/:id/ownership/:ownership-id/remove - remove ownership record.

   Returns: SSE response updating ownership section"
  [request]
  (try
    (let [company-id-str (get-in request [:path-params :id])
          company-id (try (parse-uuid company-id-str) (catch Exception _ nil))
          ownership-id-str (get-in request [:path-params :ownership-id])
          ownership-id (when ownership-id-str
                         (try (parse-uuid ownership-id-str) (catch Exception _ nil)))]

      (if (and company-id ownership-id)
        (do
          (party/remove-ownership (:conn request) ownership-id "web-user")
          (log/info "Ownership removed" {:ownership-id ownership-id})
          (sse-ok request
                  (ownership-fragments (:conn request) company-id
                                       {:type :success :message "Ownership removed"})))

        (sse-error request "Invalid ownership ID.")))

    (catch Exception e
      (log/error e "Error removing ownership")
      (sse-error request (str "Error: " (.getMessage e))))))

(defn search-parties-handler
  "Handle GET /api/parties/search - search parties for Datastar autocomplete.

   Query params:
   - q: search query (matches against legal-name, cr-number, national-id)
   - type: optional filter — \"company\" or \"person\"
   - target: DOM element ID to patch results into (default \"borrower-results\")
   - context: signal prefix — \"borrower\" or \"owner\" (default \"borrower\")

   Returns: SSE fragment with matching party options"
  [request]
  (try
    (let [db (d/db (:conn request))
          query (get-in request [:query-params "q"])
          type-str (get-in request [:query-params "type"])
          target (or (get-in request [:query-params "target"]) "borrower-results")
          context (or (get-in request [:query-params "context"]) "borrower")
          id-signal (str "$" context "PartyId")
          name-signal (str "$" context "Search")
          results-signal (str "$show" (str/capitalize context) "Results")
          party-type (when type-str (keyword "party.type" type-str))
          all-parties (party/list-parties db :type party-type)
          matches (if (str/blank? query)
                    all-parties
                    (let [q (str/lower-case query)]
                      (filter (fn [p]
                                (or (str/includes? (str/lower-case (or (:party/legal-name p) "")) q)
                                    (str/includes? (str/lower-case (or (:party/cr-number p) "")) q)
                                    (str/includes? (str/lower-case (or (:party/national-id p) "")) q)))
                              all-parties)))]
      (sse-response request
                    (str (h/html
                          [:div {:id target
                                 :style "border: 1px solid var(--lms-border); border-radius: var(--lms-radius-md); max-height: 200px; overflow-y: auto; background: var(--lms-card); box-shadow: var(--lms-shadow-lg);"
                                 "data-show" results-signal}
                           (if (empty? matches)
                             [:div.text-muted {:style "padding: 0.5rem;"} "No parties found."]
                             (for [p matches]
                               [:div {:class "party-search-result"
                                      "data-on:click" (str id-signal " = '" (:party/id p)
                                                           "'; " name-signal " = '" (:party/legal-name p)
                                                           "'; " results-signal " = false")}
                                [:strong (:party/legal-name p)]
                                [:span.text-muted {:style "margin-left: 0.5rem;"}
                                 (or (:party/cr-number p) (:party/national-id p))]]))]))))

    (catch Exception e
      (log/error e "Error searching parties")
      (sse-response request (str "<div id=\"" "borrower-results" "\">Error searching parties</div>")))))

(defn fee-row-template-handler
  "Handle GET /api/fee-row-template — return a new fee row fragment via SSE.
   Appended into #fee-rows by Datastar."
  [request]
  (let [idx (try (Integer/parseInt (or (get-in request [:query-params "n"]) "1"))
                 (catch Exception _ 1))]
    (sse-response request
                  (str (h/html (views/fee-row-template idx))))))

;; ============================================================
;; Document PDF Download Handler
;; ============================================================

(defn download-document-pdf-handler
  "Handle GET /contracts/:id/documents/:type/:doc-id/download

   Generates PDF on-demand from document snapshot and streams to user.

   Path params:
   - id: contract UUID
   - type: clearance-letter | statement | contract-agreement
   - doc-id: document UUID

   Returns: PDF file stream or error"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          doc-type-str (get-in request [:path-params :type])
          doc-id-str (get-in request [:path-params :doc-id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          doc-id (try (parse-uuid doc-id-str) (catch Exception _ nil))
          doc-type (keyword doc-type-str)]

      ;; Validate UUIDs
      (when-not contract-id
        (throw (ex-info "Invalid contract ID" {:id contract-id-str})))
      (when-not doc-id
        (throw (ex-info "Invalid document ID" {:id doc-id-str})))

      ;; Validate document type
      (when-not (#{:clearance-letter :statement :contract-agreement} doc-type)
        (throw (ex-info "Invalid document type" {:type doc-type-str})))

      ;; Query document entity
      (let [db (d/db (:conn request))
            doc-contract-attr (keyword (name doc-type) "contract")
            pull-pattern ['* {doc-contract-attr [:contract/id]}]
            doc (d/pull db pull-pattern
                        [(keyword (name doc-type) "id") doc-id])]

        (when-not doc
          (log/warn "Document not found" {:type doc-type :id doc-id})
          (throw (ex-info "Document not found" {:type doc-type :id doc-id})))

        ;; Verify document belongs to this contract
        (let [doc-contract-id (get-in doc [doc-contract-attr :contract/id])]
          (when-not (= doc-contract-id contract-id)
            (log/warn "Document does not belong to contract"
                      {:doc-contract-id doc-contract-id :contract-id contract-id})
            (throw (ex-info "Document not found" {}))))

        ;; Extract snapshot
        (let [snapshot-attr (keyword (name doc-type) "snapshot")
              snapshot-edn (get doc snapshot-attr)]

          (when-not snapshot-edn
            (throw (ex-info "Document has no snapshot" {:type doc-type :id doc-id})))

          ;; Enrich snapshot data with contract info for clearance letters
          (let [enriched-snapshot-edn
                (if (= doc-type :clearance-letter)
                  ;; For clearance letters, add contract info to snapshot
                  (let [snapshot-data (edn/read-string snapshot-edn)
                        contract (contract/get-contract db contract-id)
                        borrower (:contract/borrower contract)
                        enriched-data (assoc snapshot-data
                                             :contract {:external-id (:contract/external-id contract)
                                                        :customer-name (:party/legal-name borrower)})
                        ;; Add settlement-date from document entity
                        enriched-with-date (assoc enriched-data
                                                  :settlement-date
                                                  (str (:clearance-letter/settlement-date doc)))]
                    (pr-str enriched-with-date))
                  ;; For statements and contract agreements, use snapshot as-is
                  snapshot-edn)]

            ;; Generate PDF
            (log/info "Generating PDF" {:type doc-type :id doc-id})
            (let [result (pdf/generate-pdf doc-type enriched-snapshot-edn)]

              (if (:success? result)
                (do
                  (log/info "PDF generated successfully" {:type doc-type :id doc-id})
                  {:status 200
                   :headers {"Content-Type" "application/pdf"
                             "Content-Disposition"
                             (str "attachment; filename=\""
                                  (name doc-type) "-" doc-id ".pdf\"")}
                   :body (io/input-stream (:pdf-bytes result))})

                (do
                  (log/error "PDF generation failed" {:type doc-type :id doc-id :error (:error result)})
                  (-> (response/response (str "PDF generation failed: " (:error result)))
                      (response/status 500)))))))))

    (catch Exception e
      (log/error e "Error downloading document PDF")
      (if (or (= "Document not found" (.getMessage e))
              (= "Invalid document ID" (.getMessage e))
              (= "Invalid contract ID" (.getMessage e)))
        (-> (response/response (views/error-404-page))
            (response/status 404)
            (response/content-type "text/html; charset=utf-8"))
        (-> (response/response (views/error-500-page e))
            (response/status 500)
            (response/content-type "text/html; charset=utf-8"))))))

;; ============================================================
;; Document Generation Handlers
;; ============================================================

(defn generate-clearance-letter-handler
  "Handle POST /contracts/:id/generate-clearance-letter

   Generates a clearance letter document.

   Form params:
   - settlement-date: Date string
   - penalty-days: Integer

   Returns: Redirect to contract page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          settlement-date-str (get-in request [:form-params "settlement-date"])
          penalty-days-str (get-in request [:form-params "penalty-days"])]

      (when-not contract-id
        (throw (ex-info "Invalid contract ID" {:id contract-id-str})))

      (when-not settlement-date-str
        (throw (ex-info "Settlement date is required" {})))

      (when-not penalty-days-str
        (throw (ex-info "Penalty days is required" {})))

      (let [settlement-date (java.util.Date/from
                             (.toInstant
                              (.atStartOfDay
                               (java.time.LocalDate/parse settlement-date-str
                                                          java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
                               (java.time.ZoneId/of "UTC"))))
            penalty-days (Integer/parseInt penalty-days-str)]

        (ops/generate-clearance-letter (:conn request) contract-id settlement-date
                                       penalty-days "web-user")

        (log/info "Generated clearance letter" {:contract-id contract-id
                                                :settlement-date settlement-date
                                                :penalty-days penalty-days})

        (sse-ok request
                (contract-fragments (:conn request) contract-id
                                    {:flash {:type :success :message "Clearance letter generated successfully"}
                                     :close-modal "showClearanceLetterModal"
                                     :sections #{:documents}}))))

    (catch Exception e
      (log/error e "Error generating clearance letter")
      (sse-error request (str "Error generating clearance letter: " (.getMessage e))))))

(defn generate-statement-handler
  "Handle POST /contracts/:id/generate-statement

   Generates a statement document.

   Form params:
   - period-start: Date string
   - period-end: Date string

   Returns: Redirect to contract page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))
          period-start-str (get-in request [:form-params "period-start"])
          period-end-str (get-in request [:form-params "period-end"])]

      (when-not contract-id
        (throw (ex-info "Invalid contract ID" {:id contract-id-str})))

      (when-not period-start-str
        (throw (ex-info "Period start date is required" {})))

      (when-not period-end-str
        (throw (ex-info "Period end date is required" {})))

      (let [period-start (java.util.Date/from
                          (.toInstant
                           (.atStartOfDay
                            (java.time.LocalDate/parse period-start-str
                                                       java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
                            (java.time.ZoneId/of "UTC"))))
            period-end (java.util.Date/from
                        (.toInstant
                         (.atStartOfDay
                          (java.time.LocalDate/parse period-end-str
                                                     java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
                          (java.time.ZoneId/of "UTC"))))]

        (ops/generate-statement (:conn request) contract-id period-start period-end "web-user")

        (log/info "Generated statement" {:contract-id contract-id
                                         :period-start period-start
                                         :period-end period-end})

        (sse-ok request
                (contract-fragments (:conn request) contract-id
                                    {:flash {:type :success :message "Statement generated successfully"}
                                     :close-modal "showStatementModal"
                                     :sections #{:documents}}))))

    (catch Exception e
      (log/error e "Error generating statement")
      (sse-error request (str "Error generating statement: " (.getMessage e))))))

(defn generate-contract-agreement-handler
  "Handle POST /contracts/:id/generate-contract-agreement

   Generates a contract agreement document.

   Returns: Redirect to contract page"
  [request]
  (try
    (let [contract-id-str (get-in request [:path-params :id])
          contract-id (try (parse-uuid contract-id-str) (catch Exception _ nil))]

      (when-not contract-id
        (throw (ex-info "Invalid contract ID" {:id contract-id-str})))

      (ops/generate-contract-agreement (:conn request) contract-id "web-user")

      (log/info "Generated contract agreement" {:contract-id contract-id})

      (sse-ok request
              (contract-fragments (:conn request) contract-id
                                  {:flash {:type :success :message "Contract agreement generated successfully"}
                                   :sections #{:documents}})))

    (catch Exception e
      (log/error e "Error generating contract agreement")
      (sse-error request (str "Error generating contract agreement: " (.getMessage e))))))

;; ============================================================
;; Development Helpers
;; ============================================================

(comment
  ;; Test handlers in REPL
  ;; Note: handlers now expect :conn on the request (injected by wrap-conn middleware)

  ;; List contracts
  ;; (list-contracts-handler {:query-params {} :conn @lms.core/conn})

  ;; View specific contract
  ;; (view-contract-handler {:path-params {:id "some-uuid-here"} :conn @lms.core/conn})
  )
