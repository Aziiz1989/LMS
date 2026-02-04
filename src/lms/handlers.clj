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
            [lms.settlement :as settlement]
            [lms.views :as views]
            [datomic.client.api :as d]
            [ring.util.response :as response]
            [hiccup2.core :as h]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ============================================================
;; Contract List Handlers
;; ============================================================

(defn list-contracts-handler
  "Handle GET /contracts - list all contracts.

   Query params:
   - status: optional status filter (:active, :closed, etc.)

   Returns: HTML page with contract list"
  [request]
  (try
    (let [status-param (get-in request [:query-params "status"])
          status-filter (when status-param (keyword status-param))
          db (d/db (:conn request))
          contracts (contract/list-contracts db status-filter)]

      (log/info "Listed contracts" {:count (count contracts) :filter status-filter})

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

            (let [state (contract/contract-state db contract-id (java.util.Date.))
                  events (contract/get-events db contract-id)]
              (log/info "Viewed contract" {:id contract-id
                                          :external-id (get-in state [:contract :external-id])})

              (-> (response/response
                   (views/contract-detail-page state events flash))
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
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (str (h/html (views/payment-preview preview)))})
        (do
          (log/warn "Invalid preview params" {:contract-id contract-id
                                               :amount amount})
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body ""})))

    (catch Exception e
      (log/error e "Error previewing payment")
      {:status 500
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (str "<div class='preview-section' style='color: red;'>Error: " (.getMessage e) "</div>")})))

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
          (response/redirect (str "/contracts/" contract-id)))

        (do
          (log/warn "Invalid payment data" {:contract-id contract-id-str
                                            :amount amount-str
                                            :reference reference
                                            :date date-str})
          (-> (response/response (views/error-500-page "Invalid payment data"))
              (response/status 400)
              (response/content-type "text/html; charset=utf-8")))))

    (catch Exception e
      (log/error e "Error recording payment")
      (-> (response/response (views/error-500-page e))
          (response/status 500)
          (response/content-type "text/html; charset=utf-8")))))

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
          (response/redirect (str "/contracts/" contract-id)))

        (do
          (log/warn "Invalid retraction data" {:contract-id contract-id-str
                                               :payment-id payment-id-str
                                               :reason reason-str})
          (-> (response/response (views/error-500-page "Invalid retraction data"))
              (response/status 400)
              (response/content-type "text/html; charset=utf-8")))))

    (catch Exception e
      (log/error e "Error retracting payment")
      (let [contract-id-str (get-in request [:path-params :id])]
        (-> (response/redirect (str "/contracts/" contract-id-str))
            (assoc :flash {:type :error
                           :message (str "Error: " (.getMessage e))}))))))

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
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (str (h/html (views/settlement-result result)))})

        (do
          (log/warn "Invalid settlement params" {:contract-id contract-id-str
                                                  :date date-str
                                                  :penalty-days penalty-str})
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body "<div style=\"padding: 1rem; color: #991b1b; background: #fef2f2; border-radius: 0.375rem;\">Please provide a valid date and penalty days.</div>"})))

    (catch Exception e
      (log/error e "Error calculating settlement")
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (str "<div style=\"padding: 1rem; color: #991b1b; background: #fef2f2; border-radius: 0.375rem;\">Error: " (.getMessage e) "</div>")})))

;; ============================================================
;; Boarding Handlers
;; ============================================================

(defn- parse-fee-params
  "Extract fee data from repeating form fields.

   Ring collects same-named params as vectors when multiple values exist,
   or as a single string when only one. This normalizes both cases.

   Form params: fee-type[], fee-amount[], fee-due-date[]

   Returns: Sequence of fee maps with :fee/* keys"
  [form-params]
  (let [normalize (fn [v] (if (string? v) [v] (vec v)))
        types (some-> (get form-params "fee-type[]") normalize)
        amounts (some-> (get form-params "fee-amount[]") normalize)
        due-dates (some-> (get form-params "fee-due-date[]") normalize)]
    (when (and types amounts due-dates)
      (->> (map vector types amounts due-dates)
           (remove (fn [[_ amt _]] (str/blank? amt)))
           (mapv (fn [[type-str amount-str date-str]]
                   {:fee/type (keyword type-str)
                    :fee/amount (bigdec amount-str)
                    :fee/due-date (dates/->date (dates/parse-date date-str))}))))))

(defn- parse-contract-params
  "Extract contract data from form params.

   Returns: Map with :contract/* keys (only non-blank values included)"
  [form-params]
  (let [get-param (fn [k] (let [v (get form-params k)]
                            (when-not (str/blank? v) v)))]
    (cond-> {:contract/external-id (get-param "external-id")
             :contract/customer-name (get-param "customer-name")
             :contract/customer-id (get-param "customer-id")
             :contract/status :active
             :contract/start-date (when-let [d (get-param "start-date")]
                                    (dates/->date (dates/parse-date d)))
             :contract/principal (when-let [p (get-param "principal")]
                                   (bigdec p))}
      (get-param "maturity-date")
      (assoc :contract/maturity-date (dates/->date (dates/parse-date (get-param "maturity-date"))))

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
      (assoc :contract/virtual-iban (get-param "virtual-iban")))))

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
