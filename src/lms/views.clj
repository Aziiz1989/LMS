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

      /* Tabs */
      .tabs {
        display: flex;
        border-bottom: 2px solid #e5e7eb;
        margin-bottom: 1.5rem;
        gap: 0;
      }
      .tab {
        padding: 0.75rem 1.5rem;
        cursor: pointer;
        font-weight: 600;
        text-decoration: none;
        border-bottom: 2px solid transparent;
        margin-bottom: -2px;
        color: #6b7280;
        font-size: 0.9375rem;
      }
      .tab:hover { background: #f9fafb; color: #374151; }
      .tab.active {
        border-bottom-color: #3b82f6;
        color: #3b82f6;
      }
      .tab-content { display: none; }
      .tab-content.active { display: block; }

      /* History Timeline */
      .history-timeline {
        position: relative;
        padding-left: 2rem;
      }
      .history-timeline::before {
        content: '';
        position: absolute;
        left: 0.5rem;
        top: 0;
        bottom: 0;
        width: 2px;
        background: linear-gradient(to bottom, #d1d5db 0%, #e5e7eb 100%);
      }
      .history-tx-card {
        position: relative;
        padding: 1rem 0;
        border-bottom: 1px solid #f3f4f6;
        cursor: pointer;
        transition: background 0.15s;
      }
      .history-tx-card:last-child { border-bottom: none; }
      .history-tx-card:hover { background: #f9fafb; }
      .history-tx-card::before {
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
      .history-tx-card.op-created::before { background: #10b981; }
      .history-tx-card.op-updated::before { background: #3b82f6; }
      .history-tx-card.op-retracted::before { background: #ef4444; }
      .history-tx-card.op-admin::before { background: #6b7280; }
      .history-tx-card.op-correction::before { background: #f59e0b; }

      /* Expandable detail */
      .htx-details {
        display: none;
        margin-top: 0.75rem;
        padding-top: 0.75rem;
        border-top: 1px solid #e5e7eb;
      }
      .history-tx-card.expanded .htx-details { display: block; }
      .expand-chevron {
        float: right;
        font-size: 0.75rem;
        color: #9ca3af;
        transition: transform 0.2s;
      }
      .history-tx-card.expanded .expand-chevron { transform: rotate(180deg); }

      /* Entity badges */
      .entity-badge {
        display: inline-block;
        padding: 0.125rem 0.5rem;
        border-radius: 9999px;
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.025em;
        text-transform: uppercase;
      }
      .entity-badge.t-payment { background: #dcfce7; color: #166534; }
      .entity-badge.t-installment { background: #dbeafe; color: #1e40af; }
      .entity-badge.t-fee { background: #fef3c7; color: #92400e; }
      .entity-badge.t-deposit { background: #f3e8ff; color: #6b21a8; }
      .entity-badge.t-disbursement { background: #fee2e2; color: #991b1b; }
      .entity-badge.t-contract { background: #e0e7ff; color: #3730a3; }
      .entity-badge.t-principal-allocation { background: #ffedd5; color: #9a3412; }

      /* Changes table */
      .changes-table {
        width: 100%;
        font-size: 0.8125rem;
        border-collapse: collapse;
        margin-top: 0.5rem;
      }
      .changes-table th {
        text-align: left;
        color: #6b7280;
        font-weight: 500;
        padding: 0.25rem 0.5rem;
        border-bottom: 1px solid #e5e7eb;
        font-size: 0.75rem;
      }
      .changes-table td {
        padding: 0.25rem 0.5rem;
        border-bottom: 1px solid #f3f4f6;
      }
      .change-old {
        text-decoration: line-through;
        color: #dc2626;
        opacity: 0.7;
      }
      .change-new {
        font-weight: 600;
        color: #059669;
      }
      .change-arrow {
        color: #9ca3af;
        padding: 0 0.25rem;
      }

      /* History filters */
      .history-filters {
        display: flex;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 1rem;
        padding: 0.75rem 1rem;
        background: #f9fafb;
        border-radius: 0.5rem;
        border: 1px solid #e5e7eb;
        align-items: flex-end;
      }
      .history-filters .filter-group {
        margin-bottom: 0;
      }
      .history-filters .filter-group label {
        display: block;
        font-size: 0.6875rem;
        color: #6b7280;
        margin-bottom: 0.25rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .history-filters input, .history-filters select {
        padding: 0.375rem 0.5rem;
        border: 1px solid #d1d5db;
        border-radius: 0.375rem;
        font-size: 0.8125rem;
      }

      /* History pagination */
      .history-pagination {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.75rem 0;
        font-size: 0.8125rem;
        color: #6b7280;
      }
      .history-pagination a {
        padding: 0.375rem 0.75rem;
        border: 1px solid #d1d5db;
        border-radius: 0.375rem;
        text-decoration: none;
        color: #374151;
        font-weight: 500;
      }
      .history-pagination a:hover { background: #f3f4f6; }
      .history-pagination a.disabled {
        opacity: 0.5;
        pointer-events: none;
      }

      /* History summary line */
      .htx-summary {
        font-size: 0.875rem;
        color: #374151;
      }
      .htx-header {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        margin-bottom: 0.25rem;
      }
      .htx-timestamp {
        font-size: 0.75rem;
        color: #9ca3af;
        font-family: ui-monospace, SFMono-Regular, 'SF Mono', monospace;
      }
      .htx-meta {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        font-size: 0.75rem;
        color: #6b7280;
        margin-top: 0.25rem;
      }
      .htx-entity-group {
        margin-bottom: 0.5rem;
        padding: 0.5rem;
        background: #f9fafb;
        border-radius: 0.375rem;
      }
      .htx-entity-header {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-bottom: 0.25rem;
        font-size: 0.8125rem;
        font-weight: 500;
      }
      .htx-op-label {
        font-size: 0.6875rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .htx-op-label.op-created { color: #059669; }
      .htx-op-label.op-updated { color: #2563eb; }
      .htx-op-label.op-retracted { color: #dc2626; }
    "]]
   [:body
    [:div.header
     [:div.container
      [:h1 "LMS - Loan Management System - I am Aziz"]
      [:nav.nav
       [:a {:href "/contracts"} "Contracts"]
       [:a {:href "/parties"} "Parties"]
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
     [:h2 (str (:external-id contract) " - " (get-in contract [:borrower :legal-name]))]
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
       [:div.value (format-date (:maturity-date contract))]]
      (when (:days-to-first-installment contract)
        [:div.summary-item
         [:div.label "Days to 1st Installment"]
         [:div.value (str (:days-to-first-installment contract) " days")]])]]))

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


;; ============================================================
;; Comprehensive History Components (HTMX tab)
;; ============================================================

(defn- entity-type-display
  "Human-readable entity type name."
  [entity-type]
  (case entity-type
    :payment "Payment"
    :installment "Installment"
    :fee "Fee"
    :deposit "Deposit"
    :disbursement "Disbursement"
    :contract "Contract"
    :principal-allocation "Principal Allocation"
    (some-> entity-type name str/capitalize)))

(defn- tx-operation-class
  "CSS class for a transaction operation."
  [operation]
  (case operation
    :created "op-created"
    :updated "op-updated"
    :retracted "op-retracted"
    :admin "op-admin"
    :correction "op-correction"
    "op-updated"))

(defn- entity-operation-summary
  "Generate summary text for an entity change."
  [entity]
  (let [{:keys [entity-type label operation sub-type]} entity
        type-name (entity-type-display entity-type)
        label-str (when label (str " " label))
        sub-str (when sub-type
                  (str " (" (str/capitalize (name sub-type)) ")"))]
    (case operation
      :created (str type-name sub-str label-str " created")
      :retracted (str type-name sub-str label-str " retracted")
      :updated (str type-name sub-str label-str " updated")
      (str type-name sub-str label-str " changed"))))

(defn- tx-summary-text
  "Generate summary text for a full transaction."
  [tx]
  (let [{:keys [operation entities tx-metadata]} tx
        tx-type (:tx/type tx-metadata)
        entity-count (count entities)]
    (cond
      ;; Admin event with type
      tx-type
      (str (str/capitalize (name tx-type))
           (when (> entity-count 0)
             (str " (" entity-count " entit"
                  (if (= 1 entity-count) "y" "ies") ")")))
      ;; Correction
      (= operation :correction)
      (let [reason (:tx/reason tx-metadata)]
        (str "Correction"
             (when reason (str " (" (str/capitalize (name reason)) ")"))))
      ;; Single entity change
      (= entity-count 1)
      (entity-operation-summary (first entities))
      ;; Multiple entities
      :else
      (let [types (frequencies (map :entity-type entities))]
        (str/join ", "
                  (for [[t cnt] types]
                    (str cnt " " (entity-type-display t)
                         (when (> cnt 1) "s")
                         " " (name operation))))))))

(defn- attribute-changes-table
  "Render a table of attribute changes with before -> after values."
  [changes]
  (when (seq changes)
    [:table.changes-table
     [:thead
      [:tr
       [:th "Attribute"]
       [:th "Change"]]]
     [:tbody
      (for [c changes
            :let [{:keys [attribute operation display-name display-old display-new]} c]]
        [:tr {:key (str attribute)}
         [:td display-name]
         [:td
          (case operation
            :updated [:span
                      [:span.change-old display-old]
                      [:span.change-arrow "\u2192"]
                      [:span.change-new display-new]]
            :asserted [:span.change-new display-new]
            :retracted [:span.change-old display-old]
            [:span (or display-new display-old)])]])]]))

(defn- history-transaction-card
  "Render a single transaction card in the history timeline.
   Expandable: click to show attribute-level changes."
  [tx idx]
  (let [{:keys [tx-instant tx-metadata entities operation]} tx
        author (:tx/author tx-metadata)
        note (:tx/note tx-metadata)
        card-id (str "htx-card-" idx)]
    [:div.history-tx-card
     {:class (tx-operation-class operation)
      :onclick (str "document.getElementById('" card-id "').classList.toggle('expanded')")
      :id card-id}
     ;; Header: summary + timestamp
     [:div.htx-header
      [:span.htx-summary (tx-summary-text tx)]
      [:span.htx-timestamp (format-datetime tx-instant)]]
     ;; Chevron indicator
     [:span.expand-chevron "\u25BC"]
     ;; Meta row
     [:div.htx-meta
      (when author
        [:span [:span {:style "color: #9ca3af;"} "By: "] author])
      (when note
        [:span {:style "font-style: italic; color: #92400e;"}
         "\"" note "\""])]
     ;; Expandable details
     [:div.htx-details
      (for [[i entity] (map-indexed vector entities)]
        [:div.htx-entity-group {:key (str (:entity-id entity) "-" i)}
         ;; Entity header with badge
         [:div.htx-entity-header
          [:span.entity-badge {:class (str "t-" (name (:entity-type entity)))}
           (entity-type-display (:entity-type entity))]
          (when (:label entity)
            [:span {:style "color: #374151;"} (:label entity)])
          [:span.htx-op-label {:class (str "op-" (name (:operation entity)))}
           (name (:operation entity))]]
         ;; Attribute changes table
         (attribute-changes-table (:display-changes entity))])]]))

(defn- history-filters-bar
  "Render filter controls for history tab."
  [contract-id filters]
  (let [{:keys [entity-types from-date to-date]} filters
        base-url (str "/contracts/" contract-id "/history-tab")]
    [:form.history-filters
     {:hx-get base-url
      :hx-target "#history-tab-content"
      :hx-trigger "change"
      :hx-include "closest form"}
     [:div.filter-group
      [:label "From"]
      [:input {:type "date" :name "from-date"
               :value (or from-date "")}]]
     [:div.filter-group
      [:label "To"]
      [:input {:type "date" :name "to-date"
               :value (or to-date "")}]]
     [:div.filter-group
      [:label "Entity Type"]
      [:select {:name "entity-types"}
       [:option {:value ""} "All"]
       [:option {:value "payment" :selected (= entity-types #{:payment})} "Payments"]
       [:option {:value "installment" :selected (= entity-types #{:installment})} "Installments"]
       [:option {:value "fee" :selected (= entity-types #{:fee})} "Fees"]
       [:option {:value "disbursement" :selected (= entity-types #{:disbursement})} "Disbursements"]
       [:option {:value "deposit" :selected (= entity-types #{:deposit})} "Deposits"]
       [:option {:value "contract" :selected (= entity-types #{:contract})} "Contract"]]]
     [:div.filter-group
      [:button.btn.btn-secondary
       {:type "button"
        :style "font-size: 0.75rem; padding: 0.375rem 0.75rem;"
        :onclick (str "htmx.ajax('GET', '" base-url "', '#history-tab-content')")}
       "Clear"]]]))

(defn- history-pagination-bar
  "Render pagination controls for history tab."
  [contract-id pagination filters]
  (let [{:keys [page total total-pages]} pagination
        {:keys [entity-types from-date to-date]} filters
        base-url (str "/contracts/" contract-id "/history-tab")
        build-url (fn [p]
                    (str base-url "?page=" p
                         (when entity-types
                           (str "&entity-types=" (str/join "," (map name entity-types))))
                         (when from-date (str "&from-date=" from-date))
                         (when to-date (str "&to-date=" to-date))))]
    [:div.history-pagination
     [:span (str "Showing page " page " of " total-pages
                 " (" total " transaction" (when (not= 1 total) "s") " total)")]
     [:div {:style "display: flex; gap: 0.5rem;"}
      [:a {:href "#"
           :class (when (<= page 1) "disabled")
           :hx-get (build-url (max 1 (dec page)))
           :hx-target "#history-tab-content"} "\u2190 Prev"]
      [:a {:href "#"
           :class (when (>= page total-pages) "disabled")
           :hx-get (build-url (min total-pages (inc page)))
           :hx-target "#history-tab-content"} "Next \u2192"]]]))

(defn history-tab-content
  "Render the content for the History tab. Returned as HTMX partial.

   Args:
   - contract-id: UUID
   - transactions: paginated, formatted history transactions
   - filters: current filter state
   - pagination: {:page :total :total-pages}"
  [contract-id transactions filters pagination]
  [:div#history-tab-content
   ;; Filters
   (history-filters-bar contract-id filters)
   ;; Timeline
   (if (empty? transactions)
     [:p.text-muted {:style "padding: 2rem; text-align: center;"}
      "No history found for the selected filters."]
     [:div.history-timeline
      (for [[idx tx] (map-indexed vector transactions)]
        (history-transaction-card tx idx))])
   ;; Pagination
   (when (> (:total-pages pagination) 1)
     (history-pagination-bar contract-id pagination filters))])

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
  "Render origination modal with unified principal deduction selection.

   Shows each fee individually with checkboxes, deposit option, and
   installment prepayment field. The user selects what to deduct from
   principal — the disbursement amount updates accordingly.

   Args:
   - contract-id: UUID of contract
   - state: Contract state map from contract/contract-state

   Returns: Hiccup modal div"
  [contract-id state]
  (let [contract (:contract state)
        principal (or (:principal contract) 0M)
        security-deposit (or (:security-deposit-required contract) 0M)
        fees (->> (:fees state)
                  (filter #(= :unpaid (:status %))))
        deposit-held (or (:deposit-held state) 0M)
        deposit-needed (max 0M (- security-deposit deposit-held))
        credit-balance (or (:credit-balance state) 0M)
        excess-return (if (pos? credit-balance) credit-balance 0M)
        disb-iban (:disbursement-iban contract)
        disb-bank (:disbursement-bank contract)
        fmt-val (fn [n] (format "%.2f" (double n)))
        fmt-date (fn [d] (when d (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))
        ;; Pre-calculate initial deductions (fees due on/before today + deposit)
        today (java.util.Date.)
        initial-fee-total (->> fees
                               (filter #(not (.after ^java.util.Date (:due-date %) today)))
                               (map :amount)
                               (reduce + 0M))
        initial-deductions (+ initial-fee-total deposit-needed)
        initial-disbursement (max 0M (- principal initial-deductions))]
    [:div#origination-modal.modal
     [:div.modal-content
      [:div.modal-header
       [:h3 "Originate Contract"]]
      [:p {:style "font-size: 0.875rem; color: #6b7280; margin-bottom: 1rem;"}
       "Select deductions from principal. Disbursement updates automatically."]

      [:form {:method "post"
              :action (str "/contracts/" contract-id "/originate")}

       ;; Principal display
       [:div.preview-section
        [:div.preview-item
         [:div.label "Principal"]
         [:div.value (str "SAR " (format-money principal))]]]

       ;; Deductions from principal
       [:div {:style "margin: 1rem 0; padding: 1rem; background: #f9fafb; border-radius: 0.5rem;"}
        [:h4 {:style "margin: 0 0 0.75rem 0; font-size: 0.875rem; color: #374151;"}
         "Deductions from Principal"]

        ;; Per-fee checkboxes
        (when (seq fees)
          [:div {:style "margin-bottom: 0.75rem;"}
           (for [{:keys [id type amount due-date]} fees
                 :let [fee-due-today? (not (.after ^java.util.Date due-date today))
                       checkbox-id (str "fee-" id)]]
             [:div {:style "display: flex; align-items: center; gap: 0.5rem; padding: 0.375rem 0;"
                    :key (str id)}
              [:input {:type "checkbox"
                       :id checkbox-id
                       :class "orig-fee-checkbox"
                       :data-amount (fmt-val amount)
                       :checked fee-due-today?
                       :onchange "updateOrigDisbursement()"}]
              [:input {:type "hidden" :class "orig-fee-id-input" :disabled true
                       :name "settle-fee-id[]" :value (str id)
                       :data-checkbox checkbox-id}]
              [:input {:type "hidden" :class "orig-fee-amount-input" :disabled true
                       :name "settle-fee-amount[]" :value (fmt-val amount)
                       :data-checkbox checkbox-id}]
              [:label {:for checkbox-id
                       :style "flex: 1; display: flex; justify-content: space-between; cursor: pointer;"}
               [:span (str "Fee: " (name type)
                           (when due-date (str " (due " (fmt-date due-date) ")")))]
               [:span {:style "font-weight: 500;"} (str "SAR " (format-money amount))]]])])

        ;; Deposit checkbox
        (when (pos? deposit-needed)
          [:div {:style "display: flex; align-items: center; gap: 0.5rem; padding: 0.375rem 0;
                         border-top: 1px solid #e5e7eb; margin-top: 0.25rem; padding-top: 0.625rem;"}
           [:input {:type "checkbox"
                    :id "orig-deposit-checkbox"
                    :checked true
                    :data-amount (fmt-val deposit-needed)
                    :onchange "updateOrigDisbursement()"}]
           [:input {:type "hidden" :name "deposit-from-funding" :disabled true
                    :id "orig-deposit-hidden" :value (fmt-val deposit-needed)}]
           [:label {:for "orig-deposit-checkbox"
                    :style "flex: 1; display: flex; justify-content: space-between; cursor: pointer;"}
            [:span "Security Deposit"]
            [:span {:style "font-weight: 500;"} (str "SAR " (format-money deposit-needed))]]])

        ;; Installment prepayment
        [:div {:style "display: flex; align-items: center; gap: 0.5rem; padding: 0.375rem 0;
                       border-top: 1px solid #e5e7eb; margin-top: 0.25rem; padding-top: 0.625rem;"}
         [:label {:for "orig-inst-prepayment" :style "flex: 1;"}
          "Installment prepayment"]
         [:input {:type "number"
                  :id "orig-inst-prepayment"
                  :name "installment-prepayment"
                  :step "0.01" :min "0"
                  :placeholder "0.00"
                  :style "width: 10rem; text-align: right;"
                  :onchange "updateOrigDisbursement()"
                  :oninput "updateOrigDisbursement()"}]]]

       ;; Summary
       [:div.preview-section {:style "margin-top: 0.75rem;"}
        [:div.preview-item {:style "font-weight: bold; padding-top: 0.5rem; border-top: 2px solid #d1d5db;"}
         [:div.label "Merchant Disbursement"]
         [:div.value {:id "orig-disbursement-display"} (str "SAR " (format-money initial-disbursement))]]
        [:input {:type "hidden" :name "disbursement-amount" :id "orig-disbursement-value"
                 :value (fmt-val initial-disbursement)}]
        (when (pos? excess-return)
          [:div.preview-item
           [:div.label "Plus: Excess return to customer"]
           [:div.value (str "SAR " (format-money excess-return))]])
        (when (pos? excess-return)
          [:input {:type "hidden" :name "excess-return" :value (fmt-val excess-return)}])]

       ;; User inputs (date, reference, IBAN, bank)
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
         "Originate"]]]

      ;; JavaScript for dynamic disbursement calculation and hidden input toggling
      [:script
       (str "
function updateOrigDisbursement() {
  var principal = " (fmt-val principal) ";
  var total = 0;

  // Sum checked fee amounts
  document.querySelectorAll('.orig-fee-checkbox').forEach(function(cb) {
    if (cb.checked) {
      total += parseFloat(cb.dataset.amount);
    }
  });

  // Toggle hidden inputs for fees (disabled inputs are not submitted)
  document.querySelectorAll('.orig-fee-id-input, .orig-fee-amount-input').forEach(function(inp) {
    var cb = document.getElementById(inp.dataset.checkbox);
    inp.disabled = !cb.checked;
  });

  // Deposit
  var depCb = document.getElementById('orig-deposit-checkbox');
  var depHidden = document.getElementById('orig-deposit-hidden');
  if (depCb && depCb.checked) {
    total += parseFloat(depCb.dataset.amount);
    if (depHidden) depHidden.disabled = false;
  } else {
    if (depHidden) depHidden.disabled = true;
  }

  // Installment prepayment
  var prepay = parseFloat(document.getElementById('orig-inst-prepayment').value) || 0;
  total += prepay;

  var disbursement = Math.max(0, principal - total).toFixed(2);
  document.getElementById('orig-disbursement-display').textContent = 'SAR ' + parseFloat(disbursement).toLocaleString('en', {minimumFractionDigits: 2});
  document.getElementById('orig-disbursement-value').value = disbursement;
}

// Initialize on load
updateOrigDisbursement();
")]]]))
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
   - flash: Optional flash message map with :type and :message keys

   Returns: Complete HTML page"
  ([state] (contract-detail-page state nil))
  ([state flash]
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
       ;; Tabs navigation
       [:div.tabs
        [:a.tab.active {:href "#" :data-tab "overview" :onclick "switchTab(event, 'overview')"} "Overview"]
        [:a.tab {:href "#" :data-tab "schedule" :onclick "switchTab(event, 'schedule')"} "Schedule"]
        [:a.tab {:href "#" :data-tab "history"
                 :onclick "switchTab(event, 'history')"
                 :hx-get (str "/contracts/" contract-id "/history-tab")
                 :hx-target "#tab-history"
                 :hx-trigger "click once"
                 :hx-swap "innerHTML"} "History"]]
       ;; Tab content panels
       [:div#tab-overview.tab-content.active
        (contract-summary state)
        (fees-table (:fees state))]
       [:div#tab-schedule.tab-content
        (installments-table (:installments state))]
       [:div#tab-history.tab-content
        [:div {:style "text-align: center; padding: 2rem; color: #6b7280;"}
         "Click the History tab to load transaction history..."]]
       ;; Modals (outside tabs)
       (payment-form contract-id)
       (retract-payment-modal contract-id)
       (retract-contract-modal contract-id (get-in state [:contract :external-id]))
       (settlement-form contract-id)
       (origination-form contract-id state)
       (retract-origination-modal contract-id)
       ;; JavaScript for tabs and modal handling
       [:script "
         function switchTab(event, tabName) {
           event.preventDefault();
           // Deactivate all tabs and panels
           document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
           document.querySelectorAll('.tab-content').forEach(p => p.classList.remove('active'));
           // Activate clicked tab and its panel
           event.currentTarget.classList.add('active');
           document.getElementById('tab-' + tabName).classList.add('active');
         }

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
          [:label {:for "borrower-party-id"} "Borrower (Company) *"]
          [:input {:type "hidden" :id "borrower-party-id" :name "borrower-party-id"
                   :value (get values "borrower-party-id")}]
          [:input {:type "text" :id "borrower-search" :autocomplete "off"
                   :placeholder "Search by company name or CR number..."
                   :value (get values "borrower-name")
                   :hx-get "/api/parties/search?type=company"
                   :hx-trigger "keyup changed delay:300ms"
                   :hx-target "#borrower-results"
                   :hx-swap "innerHTML"
                   :name "borrower-search"}]
          [:div {:id "borrower-results"
                 :style "border: 1px solid #e5e7eb; border-radius: 0.375rem; max-height: 200px; overflow-y: auto; display: none;"}]
          [:small {:style "color: #6b7280; font-size: 0.75rem;"}
           "Search and select a company party. "
           [:a {:href "/parties/new" :target "_blank"} "Create new party"]]]
         [:div.form-group
          [:label {:for "start-date"} "Start Date *"]
          [:input {:type "date" :id "start-date" :name "start-date" :required true
                   :value (get values "start-date")}]]
         [:div.form-group
          [:label {:for "principal"} "Principal (SAR) *"]
          [:input {:type "number" :id "principal" :name "principal" :step "0.01" :min "0.01" :required true
                   :value (get values "principal")
                   :placeholder "e.g., 1000000.00"}]]
         [:div.form-group
          [:label {:for "days-to-first-installment"} "Days to First Installment"]
          [:input {:type "number" :id "days-to-first-installment" :name "days-to-first-installment"
                   :step "1" :min "1"
                   :value (get values "days-to-first-installment")
                   :placeholder "e.g., 30"}]
          [:small {:style "color: #6b7280; font-size: 0.75rem;"}
           "Days from disbursement to first installment. Schedule dates shift at disbursement."]]
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

      ;; JavaScript for party search and dynamic fee rows
      [:script "
        function selectParty(id, name) {
          document.getElementById('borrower-party-id').value = id;
          document.getElementById('borrower-search').value = name;
          document.getElementById('borrower-results').style.display = 'none';
        }
        document.addEventListener('htmx:afterSwap', function(event) {
          if (event.detail.target.id === 'borrower-results') {
            event.detail.target.style.display = event.detail.target.innerHTML.trim() ? 'block' : 'none';
          }
        });
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
;; Party Views
;; ============================================================

(defn party-list-page
  "Render parties list page.

   Args:
   - parties: Sequence of party entity maps

   Returns: Complete HTML page"
  [parties]
  (page-layout
   "Parties"
   [:div.card
    [:div {:style "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"}
     [:h2 {:style "margin-bottom: 0;"} "All Parties"]
     [:a.btn.btn-success {:href "/parties/new"} "+ Create Party"]]
    (if (empty? parties)
      [:div.empty-state
       [:p "No parties found."]
       [:p.text-muted "Create a party to get started."]]
      [:table
       [:thead
        [:tr
         [:th "Legal Name"]
         [:th "Type"]
         [:th "Identifier"]
         [:th "Contact"]
         [:th "Actions"]]]
       [:tbody
        (for [p parties]
          [:tr {:key (:party/id p)}
           [:td [:a {:href (str "/parties/" (:party/id p))}
                 (:party/legal-name p)]]
           [:td (case (:party/type p)
                  :party.type/company "Company"
                  :party.type/person "Person"
                  (str (:party/type p)))]
           [:td (or (:party/cr-number p) (:party/national-id p))]
           [:td (or (:party/email p) (:party/phone p) "-")]
           [:td [:a.btn.btn-primary {:href (str "/parties/" (:party/id p))}
                 "View"]]])]])]))

(defn party-form-page
  "Render party creation/edit form.

   Args:
   - party: Existing party for edit (nil for create)
   - opts: Optional map with :errors and :values

   Returns: Complete HTML page"
  [party & [{:keys [errors values]}]]
  (let [editing? (some? party)]
    (page-layout
     (if editing? "Edit Party" "Create Party")
     [:div
      [:div {:style "margin-bottom: 1rem;"}
       [:a.btn.btn-secondary {:href "/parties"} "\u2190 Back to Parties"]]

      (when (seq errors)
        [:div.card {:style "background-color: #fef2f2; border-color: #fca5a5;"}
         [:h3 {:style "color: #991b1b;"} "Validation Errors"]
         [:ul
          (for [err errors]
            [:li {:style "color: #991b1b;"} (:message err)])]])

      [:form {:method "post"
              :action (if editing?
                        (str "/parties/" (:party/id party) "/update")
                        "/parties")}
       [:div.card
        [:h2 (if editing? "Edit Party" "New Party")]
        [:div {:style "display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;"}
         [:div.form-group
          [:label {:for "type"} "Type *"]
          (if editing?
            [:input {:type "text" :disabled true
                     :value (case (:party/type party)
                              :party.type/company "Company"
                              :party.type/person "Person"
                              "")}]
            [:select {:id "type" :name "type" :required true
                      :onchange "togglePartyFields()"}
             [:option {:value ""} "Select type..."]
             [:option {:value "company"
                       :selected (= (get values "type") "company")} "Company"]
             [:option {:value "person"
                       :selected (= (get values "type") "person")} "Person"]])]
         [:div.form-group
          [:label {:for "legal-name"} "Legal Name *"]
          [:input {:type "text" :id "legal-name" :name "legal-name" :required true
                   :value (or (get values "legal-name")
                              (:party/legal-name party))
                   :placeholder "Full legal name"}]]
         [:div.form-group {:id "cr-number-group"}
          [:label {:for "cr-number"} "CR Number"]
          [:input {:type "text" :id "cr-number" :name "cr-number"
                   :value (or (get values "cr-number")
                              (:party/cr-number party))
                   :placeholder "Commercial Registration number"}]
          [:small {:style "color: #6b7280; font-size: 0.75rem;"} "Required for companies"]]
         [:div.form-group {:id "national-id-group"}
          [:label {:for "national-id"} "National ID"]
          [:input {:type "text" :id "national-id" :name "national-id"
                   :value (or (get values "national-id")
                              (:party/national-id party))
                   :placeholder "National ID / Iqama number"}]
          [:small {:style "color: #6b7280; font-size: 0.75rem;"} "Required for persons"]]
         [:div.form-group
          [:label {:for "email"} "Email"]
          [:input {:type "email" :id "email" :name "email"
                   :value (or (get values "email")
                              (:party/email party))
                   :placeholder "contact@example.com"}]]
         [:div.form-group
          [:label {:for "phone"} "Phone"]
          [:input {:type "text" :id "phone" :name "phone"
                   :value (or (get values "phone")
                              (:party/phone party))
                   :placeholder "+966..."}]]
         [:div.form-group {:style "grid-column: span 2;"}
          [:label {:for "address"} "Address"]
          [:input {:type "text" :id "address" :name "address"
                   :value (or (get values "address")
                              (:party/address party))
                   :placeholder "Full address"}]]]]

       [:div {:style "margin-top: 1.5rem; display: flex; gap: 0.75rem;"}
        [:button.btn.btn-success {:type "submit" :style "font-size: 1rem; padding: 0.75rem 2rem;"}
         (if editing? "Update Party" "Create Party")]
        [:a.btn.btn-secondary {:href "/parties" :style "font-size: 1rem; padding: 0.75rem 2rem;"}
         "Cancel"]]]

      [:script "
        function togglePartyFields() {
          var type = document.getElementById('type').value;
          var crGroup = document.getElementById('cr-number-group');
          var nidGroup = document.getElementById('national-id-group');
          if (type === 'company') {
            crGroup.style.display = '';
            nidGroup.style.display = 'none';
          } else if (type === 'person') {
            crGroup.style.display = 'none';
            nidGroup.style.display = '';
          } else {
            crGroup.style.display = '';
            nidGroup.style.display = '';
          }
        }
        togglePartyFields();
      "]])))

(defn party-detail-page
  "Render party detail page.

   Args:
   - party: Party entity map
   - contracts: Contracts this party is involved in
   - ownerships: Ownership records for this company (who owns it)
   - owns: Ownership records where this party is the owner

   Returns: Complete HTML page"
  [party contracts ownerships owns]
  (let [company? (= :party.type/company (:party/type party))
        party-id (:party/id party)]
    (page-layout
     (str "Party: " (:party/legal-name party))
     [:div
      [:div {:style "margin-bottom: 1rem; display: flex; justify-content: space-between; align-items: center;"}
       [:a.btn.btn-secondary {:href "/parties"} "\u2190 Back to Parties"]
       [:a.btn.btn-primary {:href (str "/parties/" party-id "/edit")} "Edit"]]

      ;; Party info card
      [:div.card
       [:h2 (:party/legal-name party)]
       [:div.summary-grid
        [:div.summary-item
         [:div.label "Type"]
         [:div.value (if company? "Company" "Person")]]
        [:div.summary-item
         [:div.label (if company? "CR Number" "National ID")]
         [:div.value (or (if company? (:party/cr-number party) (:party/national-id party)) "-")]]
        (when (:party/email party)
          [:div.summary-item
           [:div.label "Email"]
           [:div.value (:party/email party)]])
        (when (:party/phone party)
          [:div.summary-item
           [:div.label "Phone"]
           [:div.value (:party/phone party)]])
        (when (:party/address party)
          [:div.summary-item {:style "grid-column: span 2;"}
           [:div.label "Address"]
           [:div.value (:party/address party)]])]]

      ;; Contracts section
      [:div.card
       [:h2 "Contracts"]
       (if (empty? contracts)
         [:p.text-muted "No contract involvement."]
         [:table
          [:thead
           [:tr
            [:th "Contract"]
            [:th "Role"]]]
          [:tbody
           (for [c contracts]
             [:tr {:key (str (:contract-id c) (:role c))}
              [:td [:a {:href (str "/contracts/" (:contract-id c))}
                    (str (:contract-id c))]]
              [:td (name (:role c))]])]])]

      ;; Ownership section (for companies: who owns this company)
      (when company?
        [:div.card
         [:h2 "Ownership Structure"]
         (when (seq ownerships)
           [:table
            [:thead
             [:tr
              [:th "Owner"]
              [:th "Type"]
              [:th.text-right "Percentage"]
              [:th "Actions"]]]
            [:tbody
             (for [o ownerships]
               [:tr {:key (:ownership/id o)}
                [:td [:a {:href (str "/parties/" (get-in o [:ownership/owner :party/id]))}
                      (get-in o [:ownership/owner :party/legal-name])]]
                [:td (case (get-in o [:ownership/owner :party/type])
                       :party.type/company "Company"
                       :party.type/person "Person"
                       "-")]
                [:td.text-right (str (:ownership/percentage o) "%")]
                [:td [:form {:method "post" :style "display: inline;"
                             :action (str "/parties/" party-id
                                          "/ownership/" (:ownership/id o) "/remove")}
                      [:button.btn {:type "submit"
                                    :style "background-color: #dc2626; color: white; font-size: 0.75rem; padding: 0.25rem 0.5rem;"
                                    :onclick "return confirm('Remove this ownership record?')"}
                       "Remove"]]]])]])
         (when (empty? ownerships)
           [:p.text-muted "No ownership records."])
         [:h3 {:style "margin-top: 1.5rem;"} "Add Owner"]
         [:form {:method "post"
                 :action (str "/parties/" party-id "/ownership")}
          [:div {:style "display: flex; gap: 0.75rem; align-items: flex-end;"}
           [:div.form-group {:style "flex: 2; position: relative;"}
            [:label {:for "owner-search"} "Owner Party"]
            [:input {:type "hidden" :id "owner-party-id" :name "owner-party-id"}]
            [:input {:type "text" :id "owner-search" :name "owner-search"
                     :placeholder "Search by name, CR, or National ID..."
                     :autocomplete "off"}]
            [:div {:id "owner-search-results"
                   :style "position: absolute; z-index: 10; background: white; border: 1px solid #d1d5db; border-radius: 0.375rem; width: 100%; max-height: 200px; overflow-y: auto; display: none;"}]]
           [:div.form-group {:style "flex: 1;"}
            [:label {:for "percentage"} "Percentage"]
            [:input {:type "number" :id "percentage" :name "percentage"
                     :min "0.01" :max "100" :step "0.01"
                     :placeholder "e.g. 60"
                     :required true}]]
           [:div
            [:button.btn.btn-primary {:type "submit"} "Add Owner"]]]
          [:script "
(function() {
  var searchInput = document.getElementById('owner-search');
  var resultsDiv = document.getElementById('owner-search-results');
  var hiddenInput = document.getElementById('owner-party-id');
  searchInput.addEventListener('input', function() {
    var q = this.value;
    if (q.length < 1) { resultsDiv.style.display = 'none'; return; }
    fetch('/api/parties/search?q=' + encodeURIComponent(q))
      .then(function(r) { return r.text(); })
      .then(function(html) {
        resultsDiv.innerHTML = html;
        resultsDiv.style.display = 'block';
      });
  });
  document.addEventListener('click', function(e) {
    if (!resultsDiv.contains(e.target) && e.target !== searchInput) {
      resultsDiv.style.display = 'none';
    }
  });
  window.selectParty = function(id, name) {
    hiddenInput.value = id;
    searchInput.value = name;
    resultsDiv.style.display = 'none';
  };
})();
"]]])

      ;; Ownership section (what this party owns)
      (when (seq owns)
        [:div.card
         [:h2 "Owns Shares In"]
         [:table
          [:thead
           [:tr
            [:th "Company"]
            [:th.text-right "Percentage"]]]
          [:tbody
           (for [o owns]
             [:tr {:key (:ownership/id o)}
              [:td [:a {:href (str "/parties/" (get-in o [:ownership/company :party/id]))}
                    (get-in o [:ownership/company :party/legal-name])]]
              [:td.text-right (str (:ownership/percentage o) "%")]])]]])])))

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
