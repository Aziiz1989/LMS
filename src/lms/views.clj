(ns lms.views
  "Hiccup components for server-side HTML rendering.

   This namespace contains pure functions that take data and return Hiccup
   (Clojure data structures representing HTML). No I/O, no side effects.

   Philosophy:
   - Views are data transformations: data → Hiccup
   - All derived state is computed before passing to view
   - Views never call database or do calculations
   - Keep views simple and focused"
  (:require [hiccup.page :refer [html5 include-css]]
            [hiccup.core :refer [html]]
            [clojure.string :as str]))

;; ============================================================
;; Utility Functions
;; ============================================================

(defn flash-message
  "Render flash message banner.

   Args:
   - flash: Flash map with :type (:error, :success, :warning) and :message keys,
            or a string for simple success messages

   Returns: Hiccup div or nil if no flash"
  [flash]
  (when flash
    (let [{:keys [type message]} (if (string? flash)
                                   {:type :success :message flash}
                                   flash)
          styles {:error {:bg "#fef2f2" :border "#fca5a5" :text "#991b1b"}
                  :success {:bg "#f0fdf4" :border "#86efac" :text "#166534"}
                  :warning {:bg "#fffbeb" :border "#fcd34d" :text "#92400e"}}
          style (get styles type (:success styles))]
      [:div {:style (format "padding: 1rem; margin-bottom: 1rem; border-radius: 0.375rem; background-color: %s; border: 1px solid %s; color: %s;"
                           (:bg style) (:border style) (:text style))}
       [:div {:style "display: flex; align-items: center; gap: 0.5rem;"}
        [:span {:style "font-weight: 600;"}
         (case type
           :error "Error:"
           :success "Success:"
           :warning "Warning:"
           "")]
        [:span message]]])))

(defn format-money
  "Format money amount in SAR with thousands separators.

   Example:
   (format-money 1234567.89M) => \"1,234,567.89\""
  [amount]
  (when amount
    (let [formatted (format "%.2f" (double amount))]
      (str/replace formatted #"\\B(?=(\\d{3})+(?!\\d))" ","))))

(defn format-date
  "Format date as YYYY-MM-DD.

   Example:
   (format-date #inst \"2024-01-15\") => \"2024-01-15\""
  [date]
  (when date
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") date)))

(defn status-badge
  "Render status badge with appropriate styling.

   Args:
   - status: keyword like :paid, :partial, :overdue, :scheduled, :unpaid

   Returns: Hiccup span with styling"
  [status]
  (let [status-styles
        {:paid {:bg "#10b981" :text "white"}
         :partial {:bg "#f59e0b" :text "white"}
         :overdue {:bg "#ef4444" :text "white"}
         :scheduled {:bg "#6b7280" :text "white"}
         :unpaid {:bg "#6b7280" :text "white"}
         :active {:bg "#3b82f6" :text "white"}
         :closed {:bg "#6b7280" :text "white"}}
        style (get status-styles status {:bg "#6b7280" :text "white"})]
    [:span {:style (format "display: inline-block; padding: 0.25rem 0.75rem; border-radius: 0.375rem; font-size: 0.875rem; font-weight: 600; background-color: %s; color: %s;"
                          (:bg style) (:text style))}
     (str/upper-case (name status))]))

;; ============================================================
;; Layout Components
;; ============================================================

(defn page-layout
  "Main page layout with header, navigation, and content area.

   Args:
   - title: Page title (string)
   - content: Hiccup content to render in main area

   Returns: Complete HTML5 page"
  [title & content]
  (html5
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title (str title " | LMS")]
    [:script {:src "https://unpkg.com/htmx.org@2.0.4"}]
    [:style "
      * { box-sizing: border-box; margin: 0; padding: 0; }
      body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
        font-size: 16px;
        line-height: 1.5;
        color: #1f2937;
        background-color: #f9fafb;
      }
      .container { max-width: 1280px; margin: 0 auto; padding: 0 1rem; }
      .header {
        background-color: white;
        border-bottom: 1px solid #e5e7eb;
        padding: 1rem 0;
        margin-bottom: 2rem;
      }
      .header h1 { font-size: 1.5rem; font-weight: 700; }
      .nav { margin-top: 0.5rem; }
      .nav a {
        color: #3b82f6;
        text-decoration: none;
        margin-right: 1.5rem;
        font-weight: 500;
      }
      .nav a:hover { text-decoration: underline; }
      .card {
        background-color: white;
        border: 1px solid #e5e7eb;
        border-radius: 0.5rem;
        padding: 1.5rem;
        margin-bottom: 1.5rem;
        box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
      }
      .card h2 {
        font-size: 1.25rem;
        font-weight: 600;
        margin-bottom: 1rem;
        color: #111827;
      }
      table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.875rem;
      }
      th {
        text-align: left;
        padding: 0.75rem 1rem;
        background-color: #f9fafb;
        border-bottom: 2px solid #e5e7eb;
        font-weight: 600;
        color: #6b7280;
        text-transform: uppercase;
        font-size: 0.75rem;
        letter-spacing: 0.05em;
      }
      td {
        padding: 0.75rem 1rem;
        border-bottom: 1px solid #e5e7eb;
      }
      tr:hover { background-color: #f9fafb; }
      .text-right { text-align: right; }
      .text-muted { color: #6b7280; }
      .btn {
        display: inline-block;
        padding: 0.5rem 1rem;
        border-radius: 0.375rem;
        font-weight: 500;
        text-decoration: none;
        border: none;
        cursor: pointer;
        font-size: 0.875rem;
      }
      .btn-primary {
        background-color: #3b82f6;
        color: white;
      }
      .btn-primary:hover {
        background-color: #2563eb;
      }
      .btn-secondary {
        background-color: #6b7280;
        color: white;
      }
      .btn-secondary:hover {
        background-color: #4b5563;
      }
      .btn-success {
        background-color: #10b981;
        color: white;
      }
      .btn-success:hover {
        background-color: #059669;
      }
      .summary-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 1rem;
        margin-bottom: 1.5rem;
      }
      .summary-item {
        padding: 1rem;
        background-color: #f9fafb;
        border-radius: 0.375rem;
      }
      .summary-item .label {
        font-size: 0.75rem;
        color: #6b7280;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 600;
        margin-bottom: 0.25rem;
      }
      .summary-item .value {
        font-size: 1.5rem;
        font-weight: 700;
        color: #111827;
      }
      .summary-item.highlight {
        background-color: #dbeafe;
      }
      .summary-item.highlight .value {
        color: #1e40af;
      }
      .empty-state {
        text-align: center;
        padding: 3rem 1rem;
        color: #6b7280;
      }
      .empty-state svg {
        width: 4rem;
        height: 4rem;
        margin: 0 auto 1rem;
        color: #d1d5db;
      }
      .modal {
        display: none;
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background-color: rgba(0, 0, 0, 0.5);
        z-index: 1000;
        align-items: center;
        justify-content: center;
      }
      .modal.active {
        display: flex;
      }
      .modal-content {
        background-color: white;
        border-radius: 0.5rem;
        padding: 2rem;
        max-width: 600px;
        width: 90%;
        max-height: 90vh;
        overflow-y: auto;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
      }
      .modal-header {
        margin-bottom: 1.5rem;
      }
      .modal-header h3 {
        font-size: 1.5rem;
        font-weight: 700;
        color: #111827;
      }
      .form-group {
        margin-bottom: 1rem;
      }
      .form-group label {
        display: block;
        font-weight: 600;
        margin-bottom: 0.5rem;
        color: #374151;
      }
      .form-group input {
        width: 100%;
        padding: 0.5rem 0.75rem;
        border: 1px solid #d1d5db;
        border-radius: 0.375rem;
        font-size: 1rem;
      }
      .form-group input:focus {
        outline: none;
        border-color: #3b82f6;
        box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
      }
      .modal-actions {
        display: flex;
        gap: 0.75rem;
        justify-content: flex-end;
        margin-top: 1.5rem;
      }
      .preview-section {
        margin-top: 1.5rem;
        padding: 1rem;
        background-color: #f9fafb;
        border-radius: 0.375rem;
        border: 1px solid #e5e7eb;
      }
      .preview-section h4 {
        font-size: 1rem;
        font-weight: 600;
        margin-bottom: 0.75rem;
        color: #111827;
      }
      .preview-item {
        display: flex;
        justify-content: space-between;
        padding: 0.5rem 0;
        border-bottom: 1px solid #e5e7eb;
      }
      .preview-item:last-child {
        border-bottom: none;
      }
      .preview-item .label {
        color: #6b7280;
      }
      .preview-item .value {
        font-weight: 600;
        color: #111827;
      }
      .htmx-indicator {
        display: none;
      }
      .htmx-request .htmx-indicator {
        display: inline;
      }

      /* Transaction Timeline */
      .tx-timeline {
        position: relative;
        padding-left: 2rem;
      }
      .tx-timeline::before {
        content: '';
        position: absolute;
        left: 0.5rem;
        top: 0;
        bottom: 0;
        width: 2px;
        background: linear-gradient(to bottom, #d1d5db 0%, #e5e7eb 100%);
      }
      .tx-event {
        position: relative;
        padding: 1rem 0;
        border-bottom: 1px solid #f3f4f6;
      }
      .tx-event:last-child {
        border-bottom: none;
      }
      .tx-event::before {
        content: '';
        position: absolute;
        left: -1.625rem;
        top: 1.125rem;
        width: 12px;
        height: 12px;
        border-radius: 50%;
        border: 2px solid white;
        box-shadow: 0 0 0 2px #e5e7eb;
      }
      .tx-event.type-payment::before { background: #10b981; }
      .tx-event.type-reversal::before { background: #f59e0b; }
      .tx-event.type-reversed::before { background: #ef4444; }
      .tx-event.type-retracted::before { background: #ef4444; }
      .tx-event.type-boarding::before { background: #6b7280; }
      .tx-event.type-disbursement::before { background: #3b82f6; }
      .tx-event.type-deposit::before { background: #8b5cf6; }
      .tx-event.type-principal-allocation::before { background: #f97316; }
      .tx-event.type-reversed, .tx-event.type-retracted {
        opacity: 0.7;
      }
      .tx-header {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        margin-bottom: 0.25rem;
      }
      .tx-type {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .tx-type-payment { color: #059669; }
      .tx-type-reversal { color: #d97706; }
      .tx-type-reversed { color: #dc2626; text-decoration: line-through; }
      .tx-type-retracted-payment { color: #dc2626; }
      .tx-type-boarding { color: #6b7280; }
      .tx-type-disbursement { color: #2563eb; }
      .tx-type-deposit { color: #7c3aed; }
      .tx-type-principal-allocation { color: #ea580c; }
      .tx-retraction-reason {
        display: inline-block;
        font-size: 0.6875rem;
        font-weight: 500;
        padding: 0.125rem 0.5rem;
        border-radius: 9999px;
        background: #fee2e2;
        color: #991b1b;
        border: 1px solid #fca5a5;
        margin-top: 0.25rem;
      }
      .tx-timestamp {
        font-size: 0.75rem;
        color: #9ca3af;
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', monospace;
      }
      .tx-amount {
        font-size: 1.125rem;
        font-weight: 600;
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', monospace;
        color: #111827;
        margin: 0.25rem 0;
      }
      .tx-amount.reversed {
        text-decoration: line-through;
        color: #dc2626;
      }
      .tx-amount.reversal {
        color: #d97706;
      }
      .tx-amount.reversal::before {
        content: '\u2212 ';
      }
      .tx-meta {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        font-size: 0.8125rem;
        color: #6b7280;
        margin-top: 0.25rem;
      }
      .tx-meta-item {
        display: flex;
        gap: 0.25rem;
      }
      .tx-meta-label {
        color: #9ca3af;
      }
      .tx-link-badge {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        font-size: 0.75rem;
        padding: 0.125rem 0.5rem;
        border-radius: 9999px;
        margin-top: 0.5rem;
      }
      .tx-link-badge.reversal-link {
        background: #fef3c7;
        color: #92400e;
        border: 1px solid #fcd34d;
      }
      .tx-link-badge.reversed-link {
        background: #fee2e2;
        color: #991b1b;
        border: 1px solid #fca5a5;
      }
      .tx-reason {
        margin-top: 0.5rem;
        font-size: 0.8125rem;
        color: #92400e;
        font-style: italic;
      }

      /* Settlement Calculator */
      .settlement-result {
        margin-top: 1.5rem;
      }
      .settlement-hero {
        text-align: center;
        padding: 1.75rem 1rem;
        margin-bottom: 1.25rem;
        border-radius: 0.5rem;
        border: 1px solid #c7d2fe;
        background: linear-gradient(135deg, #eef2ff 0%, #e0e7ff 100%);
      }
      .settlement-hero .hero-label {
        font-size: 0.6875rem;
        font-weight: 700;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: #6366f1;
        margin-bottom: 0.375rem;
      }
      .settlement-hero .hero-amount {
        font-size: 2.25rem;
        font-weight: 800;
        color: #312e81;
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
        letter-spacing: -0.02em;
        line-height: 1.2;
      }
      .settlement-hero.refund {
        background: linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%);
        border-color: #86efac;
      }
      .settlement-hero.refund .hero-label { color: #059669; }
      .settlement-hero.refund .hero-amount { color: #064e3b; }
      .settlement-waterfall {
        border: 1px solid #e5e7eb;
        border-radius: 0.5rem;
        overflow: hidden;
        margin-bottom: 1rem;
      }
      .settlement-item {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.625rem 1rem;
        border-bottom: 1px solid #f3f4f6;
        font-size: 0.875rem;
      }
      .settlement-item:last-child { border-bottom: none; }
      .settlement-item .s-label {
        color: #374151;
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .settlement-item .s-sign {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.25rem;
        height: 1.25rem;
        border-radius: 50%;
        font-size: 0.75rem;
        font-weight: 700;
        flex-shrink: 0;
      }
      .settlement-item .s-sign.plus {
        background: #fef3c7;
        color: #92400e;
      }
      .settlement-item .s-sign.minus {
        background: #dbeafe;
        color: #1e40af;
      }
      .settlement-item .s-amount {
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
        font-weight: 600;
        color: #111827;
        font-size: 0.875rem;
      }
      .settlement-item.sub-detail {
        padding-left: 2.75rem;
        background-color: #fafafa;
      }
      .settlement-item.sub-detail .s-label { color: #6b7280; font-size: 0.8125rem; }
      .settlement-item.sub-detail .s-amount { color: #6b7280; font-weight: 500; font-size: 0.8125rem; }
      .settlement-divider {
        border-bottom: 2px solid #d1d5db;
        margin: 0;
      }
      .settlement-item.total {
        background-color: #f9fafb;
        font-weight: 700;
      }
      .settlement-item.total .s-label { color: #111827; font-weight: 700; }
      .settlement-item.total .s-amount { font-size: 1rem; color: #111827; }
      .settlement-period {
        display: flex;
        flex-wrap: wrap;
        gap: 1.5rem;
        padding: 0.75rem 1rem;
        background: #f9fafb;
        border: 1px solid #e5e7eb;
        border-radius: 0.375rem;
        font-size: 0.8125rem;
        color: #6b7280;
      }
      .settlement-period .sp-item {
        display: flex;
        gap: 0.375rem;
      }
      .settlement-period .sp-label { color: #9ca3af; }
      .settlement-period .sp-value {
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
        color: #374151;
      }
      .settlement-override-badge {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        font-size: 0.75rem;
        padding: 0.125rem 0.625rem;
        border-radius: 9999px;
        background: #fef3c7;
        color: #92400e;
        border: 1px solid #fcd34d;
        margin-top: 0.75rem;
      }
      .override-toggle {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-bottom: 0.5rem;
        font-size: 0.875rem;
        color: #6b7280;
        cursor: pointer;
      }
      .override-toggle input[type=checkbox] {
        width: auto;
        cursor: pointer;
      }
      .override-fields { display: none; }
      .override-fields.visible { display: block; }
      .btn-settlement {
        background-color: #4f46e5;
        color: white;
      }
      .btn-settlement:hover {
        background-color: #4338ca;
      }
    "]]
   [:body
    [:div.header
     [:div.container
      [:h1 "LMS - Loan Management System - I am Aziz"]
      [:nav.nav
       [:a {:href "/contracts"} "Contracts"]
       [:a {:href "/about"} "About"]]]]
    [:div.container
     content]]))

;; ============================================================
;; Contract List Components
;; ============================================================

(defn contract-list-table
  "Render table of contracts.

   Args:
   - contracts: Sequence of contract summary maps

   Returns: Hiccup table"
  [contracts]
  (if (empty? contracts)
    [:div.empty-state
     [:p "No contracts found."]
     [:p.text-muted "Create a contract to get started."]]
    [:table
     [:thead
      [:tr
       [:th "External ID"]
       [:th "Customer"]
       [:th "Status"]
       [:th.text-right "Principal"]
       [:th "Actions"]]]
     [:tbody
      (for [contract contracts]
        [:tr {:key (:id contract)}
         [:td [:a {:href (str "/contracts/" (:id contract))}
               (:external-id contract)]]
         [:td (:customer-name contract)]
         [:td (status-badge (:status contract))]
         [:td.text-right (format-money (:principal contract))]
         [:td [:a.btn.btn-primary {:href (str "/contracts/" (:id contract))}
               "View"]]])]]))

(defn contract-list-page
  "Render contracts list page.

   Args:
   - contracts: Sequence of contract summary maps

   Returns: Complete HTML page"
  [contracts]
  (page-layout
   "Contracts"
   [:div.card
    [:div {:style "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"}
     [:h2 {:style "margin-bottom: 0;"} "All Contracts"]
     [:a.btn.btn-success {:href "/contracts/new"} "+ Board Contract"]]
    (contract-list-table contracts)]))

;; ============================================================
;; Contract Detail Components
;; ============================================================

(defn contract-summary
  "Render contract summary card with key metrics.

   Args:
   - state: Contract state map from contract/contract-state

   Returns: Hiccup div"
  [state]
  (let [contract (:contract state)]
    [:div.card
     [:h2 (str (:external-id contract) " - " (:customer-name contract))]
     [:div.summary-grid
      [:div.summary-item
       [:div.label "Status"]
       [:div.value (status-badge (:status contract))]]
      [:div.summary-item
       [:div.label "Principal"]
       [:div.value (format-money (:principal contract))]]
      [:div.summary-item.highlight
       [:div.label "Outstanding"]
       [:div.value (format-money (:total-outstanding state))]]
      [:div.summary-item
       [:div.label "Credit Balance"]
       [:div.value (format-money (:credit-balance state))]]
      [:div.summary-item
       [:div.label "Deposit Held"]
       [:div.value (format-money (:deposit-held state))]]
      [:div.summary-item
       [:div.label "Start Date"]
       [:div.value (format-date (:start-date contract))]]
      [:div.summary-item
       [:div.label "Maturity Date"]
       [:div.value (format-date (:maturity-date contract))]]]]))

(defn fees-table
  "Render fees table.

   Args:
   - fees: Sequence of fee maps with paid/outstanding/status

   Returns: Hiccup div"
  [fees]
  [:div.card
   [:h2 "Fees"]
   (if (empty? fees)
     [:p.text-muted "No fees."]
     [:table
      [:thead
       [:tr
        [:th "Type"]
        [:th "Due Date"]
        [:th.text-right "Amount"]
        [:th.text-right "Paid"]
        [:th.text-right "Outstanding"]
        [:th "Status"]]]
      [:tbody
       (for [fee fees]
         [:tr {:key (:id fee)}
          [:td (str/capitalize (name (:type fee)))]
          [:td (format-date (:due-date fee))]
          [:td.text-right (format-money (:amount fee))]
          [:td.text-right (format-money (:paid fee))]
          [:td.text-right (format-money (:outstanding fee))]
          [:td (status-badge (:status fee))]])]])])

(defn installments-table
  "Render installments table.

   Args:
   - installments: Sequence of installment maps with paid/outstanding/status/days-delinquent

   Returns: Hiccup div"
  [installments]
  [:div.card
   [:h2 "Installment Schedule"]
   [:table
    [:thead
     [:tr
      [:th "#"]
      [:th "Due Date"]
      [:th.text-right "Remaining Principal"]
      [:th.text-right "Principal Due"]
      [:th.text-right "Profit Due"]
      [:th.text-right "Total Due"]
      [:th.text-right "Paid"]
      [:th.text-right "Outstanding"]
      [:th "Status"]
      [:th.text-right "Days Late"]]]
    [:tbody
     (for [inst installments]
       [:tr {:key (:id inst)}
        [:td (:seq inst)]
        [:td (format-date (:due-date inst))]
        [:td.text-right (format-money (:remaining-principal inst))]
        [:td.text-right (format-money (:principal-due inst))]
        [:td.text-right (format-money (:profit-due inst))]
        [:td.text-right (format-money (:total-due inst))]
        [:td.text-right (format-money (:total-paid inst))]
        [:td.text-right (format-money (:outstanding inst))]
        [:td (status-badge (:status inst))]
        [:td.text-right
         {:style (let [days (:days-delinquent inst)]
                   (cond
                     (pos? days) "color: #ef4444; font-weight: 600;"
                     (neg? days) "color: #9ca3af;"
                     :else "color: #6b7280;"))}
         (:days-delinquent inst)]])]]])

(defn- format-datetime
  "Format datetime with both date and time for audit trail."
  [date]
  (when date
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm") date)))

(defn- event-type-class
  "Return CSS class for event type styling."
  [event]
  (case (:event-type event)
    :payment "type-payment"
    :retracted-payment "type-retracted"
    :disbursement (if (= :refund (:sub-type event))
                    "type-reversal"      ;; reuse reversal styling for refunds (orange)
                    "type-disbursement")
    :deposit "type-deposit"
    :principal-allocation "type-principal-allocation"
    :admin "type-boarding"
    "type-boarding"))

(defn- event-type-label
  "Return display label for event type."
  [event]
  (case (:event-type event)
    :payment "Payment"
    :retracted-payment "Payment (Retracted)"
    :disbursement (case (:sub-type event)
                    :funding "Disbursement (Funding)"
                    :refund "Refund"
                    :excess-return "Excess Return"
                    "Disbursement")
    :deposit (case (:sub-type event)
               :received "Deposit Received"
               :refund "Deposit Refund"
               :offset "Deposit Offset"
               :transfer "Deposit Transfer"
               "Deposit")
    :principal-allocation "Principal Allocation"
    :admin (case (:type event)
             :boarding "Boarding"
             :rate-adjustment "Rate Adjustment"
             (some-> (:type event) name str/capitalize))
    "Event"))

(defn- retraction-reason-label
  "Human-readable label for retraction reason keywords."
  [reason]
  (case reason
    :correction "Wrong Amount"
    :duplicate-removal "Duplicate Entry"
    :erroneous-entry "Wrong Contract"
    (some-> reason name)))

(defn transaction-log
  "Render event history as a timeline.

   Displays all monetary and admin events chronologically.
   Entity events (payments, disbursements, deposits) show business date and amount.
   Admin events (boarding, rate-adjustment) show recording date.
   Retracted payments show strikethrough amount, reason badge, and retraction metadata.

   Args:
   - events: Sequence of event maps from contract/get-events

   Returns: Hiccup div"
  [events]
  [:div.card
   [:h2 "Transaction History"]
   (if (empty? events)
     [:p.text-muted "No transactions yet."]
     [:div.tx-timeline
      (for [event (reverse events)]  ;; Most recent first
        (let [retracted? (= :retracted-payment (:event-type event))]
          [:div.tx-event {:key (or (:id event) (:tx-id event))
                          :class (event-type-class event)}
           ;; Header: type + date
           [:div.tx-header
            [:span.tx-type {:class (str "tx-type-" (name (:event-type event)))}
             (event-type-label event)]
            [:span.tx-timestamp (format-datetime (:date event))]]

           ;; Amount (all amounts positive — direction from event type)
           (when (:amount event)
             [:div.tx-amount {:class (cond
                                       retracted? "reversed"
                                       (= :refund (:sub-type event)) "reversal")}
              "SAR " (format-money (:amount event))])

           ;; Retraction reason badge
           (when retracted?
             [:div
              [:span.tx-retraction-reason (retraction-reason-label (:reason event))]])

           ;; Metadata row
           [:div.tx-meta
            (when (:reference event)
              [:span.tx-meta-item
               [:span.tx-meta-label "Ref:"]
               [:span (:reference event)]])
            (when (:channel event)
              [:span.tx-meta-item
               [:span.tx-meta-label "Channel:"]
               [:span (:channel event)]])
            (when (and retracted? (:original-date event))
              [:span.tx-meta-item
               [:span.tx-meta-label "Original date:"]
               [:span (format-datetime (:original-date event))]])
            (when (:author event)
              [:span.tx-meta-item
               [:span.tx-meta-label "By:"]
               [:span (:author event)]])]

           ;; Note (for admin events, refunds, or retraction notes)
           (when (:note event)
             [:div.tx-reason
              "\"" (:note event) "\""])

           ;; Retract button (only for live payments — data correction)
           (when (= :payment (:event-type event))
             [:div {:style "margin-top: 0.5rem;"}
              [:button.btn.btn-secondary
               {:type "button"
                :style "font-size: 0.75rem; padding: 0.25rem 0.5rem;"
              :onclick (str "showRetractPaymentModal('" (:id event) "', '"
                           (:reference event) "', "
                           (:amount event) ")")}
             "Retract"]])]))])])

;; ============================================================
;; Payment Components
;; ============================================================

(defn payment-preview
  "Render payment allocation preview.

   Args:
   - preview: Preview data from ops/preview-payment with :before, :after, :changes

   Returns: Hiccup div"
  [{:keys [after changes]}]
  [:div.preview-section
   [:h4 "Payment Allocation Preview"]
   [:div
    (for [change changes]
      [:div.preview-item {:key (str (:type change) "-" (:id change))}
       [:div.label (:description change)]
       [:div.value (format-money (:amount change))]])
    [:div.preview-item {:style "font-weight: bold; margin-top: 0.5rem; padding-top: 0.5rem; border-top: 2px solid #d1d5db;"}
     [:div.label "Outstanding After Payment"]
     [:div.value (format-money (:total-outstanding after))]]]])

(defn settlement-result
  "Render settlement calculation breakdown.

   Returned as an HTML fragment via HTMX — swapped into the modal.

   Args:
   - result: Settlement breakdown map from settlement/calculate-settlement

   Returns: Hiccup div"
  [result]
  (let [{:keys [outstanding-principal
                accrued-profit profit-already-paid
                effective-accrued-unpaid-profit
                unearned-profit outstanding-fees
                penalty-days penalty-amount
                credit-balance settlement-amount refund-due
                current-period-start current-period-end accrued-days
                manual-override?]} result
        has-refund? (and refund-due (pos? refund-due))]
    [:div.settlement-result
     ;; Hero: final amount
     [:div.settlement-hero {:class (when has-refund? "refund")}
      [:div.hero-label (if has-refund? "Refund Due to Customer" "Settlement Amount")]
      [:div.hero-amount
       (str "SAR " (format-money (if has-refund? refund-due settlement-amount)))]]

     ;; Waterfall breakdown
     [:div.settlement-waterfall
      [:div.settlement-item
       [:span.s-label [:span.s-sign.plus "+"] "Outstanding Principal"]
       [:span.s-amount (str "SAR " (format-money outstanding-principal))]]

      [:div.settlement-item
       [:span.s-label [:span.s-sign.plus "+"] "Accrued Unpaid Profit"]
       [:span.s-amount (str "SAR " (format-money effective-accrued-unpaid-profit))]]
      [:div.settlement-item.sub-detail
       [:span.s-label "Total accrued"]
       [:span.s-amount (str "SAR " (format-money accrued-profit))]]
      [:div.settlement-item.sub-detail
       [:span.s-label "Already paid"]
       [:span.s-amount (str "SAR " (format-money profit-already-paid))]]

      [:div.settlement-item
       [:span.s-label [:span.s-sign.plus "+"] "Outstanding Fees"]
       [:span.s-amount (str "SAR " (format-money outstanding-fees))]]

      (when (pos? penalty-days)
        [:div.settlement-item
         [:span.s-label [:span.s-sign.plus "+"]
          (str "Penalty (" penalty-days " day" (when (> penalty-days 1) "s") ")")]
         [:span.s-amount (str "SAR " (format-money penalty-amount))]])

      (when (pos? credit-balance)
        [:div.settlement-item
         [:span.s-label [:span.s-sign.minus "\u2212"] "Credit Balance"]
         [:span.s-amount (str "SAR " (format-money credit-balance))]])

      [:div.settlement-divider]

      [:div.settlement-item.total
       [:span.s-label (if has-refund? "Refund Due" "Settlement Amount")]
       [:span.s-amount (str "SAR " (format-money (if has-refund? refund-due settlement-amount)))]]]

     ;; Manual override indicator
     (when manual-override?
       [:div {:style "text-align: center;"}
        [:span.settlement-override-badge "Manual override applied to accrued unpaid profit"]])

     ;; Period metadata
     [:div.settlement-period {:style "margin-top: 0.75rem;"}
      (when current-period-start
        [:span.sp-item
         [:span.sp-label "Period start:"]
         [:span.sp-value (format-date current-period-start)]])
      (when current-period-end
        [:span.sp-item
         [:span.sp-label "Period end:"]
         [:span.sp-value (format-date current-period-end)]])
      [:span.sp-item
       [:span.sp-label "Accrued days:"]
       [:span.sp-value (str accrued-days)]]
      [:span.sp-item
       [:span.sp-label "Unearned profit:"]
       [:span.sp-value (str "SAR " (format-money unearned-profit))]]]]))

(defn payment-form
  "Render payment recording form with HTMX preview.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup form"
  [contract-id]
  [:div#payment-modal.modal
   [:div.modal-content
    [:div.modal-header
     [:h3 "Record Payment"]]
    [:form {:method "post"
            :action (str "/contracts/" contract-id "/record-payment")}
     [:div.form-group
      [:label {:for "amount"} "Payment Amount (SAR)"]
      [:input {:type "number"
               :id "amount"
               :name "amount"
               :step "0.01"
               :min "0.01"
               :required true
               :placeholder "e.g., 500000.00"}]]
     [:div.form-group
      [:label {:for "reference"} "Payment Reference"]
      [:input {:type "text"
               :id "reference"
               :name "reference"
               :required true
               :placeholder "e.g., PAY-12345"}]]
     [:div.form-group
      [:label {:for "original-date"} "Payment Date"]
      [:input {:type "date"
               :id "original-date"
               :name "original-date"
               :required true
               :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
     [:div.form-group
      [:label {:for "note"} "Note (Optional)"]
      [:textarea {:id "note"
                  :name "note"
                  :rows "3"
                  :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem; font-family: inherit;"
                  :placeholder "e.g., Payment received via bank transfer from account ending in 1234"}]]
     [:div#preview-area]
     [:div.modal-actions
      [:button.btn.btn-secondary
       {:type "button"
        :onclick "document.getElementById('payment-modal').classList.remove('active')"}
       "Cancel"]
      [:button.btn.btn-success
       {:type "submit"}
       "Record Payment"]]]]])

(defn retract-payment-modal
  "Render modal for retracting a payment (data correction).

   Uses [:db/retractEntity] to remove the payment from the current database.
   Datomic history preserves retracted datoms for forensics.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup modal"
  [contract-id]
  [:div#retract-payment-modal.modal
   [:div.modal-content
    [:div.modal-header
     [:h3 "Retract Payment"]]
    [:form {:method "post"
            :action (str "/contracts/" contract-id "/retract-payment")}
     [:input {:type "hidden"
              :id "retract-payment-id"
              :name "payment-id"}]
     [:p "This will remove the payment as a data correction. Use this for recording errors (wrong amount, duplicate, wrong contract)."]
     [:p {:style "font-size: 0.875rem; color: #6b7280; margin-top: 0.5rem;"}
      "For real money returned to customer, use a refund disbursement instead."]
     [:div#retract-payment-details {:style "margin: 1rem 0; padding: 1rem; background-color: #fef3c7; border-radius: 0.375rem; border: 1px solid #fcd34d;"}]
     [:div.form-group
      [:label {:for "retract-reason"} "Reason for Correction"]
      [:select {:id "retract-reason"
                :name "reason"
                :required true
                :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong amount)"]
       [:option {:value "duplicate-removal"} "Duplicate Removal"]
       [:option {:value "erroneous-entry"} "Erroneous Entry (wrong contract)"]]]
     [:div.form-group
      [:label {:for "retract-note"} "Note (Optional)"]
      [:textarea {:id "retract-note"
                  :name "note"
                  :rows "2"
                  :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem; font-family: inherit;"
                  :placeholder "e.g., Duplicate of FT-ANB-123"}]]
     [:div.modal-actions
      [:button.btn.btn-secondary
       {:type "button"
        :onclick "closeRetractPaymentModal()"}
       "Cancel"]
      [:button.btn.btn-secondary
       {:type "submit"
        :style "background-color: #f59e0b;"}
       "Retract Payment"]]]]])

(defn retract-contract-modal
  "Render modal for retracting a contract (data correction).

   Retracts the contract and ALL associated entities (fees, installments,
   payments, disbursements, deposits, principal-allocations) in a single
   atomic transaction. Datomic history preserves all retracted datoms.

   Args:
   - contract-id: UUID of contract
   - external-id: External ID for display

   Returns: Hiccup modal"
  [contract-id external-id]
  [:div#retract-contract-modal.modal
   [:div.modal-content
    [:div.modal-header
     [:h3 {:style "color: #991b1b;"} "Delete Contract"]]
    [:form {:method "post"
            :action (str "/contracts/" contract-id "/retract-contract")}
     [:div {:style "padding: 1rem; margin-bottom: 1rem; border-radius: 0.375rem; background-color: #fef2f2; border: 1px solid #fca5a5;"}
      [:p {:style "font-weight: 600; color: #991b1b; margin-bottom: 0.5rem;"}
       "This will permanently retract the following contract and all its data:"]
      [:p {:style "font-family: ui-monospace, monospace; font-size: 1.125rem; color: #111827; font-weight: 700;"}
       external-id]
      [:ul {:style "margin-top: 0.75rem; margin-left: 1.5rem; font-size: 0.875rem; color: #991b1b;"}
       [:li "Contract record"]
       [:li "All fees"]
       [:li "All installments"]
       [:li "All payments"]
       [:li "All disbursements"]
       [:li "All deposits"]
       [:li "All principal allocations"]]]
     [:p {:style "font-size: 0.875rem; color: #6b7280; margin-bottom: 1rem;"}
      "Use this only for data corrections (contract boarded in error, duplicate, wrong customer). "
      "Datomic history preserves retracted data for audit purposes."]
     [:div.form-group
      [:label {:for "retract-contract-reason"} "Reason for Correction *"]
      [:select {:id "retract-contract-reason"
                :name "reason"
                :required true
                :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong data)"]
       [:option {:value "duplicate-removal"} "Duplicate Removal"]
       [:option {:value "erroneous-entry"} "Erroneous Entry (wrong customer/contract)"]]]
     [:div.form-group
      [:label {:for "retract-contract-note"} "Note (Optional)"]
      [:textarea {:id "retract-contract-note"
                  :name "note"
                  :rows "2"
                  :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem; font-family: inherit;"
                  :placeholder "e.g., Contract boarded against wrong customer CR-456"}]]
     [:div.modal-actions
      [:button.btn.btn-secondary
       {:type "button"
        :onclick "document.getElementById('retract-contract-modal').classList.remove('active')"}
       "Cancel"]
      [:button.btn
       {:type "submit"
        :style "background-color: #dc2626; color: white;"}
       "Delete Contract"]]]]])

(defn settlement-form
  "Render settlement calculator modal with HTMX-powered inline results.

   The form POSTs to the settlement endpoint via HTMX and the server
   returns an HTML fragment (settlement-result) swapped into the modal.
   No page reload — settlement is a read-only computation.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup modal div"
  [contract-id]
  [:div#settlement-modal.modal
   [:div.modal-content
    [:div.modal-header
     [:h3 "Settlement Calculator"]]
    [:form {:hx-post (str "/contracts/" contract-id "/calculate-settlement")
            :hx-target "#settlement-result-area"
            :hx-swap "innerHTML"}
     [:div.form-group
      [:label {:for "settlement-date"} "Settlement Date"]
      [:input {:type "date"
               :id "settlement-date"
               :name "settlement-date"
               :required true
               :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
     [:div.form-group
      [:label {:for "penalty-days"} "Penalty Days"]
      [:input {:type "number"
               :id "penalty-days"
               :name "penalty-days"
               :min "0"
               :step "1"
               :required true
               :value "0"
               :placeholder "0"}]]
     [:div.form-group
      [:label.override-toggle
       [:input {:type "checkbox"
                :id "override-toggle"
                :onchange "document.getElementById('override-fields').classList.toggle('visible', this.checked); if(!this.checked) document.getElementById('manual-override').value = '';"}]
       "Manual override for accrued unpaid profit"]
      [:div#override-fields.override-fields
       [:input {:type "number"
                :id "manual-override"
                :name "manual-override"
                :step "0.01"
                :min "0"
                :placeholder "Override amount (SAR)"}]]]
     [:div.modal-actions
      [:button.btn.btn-secondary
       {:type "button"
        :onclick "document.getElementById('settlement-modal').classList.remove('active'); document.getElementById('settlement-result-area').innerHTML = '';"}
       "Close"]
      [:button.btn.btn-settlement
       {:type "submit"}
       "Calculate"]]]
    [:div#settlement-result-area]]])

(defn origination-form
  "Render origination modal with system-calculated funding breakdown.

   Computes the funding allocation from contract state:
   - Fee deduction = outstanding fees (not yet covered by pre-payments)
   - Deposit from funding = security deposit minus deposits already held
   - Disbursement = principal minus deductions
   - Excess return = customer credit balance (overpayment beyond fees)

   The user provides only the origination date and wire reference.
   Amounts are derived, not entered.

   Args:
   - contract-id: UUID of contract
   - state: Contract state map from contract/contract-state

   Returns: Hiccup modal div"
  [contract-id state]
  (let [contract (:contract state)
        principal (or (:principal contract) 0M)
        security-deposit (or (:security-deposit-required contract) 0M)
        fee-deduction (max 0M (- (or (:total-fees-due state) 0M)
                                  (or (:total-fees-paid state) 0M)))
        deposit-held (or (:deposit-held state) 0M)
        deposit-from-funding (max 0M (- security-deposit deposit-held))
        disbursement-amount (max 0M (- principal fee-deduction deposit-from-funding))
        credit-balance (or (:credit-balance state) 0M)
        excess-return (if (pos? credit-balance) credit-balance 0M)
        disb-iban (:disbursement-iban contract)
        disb-bank (:disbursement-bank contract)
        fmt-val (fn [n] (format "%.2f" (double n)))]
    [:div#origination-modal.modal
     [:div.modal-content
      [:div.modal-header
       [:h3 "Originate Contract"]]
      [:p {:style "font-size: 0.875rem; color: #6b7280; margin-bottom: 1rem;"}
       "Funding breakdown calculated from contract terms and pre-payments."]

      ;; Funding allocation preview
      [:div.preview-section
       [:h4 "Funding Allocation"]
       [:div.preview-item
        [:div.label "Principal"]
        [:div.value (str "SAR " (format-money principal))]]
       (when (pos? fee-deduction)
         [:div.preview-item
          [:div.label "Less: Fee deduction (outstanding fees)"]
          [:div.value (str "SAR " (format-money fee-deduction))]])
       (when (pos? deposit-from-funding)
         [:div.preview-item
          [:div.label "Less: Deposit from funding"]
          [:div.value (str "SAR " (format-money deposit-from-funding))]])
       [:div.preview-item {:style "font-weight: bold; margin-top: 0.5rem; padding-top: 0.5rem; border-top: 2px solid #d1d5db;"}
        [:div.label "Merchant Disbursement"]
        [:div.value (str "SAR " (format-money disbursement-amount))]]
       (when (pos? excess-return)
         [:div.preview-item
          [:div.label "Plus: Excess return to customer"]
          [:div.value (str "SAR " (format-money excess-return))]])]

      ;; Form — computed amounts as hidden inputs, user provides date + reference
      [:form {:method "post"
              :action (str "/contracts/" contract-id "/originate")}
       (when (pos? fee-deduction)
         [:input {:type "hidden" :name "fee-deduction" :value (fmt-val fee-deduction)}])
       (when (pos? deposit-from-funding)
         [:input {:type "hidden" :name "deposit-from-funding" :value (fmt-val deposit-from-funding)}])
       [:input {:type "hidden" :name "disbursement-amount" :value (fmt-val disbursement-amount)}]
       (when (pos? excess-return)
         [:input {:type "hidden" :name "excess-return" :value (fmt-val excess-return)}])

       [:div {:style "border-top: 1px solid #e5e7eb; padding-top: 1rem; margin-top: 1rem;"}
        [:div.form-group
         [:label {:for "orig-date"} "Origination Date *"]
         [:input {:type "date"
                  :id "orig-date"
                  :name "date"
                  :required true
                  :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
        [:div.form-group
         [:label {:for "orig-disb-reference"} "Wire Reference *"]
         [:input {:type "text"
                  :id "orig-disb-reference"
                  :name "disbursement-reference"
                  :required true
                  :placeholder "e.g., WT-001"}]]
        [:div {:style "display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
         [:div.form-group
          [:label {:for "orig-disb-iban"} "Destination IBAN"]
          [:input {:type "text"
                   :id "orig-disb-iban"
                   :name "disbursement-iban"
                   :value (or disb-iban "")
                   :placeholder "e.g., SA242000..."}]]
         [:div.form-group
          [:label {:for "orig-disb-bank"} "Destination Bank"]
          [:input {:type "text"
                   :id "orig-disb-bank"
                   :name "disbursement-bank"
                   :value (or disb-bank "")
                   :placeholder "e.g., ANB"}]]]]

       [:div.modal-actions
        [:button.btn.btn-secondary
         {:type "button"
          :onclick "document.getElementById('origination-modal').classList.remove('active')"}
         "Cancel"]
        [:button.btn.btn-success
         {:type "submit"}
         "Originate"]]]]]))

(defn retract-origination-modal
  "Render modal for retracting origination entities (data correction).

   Retracts all origination-created entities: principal allocations,
   funding disbursements, deposits from funding, and excess returns.
   Datomic history preserves all retracted datoms for audit.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup modal"
  [contract-id]
  [:div#retract-origination-modal.modal
   [:div.modal-content
    [:div.modal-header
     [:h3 {:style "color: #92400e;"} "Retract Origination"]]
    [:form {:method "post"
            :action (str "/contracts/" contract-id "/retract-origination")}
     [:div {:style "padding: 1rem; margin-bottom: 1rem; border-radius: 0.375rem; background-color: #fffbeb; border: 1px solid #fcd34d;"}
      [:p {:style "font-weight: 600; color: #92400e; margin-bottom: 0.5rem;"}
       "This will retract all origination entities:"]
      [:ul {:style "margin-left: 1.5rem; font-size: 0.875rem; color: #92400e;"}
       [:li "Principal allocations (fee deductions from funding)"]
       [:li "Funding disbursements"]
       [:li "Deposits from funding"]
       [:li "Excess return disbursements"]]]
     [:p {:style "font-size: 0.875rem; color: #6b7280; margin-bottom: 1rem;"}
      "Use this only for data corrections (wrong amounts, duplicate origination). "
      "Datomic history preserves retracted data for audit purposes."]
     [:div.form-group
      [:label {:for "retract-orig-reason"} "Reason for Correction *"]
      [:select {:id "retract-orig-reason"
                :name "reason"
                :required true
                :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong amounts)"]
       [:option {:value "duplicate-removal"} "Duplicate Origination"]
       [:option {:value "erroneous-entry"} "Erroneous Entry"]]]
     [:div.form-group
      [:label {:for "retract-orig-note"} "Note (Optional)"]
      [:textarea {:id "retract-orig-note"
                  :name "note"
                  :rows "2"
                  :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem; font-family: inherit;"
                  :placeholder "e.g., Wrong fee deduction amount, re-originating"}]]
     [:div.modal-actions
      [:button.btn.btn-secondary
       {:type "button"
        :onclick "document.getElementById('retract-origination-modal').classList.remove('active')"}
       "Cancel"]
      [:button.btn
       {:type "submit"
        :style "background-color: #f59e0b; color: white;"}
       "Retract Origination"]]]]])

(defn contract-detail-page
  "Render contract detail page.

   Args:
   - state: Contract state map from contract/contract-state
   - events: Event timeline from contract/get-events
   - flash: Optional flash message map with :type and :message keys

   Returns: Complete HTML page"
  ([state events] (contract-detail-page state events nil))
  ([state events flash]
   (let [contract-id (get-in state [:contract :id])]
     (page-layout
      (str "Contract: " (get-in state [:contract :external-id]))
      [:div
       (flash-message flash)
       [:div {:style "margin-bottom: 1rem; display: flex; justify-content: space-between; align-items: center;"}
        [:a.btn.btn-secondary {:href "/contracts"} "\u2190 Back to Contracts"]
        [:div {:style "display: flex; gap: 0.5rem;"}
         [:button.btn.btn-success
          {:type "button"
           :onclick "document.getElementById('origination-modal').classList.add('active')"}
          "Originate"]
         [:button.btn
          {:type "button"
           :style "background-color: #f59e0b; color: white;"
           :onclick "document.getElementById('retract-origination-modal').classList.add('active')"}
          "Retract Origination"]
         [:button.btn.btn-settlement
          {:type "button"
           :onclick "document.getElementById('settlement-modal').classList.add('active')"}
          "Calculate Settlement"]
         [:button.btn.btn-primary
          {:type "button"
           :onclick "document.getElementById('payment-modal').classList.add('active')"}
          "+ Record Payment"]
         [:button.btn
          {:type "button"
           :style "background-color: #dc2626; color: white;"
           :onclick "document.getElementById('retract-contract-modal').classList.add('active')"}
          "Delete Contract"]]]
       (contract-summary state)
       (fees-table (:fees state))
       (installments-table (:installments state))
       (transaction-log events)
       (payment-form contract-id)
       (retract-payment-modal contract-id)
       (retract-contract-modal contract-id (get-in state [:contract :external-id]))
       (settlement-form contract-id)
       (origination-form contract-id state)
       (retract-origination-modal contract-id)
       ;; JavaScript for modal handling
       [:script "
         function showRetractPaymentModal(paymentId, reference, amount) {
           document.getElementById('retract-payment-id').value = paymentId;
           document.getElementById('retract-payment-details').innerHTML =
             '<p><strong>Reference:</strong> ' + reference +
             '<br><strong>Amount:</strong> SAR ' + amount.toLocaleString('en-US', {minimumFractionDigits: 2}) + '</p>';
           document.getElementById('retract-payment-modal').classList.add('active');
         }

         function closeRetractPaymentModal() {
           document.getElementById('retract-payment-modal').classList.remove('active');
         }
       "]]))))

;; ============================================================
;; Boarding Form Components
;; ============================================================

(defn- boarding-errors-card
  "Render validation errors card.

   Args:
   - errors: Sequence of {:field :x :message \"...\"}

   Returns: Hiccup div or nil if no errors"
  [errors]
  (when (seq errors)
    [:div {:style "padding: 1rem; margin-bottom: 1.5rem; border-radius: 0.5rem; background-color: #fef2f2; border: 1px solid #fca5a5;"}
     [:h3 {:style "color: #991b1b; margin-bottom: 0.5rem; font-size: 1rem;"} "Validation Errors"]
     [:ul {:style "color: #991b1b; margin-left: 1.5rem; font-size: 0.875rem;"}
      (for [err errors]
        [:li {:key (:message err)} (:message err)])]]))

(defn- fee-row-template
  "Render a single fee input row.

   Args:
   - idx: Row index for unique IDs

   Returns: Hiccup div"
  [idx]
  [:div.fee-row {:style "display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 0.5rem; margin-bottom: 0.5rem; align-items: end;"
                 :data-fee-row "true"}
   [:div.form-group {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Fee Type"])
    [:select {:name "fee-type[]"
              :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}
     [:option {:value "management"} "Management"]
     [:option {:value "insurance"} "Insurance"]
     [:option {:value "processing"} "Processing"]
     [:option {:value "documentation"} "Documentation"]]]
   [:div.form-group {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Amount (SAR)"])
    [:input {:type "number" :name "fee-amount[]" :step "0.01" :min "0.01" :required true
             :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}]]
   [:div.form-group {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Due Date"])
    [:input {:type "date" :name "fee-due-date[]" :required true
             :style "width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;"}]]
   [:button.btn.btn-secondary {:type "button"
                                :onclick "this.closest('[data-fee-row]').remove()"
                                :style "padding: 0.5rem; font-size: 0.875rem; height: fit-content;"}
    "X"]])

(defn boarding-form-page
  "Render contract boarding form page.

   Supports two modes via type parameter:
   - \"new\" (default): Board a new loan (disbursement happens later)
   - \"existing\": Board an existing loan being migrated (includes payment history + disbursement)

   Args:
   - type: \"new\" or \"existing\"
   - errors: Optional sequence of validation errors
   - values: Optional map of pre-filled form values (for re-rendering after validation failure)

   Returns: Complete HTML page"
  [type & [{:keys [errors values]}]]
  (let [existing? (= type "existing")
        action (if existing? "/contracts/board-existing" "/contracts/board")]
    (page-layout
     "Board Contract"
     [:div
      ;; Back link
      [:div {:style "margin-bottom: 1rem;"}
       [:a.btn.btn-secondary {:href "/contracts"} "\u2190 Back to Contracts"]]

      ;; Tab switcher
      [:div {:style "display: flex; gap: 0; margin-bottom: 1.5rem; border-bottom: 2px solid #e5e7eb;"}
       [:a {:href "/contracts/new?type=new"
            :style (str "padding: 0.75rem 1.5rem; font-weight: 600; text-decoration: none; border-bottom: 2px solid "
                       (if-not existing? "#3b82f6" "transparent")
                       "; margin-bottom: -2px; color: "
                       (if-not existing? "#3b82f6" "#6b7280") ";")}
        "New Loan"]
       [:a {:href "/contracts/new?type=existing"
            :style (str "padding: 0.75rem 1.5rem; font-weight: 600; text-decoration: none; border-bottom: 2px solid "
                       (if existing? "#3b82f6" "transparent")
                       "; margin-bottom: -2px; color: "
                       (if existing? "#3b82f6" "#6b7280") ";")}
        "Existing Loan"]]

      ;; Validation errors
      (boarding-errors-card errors)

      ;; Form
      [:form {:method "post"
              :action action
              :enctype "multipart/form-data"}

       ;; ── Contract Terms ──
       [:div.card
        [:h2 "Contract Terms"]
        [:div {:style "display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
         [:div.form-group
          [:label {:for "external-id"} "External ID *"]
          [:input {:type "text" :id "external-id" :name "external-id" :required true
                   :value (get values "external-id")
                   :placeholder "e.g., LOAN-2024-001"}]]
         [:div.form-group
          [:label {:for "customer-name"} "Customer Name *"]
          [:input {:type "text" :id "customer-name" :name "customer-name" :required true
                   :value (get values "customer-name")
                   :placeholder "e.g., Acme Trading Co."}]]
         [:div.form-group
          [:label {:for "customer-id"} "Customer ID *"]
          [:input {:type "text" :id "customer-id" :name "customer-id" :required true
                   :value (get values "customer-id")
                   :placeholder "e.g., CR-1234567890"}]]
         [:div.form-group
          [:label {:for "start-date"} "Start Date *"]
          [:input {:type "date" :id "start-date" :name "start-date" :required true
                   :value (get values "start-date")}]]
         [:div.form-group
          [:label {:for "maturity-date"} "Maturity Date"]
          [:input {:type "date" :id "maturity-date" :name "maturity-date"
                   :value (get values "maturity-date")}]]
         [:div.form-group
          [:label {:for "principal"} "Principal (SAR) *"]
          [:input {:type "number" :id "principal" :name "principal" :step "0.01" :min "0.01" :required true
                   :value (get values "principal")
                   :placeholder "e.g., 1000000.00"}]]
         [:div.form-group
          [:label {:for "security-deposit"} "Security Deposit (SAR)"]
          [:input {:type "number" :id "security-deposit" :name "security-deposit" :step "0.01" :min "0"
                   :value (get values "security-deposit")
                   :placeholder "e.g., 50000.00"}]]
         [:div.form-group
          [:label {:for "facility-id"} "Facility External ID"]
          [:input {:type "text" :id "facility-id" :name "facility-id"
                   :value (get values "facility-id")
                   :placeholder "Leave blank for standalone contract"}]]]]

       ;; ── Commodity Details (optional, collapsible) ──
       [:div.card
        [:details
         [:summary {:style "cursor: pointer; font-size: 1.25rem; font-weight: 600; color: #111827;"}
          "Commodity Details (Optional)"]
         [:div {:style "margin-top: 1rem; display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
          [:div.form-group
           [:label {:for "commodity-description"} "Description"]
           [:input {:type "text" :id "commodity-description" :name "commodity-description"
                    :value (get values "commodity-description")
                    :placeholder "e.g., Palm Oil"}]]
          [:div.form-group
           [:label {:for "commodity-vendor"} "Vendor"]
           [:input {:type "text" :id "commodity-vendor" :name "commodity-vendor"
                    :value (get values "commodity-vendor")
                    :placeholder "e.g., Global Commodities Ltd."}]]
          [:div.form-group
           [:label {:for "commodity-quantity"} "Quantity"]
           [:input {:type "number" :id "commodity-quantity" :name "commodity-quantity" :step "0.01"
                    :value (get values "commodity-quantity")}]]
          [:div.form-group
           [:label {:for "commodity-unit-price"} "Unit Price (SAR)"]
           [:input {:type "number" :id "commodity-unit-price" :name "commodity-unit-price" :step "0.01"
                    :value (get values "commodity-unit-price")}]]]]]

       ;; ── Banking Details (optional, collapsible) ──
       [:div.card
        [:details
         [:summary {:style "cursor: pointer; font-size: 1.25rem; font-weight: 600; color: #111827;"}
          "Banking Details (Optional)"]
         [:div {:style "margin-top: 1rem; display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
          [:div.form-group
           [:label {:for "disbursement-iban"} "Disbursement IBAN"]
           [:input {:type "text" :id "disbursement-iban" :name "disbursement-iban"
                    :value (get values "disbursement-iban")
                    :placeholder "e.g., SA0380000000608010167519"}]]
          [:div.form-group
           [:label {:for "disbursement-bank"} "Disbursement Bank"]
           [:input {:type "text" :id "disbursement-bank" :name "disbursement-bank"
                    :value (get values "disbursement-bank")
                    :placeholder "e.g., ANB"}]]
          [:div.form-group
           [:label {:for "virtual-iban"} "Virtual IBAN"]
           [:input {:type "text" :id "virtual-iban" :name "virtual-iban"
                    :value (get values "virtual-iban")
                    :placeholder "e.g., SA0380000000608010167520"}]]]]]

       ;; ── Fees ──
       [:div.card
        [:h2 "Fees"]
        [:div#fee-rows
         (fee-row-template 0)]
        [:button.btn.btn-secondary
         {:type "button"
          :onclick "addFeeRow()"
          :style "margin-top: 0.5rem; font-size: 0.875rem;"}
         "+ Add Fee"]]

       ;; ── Schedule CSV Upload ──
       [:div.card
        [:h2 "Installment Schedule *"]
        [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
         "Upload a CSV file with the installment schedule. "
         "Format: Seq, Due Date, Principal Due, Profit Due, Remaining Principal (optional)"]
        [:div.form-group
         [:input {:type "file" :id "schedule-csv" :name "schedule-csv" :accept ".csv" :required true
                  :style "padding: 0.5rem; border: 1px solid #d1d5db; border-radius: 0.375rem; width: 100%;"}]]
        [:details {:style "margin-top: 0.5rem;"}
         [:summary {:style "cursor: pointer; font-size: 0.8125rem; color: #6b7280;"} "Example CSV format"]
         [:pre {:style "background-color: #f3f4f6; padding: 0.75rem; border-radius: 0.375rem; font-size: 0.75rem; overflow-x: auto; margin-top: 0.5rem;"}
          "Seq,Due Date,Principal Due,Profit Due,Remaining Principal\n1,2024-01-31,83333.33,12500.00,1000000.00\n2,2024-02-28,83333.33,12500.00,916666.67\n3,2024-03-31,83333.34,12500.00,833333.34"]]]

       ;; ── Existing Loan: Payment History CSV ──
       (when existing?
         [:div.card
          [:h2 "Payment History"]
          [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
           "Upload a CSV with historical payments to replay. "
           "Format: Date, External ID, Payment Summary, Amount, Paid By, Source, Reference"]
          [:div.form-group
           [:input {:type "file" :id "payment-csv" :name "payment-csv" :accept ".csv"
                    :style "padding: 0.5rem; border: 1px solid #d1d5db; border-radius: 0.375rem; width: 100%;"}]]
          [:details {:style "margin-top: 0.5rem;"}
           [:summary {:style "cursor: pointer; font-size: 0.8125rem; color: #6b7280;"} "Example CSV format"]
           [:pre {:style "background-color: #f3f4f6; padding: 0.75rem; border-radius: 0.375rem; font-size: 0.75rem; overflow-x: auto; margin-top: 0.5rem;"}
            "Date,External ID,Payment Summary,Amount,Paid By,Source,Reference\n2024-01-15,LOAN-001,Transfer,50000,Customer,,FT-ANB-12345\n2024-02-15,LOAN-001,Transfer,60000,Customer,,FT-ANB-12346"]]])

       ;; ── Existing Loan: Historical Disbursement ──
       (when existing?
         [:div.card
          [:h2 "Historical Disbursement"]
          [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
           "If the loan was already disbursed, enter the disbursement details."]
          [:div {:style "display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
           [:div.form-group
            [:label {:for "disb-amount"} "Disbursement Amount (SAR)"]
            [:input {:type "number" :id "disb-amount" :name "disb-amount" :step "0.01" :min "0.01"
                     :value (get values "disb-amount")
                     :placeholder "e.g., 1000000.00"}]]
           [:div.form-group
            [:label {:for "disb-date"} "Disbursement Date"]
            [:input {:type "date" :id "disb-date" :name "disb-date"
                     :value (get values "disb-date")}]]
           [:div.form-group
            [:label {:for "disb-reference"} "Reference"]
            [:input {:type "text" :id "disb-reference" :name "disb-reference"
                     :value (get values "disb-reference")
                     :placeholder "e.g., WT-001"}]]
           [:div.form-group
            [:label {:for "disb-iban"} "Destination IBAN"]
            [:input {:type "text" :id "disb-iban" :name "disb-iban"
                     :value (get values "disb-iban")}]]
           [:div.form-group
            [:label {:for "disb-bank"} "Destination Bank"]
            [:input {:type "text" :id "disb-bank" :name "disb-bank"
                     :value (get values "disb-bank")}]]]])

       ;; ── Submit ──
       [:div {:style "margin-top: 1.5rem; display: flex; gap: 0.75rem;"}
        [:button.btn.btn-success {:type "submit" :style "font-size: 1rem; padding: 0.75rem 2rem;"}
         (if existing? "Board Existing Contract" "Board New Contract")]
        [:a.btn.btn-secondary {:href "/contracts" :style "font-size: 1rem; padding: 0.75rem 2rem;"}
         "Cancel"]]]

      ;; JavaScript for dynamic fee rows
      [:script "
        var feeRowCount = 1;
        function addFeeRow() {
          feeRowCount++;
          var container = document.getElementById('fee-rows');
          var row = document.createElement('div');
          row.setAttribute('data-fee-row', 'true');
          row.style.cssText = 'display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 0.5rem; margin-bottom: 0.5rem; align-items: end;';
          row.innerHTML = '<div class=\"form-group\" style=\"margin-bottom: 0;\"><select name=\"fee-type[]\" style=\"width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;\"><option value=\"management\">Management</option><option value=\"insurance\">Insurance</option><option value=\"processing\">Processing</option><option value=\"documentation\">Documentation</option></select></div><div class=\"form-group\" style=\"margin-bottom: 0;\"><input type=\"number\" name=\"fee-amount[]\" step=\"0.01\" min=\"0.01\" required style=\"width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;\"></div><div class=\"form-group\" style=\"margin-bottom: 0;\"><input type=\"date\" name=\"fee-due-date[]\" required style=\"width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; font-size: 1rem;\"></div><button type=\"button\" class=\"btn btn-secondary\" onclick=\"this.closest(\\x27[data-fee-row]\\x27).remove()\" style=\"padding: 0.5rem; font-size: 0.875rem; height: fit-content;\">X</button>';
          container.appendChild(row);
        }
      "]])))

;; ============================================================
;; Error Pages
;; ============================================================

(defn error-404-page
  "Render 404 not found page.

   Returns: Complete HTML page"
  []
  (page-layout
   "Not Found"
   [:div.card
    [:h2 "404 - Not Found"]
    [:p "The page you're looking for doesn't exist."]
    [:p [:a {:href "/"} "Go home"]]]))

(defn error-500-page
  "Render 500 internal server error page.

   Args:
   - error: Exception or error message

   Returns: Complete HTML page"
  [error]
  (page-layout
   "Error"
   [:div.card
    [:h2 "500 - Internal Server Error"]
    [:p "An error occurred while processing your request."]
    [:pre {:style "background-color: #f3f4f6; padding: 1rem; border-radius: 0.375rem; overflow-x: auto;"}
     (str error)]
    [:p [:a {:href "/"} "Go home"]]]))
