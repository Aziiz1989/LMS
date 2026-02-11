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

   Returns: Hiccup div (always renders #flash-area for Datastar morphing)"
  [flash]
  (if flash
    (let [{:keys [type message]} (if (string? flash)
                                   {:type :success :message flash}
                                   flash)
          cls (case type
                :error "flash-danger"
                :warning "flash-caution"
                "flash-ok")]
      [:div#flash-area {:class cls}
       [:strong (case type :error "Error: " :warning "Warning: " "Success: ")]
       [:span message]])
    [:div#flash-area]))

(defn format-money
  "Format money amount in SAR with thousands separators.

   Example:
   (format-money 1234567.89M) => \"1,234,567.89\""
  [amount]
  (when amount
    (let [formatted (format "%.2f" (double amount))]
      (str/replace formatted #"\B(?=(\d{3})+(?!\d))" ","))))

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
  (let [cls (case status
              :paid "st-ok"
              :active "st-ok"
              :partial "st-warn"
              :overdue "st-err"
              "st-off")]
    [:span {:class cls}
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
  (html5 {:data-theme "light"}
         [:head
          [:meta {:charset "UTF-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title (str title " | LMS")]
          [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
          [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
          [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,300..800;1,9..40,300..800&display=swap"}]
          [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.0-RC.7/bundles/datastar.js" :type "module"}]
          (include-css "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css")
          [:style "
      :root {
        --pico-font-family: 'DM Sans', system-ui, sans-serif;
        --pico-font-size: 0.9375rem;
        --pico-border-radius: 0.5rem;
        --pico-primary: #6366f1;
        --pico-primary-hover: #4f46e5;
        --pico-primary-inverse: #fff;
        --pico-background-color: #fafaf9;
        --pico-card-background-color: #fff;
        --pico-color: #292524;
        --pico-h1-color: #1c1917;
        --pico-h2-color: #1c1917;
        --pico-h3-color: #44403c;
        --pico-muted-color: #78716c;
        --pico-muted-border-color: #e7e5e4;
        --color-ok: #16a34a;
        --color-ok-bg: #f0fdf4;
        --color-warn: #d97706;
        --color-warn-bg: #fffbeb;
        --color-err: #dc2626;
        --color-err-bg: #fef2f2;
        --font-mono: ui-monospace, 'SFMono-Regular', monospace;
      }
      body > nav { border-bottom: 1px solid var(--pico-muted-border-color); margin-bottom: 1.5rem; }
      [class^='st-'] { display: inline-block; padding: 0.125rem 0.5rem; border-radius: 9999px; font-size: 0.75rem; font-weight: 600; white-space: nowrap; }
      .st-ok { color: var(--color-ok); background: var(--color-ok-bg); }
      .st-warn { color: var(--color-warn); background: var(--color-warn-bg); }
      .st-err { color: var(--color-err); background: var(--color-err-bg); }
      .st-off { color: var(--pico-muted-color); background: #f5f5f4; }
      .flash-ok { border-left: 3px solid var(--color-ok); background: var(--color-ok-bg); padding: 1rem; border-radius: var(--pico-border-radius); margin-bottom: 1rem; }
      .flash-danger { border-left: 3px solid var(--color-err); background: var(--color-err-bg); padding: 1rem; border-radius: var(--pico-border-radius); margin-bottom: 1rem; }
      .flash-caution { border-left: 3px solid var(--color-warn); background: var(--color-warn-bg); padding: 1rem; border-radius: var(--pico-border-radius); margin-bottom: 1rem; }
      .tabs { display: flex; gap: 0; border-bottom: 1px solid var(--pico-muted-border-color); margin-bottom: 1.5rem; }
      .tabs button { background: none; border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 0.625rem 1rem; color: var(--pico-muted-color); font-size: 0.875rem; font-weight: 500; cursor: pointer; margin-bottom: -1px; }
      .tabs button:hover { color: var(--pico-color); background: none; }
      .tabs button.active { color: var(--pico-primary); border-bottom-color: var(--pico-primary); font-weight: 600; }
      .tab-content { display: none; }
      .tab-content.active { display: block; }
      .override-fields { display: none; }
      .override-fields.visible { display: block; }
      button, [role='button'], input[type='submit'], [type='button'], [type='submit'], [type='reset'] { width: auto !important; }
      .btn-danger { --pico-background-color: var(--color-err); --pico-border-color: var(--color-err); --pico-color: #fff; }
      .btn-caution { --pico-background-color: var(--color-warn); --pico-border-color: var(--color-warn); --pico-color: #fff; }
      .text-right { text-align: right; }
      td.text-right, th.text-right { font-family: var(--font-mono); font-size: 0.8125rem; }
      .mono { font-family: var(--font-mono); font-size: 0.8125rem; }
      .text-muted { color: var(--pico-muted-color); }
      .mb-0 { margin-bottom: 0 !important; }
      .page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
      .action-bar { display: flex; gap: 0.5rem; flex-wrap: wrap; }
      .empty-state { text-align: center; padding: 2rem; border: 2px dashed var(--pico-muted-border-color); border-radius: var(--pico-border-radius); color: var(--pico-muted-color); }
      .empty-state p { margin: 0; }
      .empty-state p:first-child { font-weight: 600; margin-bottom: 0.25rem; }
      .party-search-result { padding: 0.5rem 1rem; cursor: pointer; border-bottom: 1px solid var(--pico-muted-border-color); }
      .party-search-result:hover { background: #f5f5f4; }
      .party-search-result:last-child { border-bottom: none; }
      .change-old { text-decoration: line-through; color: var(--color-err); opacity: 0.7; }
      .change-new { font-weight: 600; color: var(--pico-primary); }
      input[type='hidden'] { display: none !important; }
      input[type='number'] { font-family: var(--font-mono); }
    "]]
         [:body
          [:nav.container
           [:ul [:li [:strong "LMS"]]]
           [:ul
            [:li [:a {:href "/contracts"} "Contracts"]]
            [:li [:a {:href "/parties"} "Parties"]]
            [:li [:a {:href "/about"} "About"]]]]
          [:main.container
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
    [:table.striped
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
         [:td [:a {:role "button" :href (str "/contracts/" (:id contract))}
               "View"]]])]]))

(defn contract-list-page
  "Render contracts list page.

   Args:
   - contracts: Sequence of contract summary maps

   Returns: Complete HTML page"
  [contracts]
  (page-layout
   "Contracts"
   [:article
    [:div.page-header
     [:h2.mb-0 "All Contracts"]
     [:a {:role "button" :href "/contracts/new"} "+ Board Contract"]]
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
    [:div#contract-summary
     [:h2 (str (:external-id contract) " - " (get-in contract [:borrower :legal-name]))]
     [:div.grid
      [:article
       [:small "Status"] [:br]
       [:strong (status-badge (:status contract))]]
      [:article
       [:small "Principal"] [:br]
       [:strong.mono (format-money (:principal contract))]]
      [:article
       [:small "Outstanding"] [:br]
       [:strong.mono [:mark (format-money (:total-outstanding state))]]]
      [:article
       [:small "Credit Balance"] [:br]
       [:strong.mono (format-money (:credit-balance state))]]]
     [:div.grid
      [:article
       [:small "Deposit Held"] [:br]
       [:strong.mono (format-money (:deposit-held state))]]
      [:article
       [:small "Disbursed"] [:br]
       [:strong (or (format-date (:disbursed-at contract)) "Pending")]]
      [:article
       [:small "Maturity Date"] [:br]
       [:strong (format-date (:maturity-date contract))]]
      (when (:days-to-first-installment contract)
        [:article
         [:small "Days to 1st Installment"] [:br]
         [:strong (str (:days-to-first-installment contract) " days")]])]]))
(defn fees-table
  "Render fees table.

   Args:
   - fees: Sequence of fee maps with paid/outstanding/status

   Returns: Hiccup div"
  [fees]
  [:article#fees-section
   [:h2 "Fees"]
   (if (empty? fees)
     [:p.text-muted "No fees."]
     [:table.striped
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
  [:div#installments-section
   [:h2 "Installment Schedule"]
   [:div {:style "overflow-x: auto;"}
    [:table.striped
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
                      (pos? days) "color: var(--color-err); font-weight: 600;"
                      :else "color: var(--pico-muted-color);"))}
          (:days-delinquent inst)]])]]]])

(defn parties-section
  "Render guarantors and authorized signatories section for contract detail.

   Args:
   - contract-id: UUID
   - contract: Contract map from state with :guarantors and :authorized-signatories

   Returns: Hiccup div"
  [contract-id contract]
  [:article#parties-section
   [:h2 "Parties"]

   ;; --- Guarantors ---
   [:div {:style "margin-bottom: 1.5rem;"}
    [:h3 {:style "font-size: 1rem; margin-bottom: 0.75rem;"} "Guarantors"]
    (if (empty? (:guarantors contract))
      [:p.text-muted "No guarantors."]
      [:table
       [:thead
        [:tr
         [:th "Name"]
         [:th "Type"]
         [:th {:style "width: 5rem;"} ""]]]
       [:tbody
        (for [g (:guarantors contract)]
          [:tr {:key (:id g)}
           [:td [:a {:href (str "/parties/" (:id g))} (:legal-name g)]]
           [:td (when (:type g) (str/capitalize (name (:type g))))]
           [:td
            [:button.btn-danger
             {:type "button"
              "data-on:click" (str "@post('/contracts/" contract-id "/guarantors/" (:id g) "/remove')")}
             "Remove"]]])]])

    ;; Add guarantor form
    [:div {:style "margin-top: 0.75rem;"
           "data-signals:guarantor-party-id" "''"
           "data-signals:guarantor-search" "''"
           "data-signals:show-guarantor-results" "false"}
     [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/guarantors', {contentType: 'form'})")}
      [:div {:style "display: flex; gap: 0.5rem; align-items: flex-start;"}
       [:input {:type "hidden" :name "party-id" "data-bind:guarantor-party-id" true}]
       [:div {:style "flex: 1;"}
        [:input {:type "text" :autocomplete "off"
                 :placeholder "Search party by name..."
                 "data-bind:guarantor-search" true
                 "data-on:keyup__debounce.300ms" "$showGuarantorResults = true; @get('/api/parties/search?target=guarantor-results&context=guarantor')"}]
        [:div#guarantor-results
         {:style "border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); max-height: 200px; overflow-y: auto;"
          "data-show" "$showGuarantorResults"}]]
       [:button {:type "submit"} "Add"]]]]]

   ;; --- Authorized Signatories ---
   [:div
    [:h3 {:style "font-size: 1rem; margin-bottom: 0.75rem;"} "Authorized Signatories"]
    (if (empty? (:authorized-signatories contract))
      [:p.text-muted "No authorized signatories."]
      [:table
       [:thead
        [:tr
         [:th "Name"]
         [:th {:style "width: 5rem;"} ""]]]
       [:tbody
        (for [s (:authorized-signatories contract)]
          [:tr {:key (:id s)}
           [:td [:a {:href (str "/parties/" (:id s))} (:legal-name s)]]
           [:td
            [:button.btn-danger
             {:type "button"
              "data-on:click" (str "@post('/contracts/" contract-id "/signatories/" (:id s) "/remove')")}
             "Remove"]]])]])

    ;; Add signatory form
    [:div {:style "margin-top: 0.75rem;"
           "data-signals:signatory-party-id" "''"
           "data-signals:signatory-search" "''"
           "data-signals:show-signatory-results" "false"}
     [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/signatories', {contentType: 'form'})")}
      [:div {:style "display: flex; gap: 0.5rem; align-items: flex-start;"}
       [:input {:type "hidden" :name "party-id" "data-bind:signatory-party-id" true}]
       [:div {:style "flex: 1;"}
        [:input {:type "text" :autocomplete "off"
                 :placeholder "Search person by name..."
                 "data-bind:signatory-search" true
                 "data-on:keyup__debounce.300ms" "$showSignatoryResults = true; @get('/api/parties/search?type=person&target=signatory-results&context=signatory')"}]
        [:div#signatory-results
         {:style "border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); max-height: 200px; overflow-y: auto;"
          "data-show" "$showSignatoryResults"}]]
       [:button {:type "submit"} "Add"]]]]]])

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
    [:table
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
                      " \u2192 "
                      [:span.change-new display-new]]
            :asserted [:span.change-new display-new]
            :retracted [:span.change-old display-old]
            [:span (or display-new display-old)])]])]]))

(defn- history-transaction-card
  "Render a single transaction in the history as a <details> element.
   Native browser expand/collapse — no JavaScript needed."
  [tx _idx]
  (let [{:keys [tx-instant tx-metadata entities]} tx
        author (:tx/author tx-metadata)
        note (:tx/note tx-metadata)]
    [:details
     [:summary
      (tx-summary-text tx)
      " "
      [:small (format-datetime tx-instant)]
      (when author [:small (str " by " author)])
      (when note [:small [:em (str " \"" note "\"")]])]
     ;; Entity changes (shown on expand)
     (for [[i entity] (map-indexed vector entities)]
       [:div {:key (str (:entity-id entity) "-" i)
              :style "margin-bottom: 0.5rem;"}
        [:p [:kbd (entity-type-display (:entity-type entity))]
         (when (:label entity) (str " " (:label entity)))
         " " [:small [:em (name (:operation entity))]]]
        (attribute-changes-table (:display-changes entity))])]))

(defn- history-filters-bar
  "Render filter controls for history tab."
  [contract-id filters]
  (let [{:keys [entity-types from-date to-date]} filters
        base-url (str "/contracts/" contract-id "/history-tab")]
    [:fieldset.grid
     {"data-on:change" (str "@get('" base-url "')")}
     [:label "From"
      [:input {:type "date" :name "from-date"
               :value (or from-date "")}]]
     [:label "To"
      [:input {:type "date" :name "to-date"
               :value (or to-date "")}]]
     [:label "Entity Type"
      [:select {:name "entity-types"}
       [:option {:value ""} "All"]
       [:option {:value "payment" :selected (= entity-types #{:payment})} "Payments"]
       [:option {:value "installment" :selected (= entity-types #{:installment})} "Installments"]
       [:option {:value "fee" :selected (= entity-types #{:fee})} "Fees"]
       [:option {:value "disbursement" :selected (= entity-types #{:disbursement})} "Disbursements"]
       [:option {:value "deposit" :selected (= entity-types #{:deposit})} "Deposits"]
       [:option {:value "contract" :selected (= entity-types #{:contract})} "Contract"]]]
     [:label "\u00a0"
      [:button.secondary
       {:type "button"
        "data-on:click" (str "@get('" base-url "')")}
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
    [:div {:style "display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 0;"}
     [:small (str "Showing page " page " of " total-pages
                  " (" total " transaction" (when (not= 1 total) "s") " total)")]
     [:div {:role "group"}
      [:button.secondary {:type "button"
                          :disabled (<= page 1)
                          "data-on:click" (str "@get('" (build-url (max 1 (dec page))) "')")} "\u2190 Prev"]
      [:button.secondary {:type "button"
                          :disabled (>= page total-pages)
                          "data-on:click" (str "@get('" (build-url (min total-pages (inc page))) "')")} "Next \u2192"]]]))

(defn history-tab-content
  "Render the content for the History tab. Returned as SSE fragment.

   Args:
   - contract-id: UUID
   - transactions: paginated, formatted history transactions
   - filters: current filter state
   - pagination: {:page :total :total-pages}"
  [contract-id transactions filters pagination]
  [:div#history-tab-content
   ;; Filters
   (history-filters-bar contract-id filters)
   ;; Transaction list
   (if (empty? transactions)
     [:p [:small "No history found for the selected filters."]]
     (for [[idx tx] (map-indexed vector transactions)]
       (history-transaction-card tx idx)))
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
  [:div#preview-area
   [:h4 "Payment Allocation Preview"]
   [:table
    [:tbody
     (for [change changes]
       [:tr {:key (str (:type change) "-" (:id change))}
        [:td (:description change)]
        [:td.text-right (format-money (:amount change))]])]
    [:tfoot
     [:tr
      [:th "Outstanding After Payment"]
      [:th.text-right (format-money (:total-outstanding after))]]]]])

(defn settlement-result
  "Render settlement calculation breakdown.

   Returned as an SSE fragment — morphed into the modal via Datastar.

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
    [:div#settlement-result-area
     ;; Hero: final amount
     [:p {:style "text-align: center; margin-bottom: 1rem;"}
      [:small (if has-refund? "Refund Due to Customer" "Settlement Amount")]
      [:br]
      [:strong {:style "font-size: 2rem;"}
       (str "SAR " (format-money (if has-refund? refund-due settlement-amount)))]]

     ;; Waterfall breakdown
     [:table
      [:thead [:tr [:th "Component"] [:th.text-right "Amount (SAR)"]]]
      [:tbody
       [:tr [:td "+ Outstanding Principal"] [:td.text-right (format-money outstanding-principal)]]
       [:tr [:td "+ Accrued Unpaid Profit"] [:td.text-right (format-money effective-accrued-unpaid-profit)]]
       [:tr [:td [:small "  Total accrued"]] [:td.text-right [:small (format-money accrued-profit)]]]
       [:tr [:td [:small "  Already paid"]] [:td.text-right [:small (format-money profit-already-paid)]]]
       [:tr [:td "+ Outstanding Fees"] [:td.text-right (format-money outstanding-fees)]]
       (when (pos? penalty-days)
         [:tr [:td (str "+ Penalty (" penalty-days " day" (when (> penalty-days 1) "s") ")")] [:td.text-right (format-money penalty-amount)]])
       (when (pos? credit-balance)
         [:tr [:td "\u2212 Credit Balance"] [:td.text-right (format-money credit-balance)]])]
      [:tfoot
       [:tr [:th (if has-refund? "Refund Due" "Settlement Amount")]
        [:th.text-right (format-money (if has-refund? refund-due settlement-amount))]]]]

     ;; Manual override indicator
     (when manual-override?
       [:p [:small [:em "Manual override applied to accrued unpaid profit"]]])

     ;; Period metadata
     [:p [:small
          (when current-period-start (str "Period start: " (format-date current-period-start) " | "))
          (when current-period-end (str "Period end: " (format-date current-period-end) " | "))
          (str "Accrued days: " accrued-days " | ")
          (str "Unearned profit: SAR " (format-money unearned-profit))]]]))

(defn payment-form
  "Render payment recording form with Datastar preview.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup form"
  [contract-id]
  [:dialog#payment-modal {:data-attr "{'open': $showPaymentModal}"}
   [:article
    [:header [:h3 "Record Payment"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/record-payment', {contentType: 'form'})")}
     [:label {:for "amount"} "Payment Amount (SAR)"
      [:input {:type "number"
               :id "amount"
               :name "amount"
               :step "0.01"
               :min "0.01"
               :required true
               :placeholder "e.g., 500000.00"}]]
     [:label {:for "reference"} "Payment Reference"
      [:input {:type "text"
               :id "reference"
               :name "reference"
               :required true
               :placeholder "e.g., PAY-12345"}]]
     [:label {:for "original-date"} "Payment Date"
      [:input {:type "date"
               :id "original-date"
               :name "original-date"
               :required true
               :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
     [:label {:for "note"} "Note (Optional)"
      [:textarea {:id "note"
                  :name "note"
                  :rows "3"
                  :placeholder "e.g., Payment received via bank transfer from account ending in 1234"}]]
     [:div#preview-area]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showPaymentModal = false"}
       "Cancel"]
      [:button
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
  [:dialog#retract-payment-modal {:data-attr "{'open': $showRetractPaymentModal}"}
   [:article
    [:header [:h3 "Retract Payment"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/retract-payment', {contentType: 'form'})")}
     [:input {:type "hidden"
              :name "payment-id"
              "data-bind:retract-payment-id" true}]
     [:p "This will remove the payment as a data correction. Use this for recording errors (wrong amount, duplicate, wrong contract)."]
     [:p [:small "For real money returned to customer, use a refund disbursement instead."]]
     [:div.flash-caution
      [:p [:strong "Reference: "] [:span {:data-text "$retractRef"}]]
      [:p [:strong "Amount: SAR "] [:span {:data-text "$retractAmount"}]]]
     [:label {:for "retract-reason"} "Reason for Correction"
      [:select {:id "retract-reason"
                :name "reason"
                :required true}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong amount)"]
       [:option {:value "duplicate-removal"} "Duplicate Removal"]
       [:option {:value "erroneous-entry"} "Erroneous Entry (wrong contract)"]]]
     [:label {:for "retract-note"} "Note (Optional)"
      [:textarea {:id "retract-note"
                  :name "note"
                  :rows "2"
                  :placeholder "e.g., Duplicate of FT-ANB-123"}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showRetractPaymentModal = false"}
       "Cancel"]
      [:button.btn-caution
       {:type "submit"}
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
  [:dialog#retract-contract-modal {:data-attr "{'open': $showRetractContractModal}"}
   [:article
    [:header [:h3 "Delete Contract"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/retract-contract', {contentType: 'form'})")}
     [:div.flash-danger
      [:p [:strong "This will permanently retract the following contract and all its data:"]]
      [:p [:strong external-id]]
      [:ul
       [:li "Contract record"]
       [:li "All fees"]
       [:li "All installments"]
       [:li "All payments"]
       [:li "All disbursements"]
       [:li "All deposits"]
       [:li "All principal allocations"]]]
     [:p [:small "Use this only for data corrections (contract boarded in error, duplicate, wrong customer). "
          "Datomic history preserves retracted data for audit purposes."]]
     [:label {:for "retract-contract-reason"} "Reason for Correction *"
      [:select {:id "retract-contract-reason"
                :name "reason"
                :required true}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong data)"]
       [:option {:value "duplicate-removal"} "Duplicate Removal"]
       [:option {:value "erroneous-entry"} "Erroneous Entry (wrong customer/contract)"]]]
     [:label {:for "retract-contract-note"} "Note (Optional)"
      [:textarea {:id "retract-contract-note"
                  :name "note"
                  :rows "2"
                  :placeholder "e.g., Contract boarded against wrong customer CR-456"}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showRetractContractModal = false"}
       "Cancel"]
      [:button.btn-danger
       {:type "submit"}
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
  [:dialog#settlement-modal {:data-attr "{'open': $showSettlementModal}"}
   [:article
    [:header [:h3 "Settlement Calculator"]]
    [:form {"data-on:submit" (str "@post('" "/contracts/" contract-id "/calculate-settlement', {contentType: 'form'})")}
     [:label {:for "settlement-date"} "Settlement Date"
      [:input {:type "date"
               :id "settlement-date"
               :name "settlement-date"
               :required true
               :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
     [:label {:for "penalty-days"} "Penalty Days"
      [:input {:type "number"
               :id "penalty-days"
               :name "penalty-days"
               :min "0"
               :step "1"
               :required true
               :value "0"
               :placeholder "0"}]]
     [:label
      [:input {:type "checkbox"
               :id "override-toggle"
               "data-bind:override-toggle" true}]
      "Manual override for accrued unpaid profit"]
     [:div#override-fields.override-fields {:data-class "{'visible': $overrideToggle}"}
      [:input {:type "number"
               :id "manual-override"
               :name "manual-override"
               :step "0.01"
               :min "0"
               :placeholder "Override amount (SAR)"}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showSettlementModal = false"}
       "Close"]
      [:button
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
    (let [;; Build signals map for all checkboxes
          indexed-fees (map-indexed vector fees)
          fee-signals (str/join ", "
                                (for [[i {:keys [due-date]}] indexed-fees
                                      :let [due-today? (not (.after ^java.util.Date due-date today))]]
                                  (str "origFee" i ": " (if due-today? "true" "false"))))
          deposit-signal (when (pos? deposit-needed) "origDeposit: true")
          all-signals (str/join ", " (remove str/blank? [fee-signals
                                                         deposit-signal
                                                         "origPrepayment: 0"]))
          ;; Build the disbursement effect expression
          fee-sum-expr (str/join ""
                                 (for [[i {:keys [amount]}] indexed-fees]
                                   (str "if($origFee" i ") t+=" (fmt-val amount) ";")))
          deposit-expr (when (pos? deposit-needed)
                         (str "if($origDeposit) t+=" (fmt-val deposit-needed) ";"))
          effect-expr (str "var t=0;"
                           fee-sum-expr
                           (or deposit-expr "")
                           "t+=parseFloat($origPrepayment)||0;"
                           "var d=Math.max(0," (fmt-val principal) "-t).toFixed(2);"
                           "document.getElementById('orig-disbursement-display').textContent='SAR '+Number(d).toLocaleString('en',{minimumFractionDigits:2});"
                           "document.getElementById('orig-disbursement-value').value=d;")]
      [:dialog#origination-modal {:data-attr "{'open': $showOriginationModal}"
                                  :data-signals (str "{" all-signals "}")}
       [:article
        [:header [:h3 "Originate Contract"]]
        [:p [:small "Select deductions from principal. Disbursement updates automatically."]]

        [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/originate', {contentType: 'form'})")}

         ;; Principal display
         [:table
          [:tbody
           [:tr [:td "Principal"] [:td.text-right [:strong (str "SAR " (format-money principal))]]]]]

         ;; Deductions from principal
         [:fieldset
          [:legend "Deductions from Principal"]

          ;; Per-fee checkboxes
          (when (seq fees)
            (for [[i {:keys [id type amount due-date]}] indexed-fees
                  :let [signal-name (str "origFee" i)
                        checkbox-id (str "fee-" id)]]
              [:label {:for checkbox-id
                       :key (str id)
                       :style "display: flex; justify-content: space-between; cursor: pointer;"}
               [:span
                [:input {:type "checkbox"
                         :id checkbox-id
                         :data-bind (keyword signal-name)}]
                [:input {:type "hidden"
                         :name "settle-fee-id[]" :value (str id)
                         :data-attr (str "{'disabled': !$" signal-name "}")}]
                [:input {:type "hidden"
                         :name "settle-fee-amount[]" :value (fmt-val amount)
                         :data-attr (str "{'disabled': !$" signal-name "}")}]
                (str "Fee: " (name type)
                     (when due-date (str " (due " (fmt-date due-date) ")")))]
               [:span [:strong (str "SAR " (format-money amount))]]]))

          ;; Deposit checkbox
          (when (pos? deposit-needed)
            [:label {:for "orig-deposit-checkbox"
                     :style "display: flex; justify-content: space-between; cursor: pointer;"}
             [:span
              [:input {:type "checkbox"
                       :id "orig-deposit-checkbox"
                       "data-bind:orig-deposit" true}]
              [:input {:type "hidden" :name "deposit-from-funding"
                       :value (fmt-val deposit-needed)
                       :data-attr "{'disabled': !$origDeposit}"}]
              "Security Deposit"]
             [:span [:strong (str "SAR " (format-money deposit-needed))]]])

          ;; Installment prepayment
          [:label {:for "orig-inst-prepayment"
                   :style "display: flex; justify-content: space-between; align-items: center;"}
           "Installment prepayment"
           [:input {:type "number"
                    :id "orig-inst-prepayment"
                    :name "installment-prepayment"
                    :step "0.01" :min "0"
                    :placeholder "0.00"
                    :style "width: 10rem; text-align: right;"
                    "data-bind:orig-prepayment" true}]]]

         ;; Summary with data-effect for reactive calculation
         [:table {:style "margin-top: 0.75rem;"}
          [:div {:data-effect effect-expr}]
          [:tfoot
           [:tr [:th "Merchant Disbursement"]
            [:th.text-right {:id "orig-disbursement-display"} (str "SAR " (format-money initial-disbursement))]]
           [:input {:type "hidden" :name "disbursement-amount" :id "orig-disbursement-value"
                    :value (fmt-val initial-disbursement)}]
           (when (pos? excess-return)
             [:tr [:td "Plus: Excess return to customer"]
              [:td.text-right (str "SAR " (format-money excess-return))]])
           (when (pos? excess-return)
             [:input {:type "hidden" :name "excess-return" :value (fmt-val excess-return)}])]]

         ;; User inputs (date, reference, IBAN, bank)
         [:hr]
         [:label {:for "orig-date"} "Origination Date *"
          [:input {:type "date"
                   :id "orig-date"
                   :name "date"
                   :required true
                   :value (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))}]]
         [:label {:for "orig-disb-reference"} "Wire Reference *"
          [:input {:type "text"
                   :id "orig-disb-reference"
                   :name "disbursement-reference"
                   :required true
                   :placeholder "e.g., WT-001"}]]
         [:div.grid
          [:label {:for "orig-disb-iban"} "Destination IBAN"
           [:input {:type "text"
                    :id "orig-disb-iban"
                    :name "disbursement-iban"
                    :value (or disb-iban "")
                    :placeholder "e.g., SA242000..."}]]
          [:label {:for "orig-disb-bank"} "Destination Bank"
           [:input {:type "text"
                    :id "orig-disb-bank"
                    :name "disbursement-bank"
                    :value (or disb-bank "")
                    :placeholder "e.g., ANB"}]]]

         [:footer
          [:button.secondary
           {:type "button"
            "data-on:click" "$showOriginationModal = false"}
           "Cancel"]
          [:button
           {:type "submit"}
           "Originate"]]]]])))
(defn retract-origination-modal
  "Render modal for retracting origination entities (data correction).

   Retracts all origination-created entities: principal allocations,
   funding disbursements, deposits from funding, and excess returns.
   Datomic history preserves all retracted datoms for audit.

   Args:
   - contract-id: UUID of contract

   Returns: Hiccup modal"
  [contract-id]
  [:dialog#retract-origination-modal {:data-attr "{'open': $showRetractOriginationModal}"}
   [:article
    [:header [:h3 "Retract Origination"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/retract-origination', {contentType: 'form'})")}
     [:div.flash-caution
      [:p [:strong "This will retract all origination entities:"]]
      [:ul
       [:li "Principal allocations (fee deductions from funding)"]
       [:li "Funding disbursements"]
       [:li "Deposits from funding"]
       [:li "Excess return disbursements"]]]
     [:p [:small "Use this only for data corrections (wrong amounts, duplicate origination). "
          "Datomic history preserves retracted data for audit purposes."]]
     [:label {:for "retract-orig-reason"} "Reason for Correction *"
      [:select {:id "retract-orig-reason"
                :name "reason"
                :required true}
       [:option {:value ""} "Select reason..."]
       [:option {:value "correction"} "Correction (wrong amounts)"]
       [:option {:value "duplicate-removal"} "Duplicate Origination"]
       [:option {:value "erroneous-entry"} "Erroneous Entry"]]]
     [:label {:for "retract-orig-note"} "Note (Optional)"
      [:textarea {:id "retract-orig-note"
                  :name "note"
                  :rows "2"
                  :placeholder "e.g., Wrong fee deduction amount, re-originating"}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showRetractOriginationModal = false"}
       "Cancel"]
      [:button.btn-caution
       {:type "submit"}
       "Retract Origination"]]]]])

(defn generate-clearance-letter-modal
  "Modal form for generating a clearance letter."
  [contract-id]
  [:dialog#generate-clearance-letter-modal {:data-attr "{'open': $showClearanceLetterModal}"}
   [:article
    [:header [:h3 "Generate Clearance Letter"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/generate-clearance-letter', {contentType: 'form'})")}
     [:label "Settlement Date"
      [:input {:type "date" :name "settlement-date" :required true}]]
     [:label "Penalty Days"
      [:input {:type "number" :name "penalty-days" :min "0" :value "0" :required true}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showClearanceLetterModal = false"}
       "Cancel"]
      [:button {:type "submit"} "Generate"]]]]])

(defn generate-statement-modal
  "Modal form for generating a statement."
  [contract-id]
  [:dialog#generate-statement-modal {:data-attr "{'open': $showStatementModal}"}
   [:article
    [:header [:h3 "Generate Statement"]]
    [:form {"data-on:submit" (str "@post('/contracts/" contract-id "/generate-statement', {contentType: 'form'})")}
     [:label "Period Start"
      [:input {:type "date" :name "period-start" :required true}]]
     [:label "Period End"
      [:input {:type "date" :name "period-end" :required true}]]
     [:footer
      [:button.secondary
       {:type "button"
        "data-on:click" "$showStatementModal = false"}
       "Cancel"]
      [:button {:type "submit"} "Generate"]]]]])

(defn document-list-section
  "Display all documents for a contract with download buttons."
  [contract-id documents]
  [:div#documents-section {:style "padding: 1rem;"}
   [:h3 "Documents"]

   ;; Action buttons for generating documents
   [:div {:style "margin-bottom: 1.5rem; display: flex; gap: 0.5rem; flex-wrap: wrap;"}
    [:button
     {:type "button"
      "data-on:click" "$showClearanceLetterModal = true"}
     "+ Generate Clearance Letter"]
    [:button
     {:type "button"
      "data-on:click" "$showStatementModal = true"}
     "+ Generate Statement"]
    [:button
     {:type "button"
      "data-on:click" (str "@post('/contracts/" contract-id "/generate-contract-agreement')")}
     "+ Generate Contract Agreement"]]

   ;; Clearance Letters
   (when (seq (:clearance-letters documents))
     [:div {:style "margin-bottom: 2rem;"}
      [:h4 "Clearance Letters"]
      [:table
       [:thead
        [:tr
         [:th "Settlement Date"]
         [:th "Amount (SAR)"]
         [:th "Penalty Days"]
         [:th "Actions"]]]
       [:tbody
        (for [cl (:clearance-letters documents)]
          [:tr
           [:td (format-date (:clearance-letter/settlement-date cl))]
           [:td (format-money (:clearance-letter/settlement-amount cl))]
           [:td (:clearance-letter/penalty-days cl)]
           [:td
            [:a {:role "button" :href (str "/contracts/" contract-id "/documents/clearance-letter/"
                                           (:clearance-letter/id cl) "/download")
                 :download ""}
             "Download PDF"]]])]]])

   ;; Statements
   (when (seq (:statements documents))
     [:div {:style "margin-bottom: 2rem;"}
      [:h4 "Statements"]
      [:table
       [:thead
        [:tr
         [:th "Period Start"]
         [:th "Period End"]
         [:th "Actions"]]]
       [:tbody
        (for [stmt (:statements documents)]
          [:tr
           [:td (format-date (:statement/period-start stmt))]
           [:td (format-date (:statement/period-end stmt))]
           [:td
            [:a {:role "button" :href (str "/contracts/" contract-id "/documents/statement/"
                                           (:statement/id stmt) "/download")
                 :download ""}
             "Download PDF"]]])]]])

   ;; Contract Agreements
   (when (seq (:contract-agreements documents))
     [:div {:style "margin-bottom: 2rem;"}
      [:h4 "Contract Agreements"]
      [:table
       [:thead
        [:tr
         [:th "Generated Date"]
         [:th "Actions"]]]
       [:tbody
        (for [ca (:contract-agreements documents)]
          (let [generated-date (:db/txInstant ca)]
            [:tr
             [:td (format-date generated-date)]
             [:td
              [:a {:role "button" :href (str "/contracts/" contract-id "/documents/contract-agreement/"
                                             (:contract-agreement/id ca) "/download")
                   :download ""}
               "Download PDF"]]]))]]])

   ;; No documents message
   (when (and (empty? (:clearance-letters documents))
              (empty? (:statements documents))
              (empty? (:contract-agreements documents)))
     [:div {:style "text-align: center; padding: 2rem; color: var(--pico-muted-color);"}
      "No documents have been generated for this contract yet."])])

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
      [:div {"data-signals:show-payment-modal" "false"
             "data-signals:show-retract-payment-modal" "false"
             "data-signals:show-retract-contract-modal" "false"
             "data-signals:show-settlement-modal" "false"
             "data-signals:show-origination-modal" "false"
             "data-signals:show-retract-origination-modal" "false"
             "data-signals:show-clearance-letter-modal" "false"
             "data-signals:show-statement-modal" "false"
             "data-signals:retract-payment-id" "''"
             "data-signals:retract-ref" "''"
             "data-signals:retract-amount" "''"
             "data-signals:override-toggle" "false"}
       (flash-message flash)
       [:div {:style "margin-bottom: 1rem; display: flex; justify-content: space-between; align-items: center;"}
        [:a.secondary {:href "/contracts" :role "button"} "\u2190 Back to Contracts"]
        [:div.action-bar
         [:button
          {:type "button"
           "data-on:click" "$showOriginationModal = true"}
          "Originate"]
         [:button.btn-caution
          {:type "button"
           "data-on:click" "$showRetractOriginationModal = true"}
          "Retract Origination"]
         [:button
          {:type "button"
           "data-on:click" "$showSettlementModal = true"}
          "Calculate Settlement"]
         [:button
          {:type "button"
           "data-on:click" "$showPaymentModal = true"}
          "+ Record Payment"]
         [:button.btn-danger
          {:type "button"
           "data-on:click" "$showRetractContractModal = true"}
          "Delete Contract"]]]
       ;; Tabs navigation
       [:div.tabs {"data-signals:active-tab" "'overview'"
                   "data-signals:history-loaded" "false"}
        [:button {:data-class "{'active': $activeTab === 'overview'}"
                  "data-on:click" "$activeTab = 'overview'"} "Overview"]
        [:button {:data-class "{'active': $activeTab === 'schedule'}"
                  "data-on:click" "$activeTab = 'schedule'"} "Schedule"]
        [:button {:data-class "{'active': $activeTab === 'history'}"
                  "data-on:click" (str "$activeTab = 'history'; if(!$historyLoaded) { $historyLoaded = true; @get('" "/contracts/" contract-id "/history-tab') }")} "History"]
        [:button {:data-class "{'active': $activeTab === 'documents'}"
                  "data-on:click" "$activeTab = 'documents'"} "Documents"]]
       ;; Tab content panels
       [:div#tab-overview.tab-content {"data-class" "{'active': $activeTab === 'overview'}"}
        (contract-summary state)
        (fees-table (:fees state))
        (parties-section contract-id (:contract state))]
       [:div#tab-schedule.tab-content {"data-class" "{'active': $activeTab === 'schedule'}"}
        (installments-table (:installments state))]
       [:div#tab-history.tab-content {"data-class" "{'active': $activeTab === 'history'}"}
        [:div#history-tab-content
         [:p [:small "Click the History tab to load transaction history..."]]]]
       [:div#tab-documents.tab-content {"data-class" "{'active': $activeTab === 'documents'}"}
        (document-list-section contract-id (:documents state))]
       ;; Modals (outside tabs)
       (payment-form contract-id)
       (retract-payment-modal contract-id)
       (retract-contract-modal contract-id (get-in state [:contract :external-id]))
       (settlement-form contract-id)
       (origination-form contract-id state)
       (retract-origination-modal contract-id)
       (generate-clearance-letter-modal contract-id)
       (generate-statement-modal contract-id)]))))

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
    [:div.flash-danger
     [:h3 {:style "font-size: 1rem;"} "Validation Errors"]
     [:ul
      (for [err errors]
        [:li {:key (:message err)} (:message err)])]]))

(defn fee-row-template
  "Render a single fee input row.

   Args:
   - idx: Row index for unique IDs

   Returns: Hiccup div"
  [idx]
  [:div.fee-row {:style "display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 0.5rem; margin-bottom: 0.5rem; align-items: end;"
                 :data-fee-row "true"}
   [:div {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Fee Type"])
    [:select {:name "fee-type[]"}
     [:option {:value "management"} "Management"]
     [:option {:value "insurance"} "Insurance"]
     [:option {:value "processing"} "Processing"]
     [:option {:value "documentation"} "Documentation"]]]
   [:div {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Amount (SAR)"])
    [:input {:type "number" :name "fee-amount[]" :step "0.01" :min "0.01" :required true}]]
   [:div {:style "margin-bottom: 0;"}
    (when (= idx 0) [:label "Days After Disb."])
    [:input {:type "number" :name "fee-days-after-disbursement[]" :min "0" :value "0" :required true
             :placeholder "0 = at disbursement"}]]
   [:button.secondary {:type "button"
                       :onclick "this.closest('[data-fee-row]').remove()"}
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
       [:a.secondary {:role "button" :href "/contracts"} "\u2190 Back to Contracts"]]

      ;; Tab switcher
      [:div {:role "group"}
       [:a {:role "button" :href "/contracts/new?type=new"
            :class (when existing? "secondary")}
        "New Loan"]
       [:a {:role "button" :href "/contracts/new?type=existing"
            :class (when-not existing? "secondary")}
        "Existing Loan"]]

      ;; Validation errors
      (boarding-errors-card errors)

      ;; Form
      [:form {:method "post"
              :action action
              :enctype "multipart/form-data"}

       ;; ── Contract Terms ──
       [:article
        [:h2 "Contract Terms"]
        [:div.grid
         [:div.form-group
          [:label {:for "external-id"} "External ID *"]
          [:input {:type "text" :id "external-id" :name "external-id" :required true
                   :value (get values "external-id")
                   :placeholder "e.g., LOAN-2024-001"}]]
         [:div.form-group {"data-signals:borrower-party-id" (str "'" (or (get values "borrower-party-id") "") "'")
                           "data-signals:borrower-search" (str "'" (or (get values "borrower-name") "") "'")
                           "data-signals:show-borrower-results" "false"}
          [:label {:for "borrower-search"} "Borrower (Company) *"]
          [:input {:type "hidden" :id "borrower-party-id" :name "borrower-party-id"
                   "data-bind:borrower-party-id" true}]
          [:input {:type "text" :id "borrower-search" :autocomplete "off"
                   :placeholder "Search by company name or CR number..."
                   "data-bind:borrower-search" true
                   "data-on:keyup__debounce.300ms" "$showBorrowerResults = true; @get('/api/parties/search?type=company&target=borrower-results&context=borrower')"
                   :name "borrower-search"}]
          [:div#borrower-results
           {:style "border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); max-height: 200px; overflow-y: auto;"
            "data-show" "$showBorrowerResults"}]
          [:small
           "Search and select a company party. "
           [:a {:href "/parties/new" :target "_blank"} "Create new party"]]]
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
          [:small
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
       [:article
        [:details
         [:summary
          "Commodity Details (Optional)"]
         [:div.grid {:style "margin-top: 1rem;"}
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
       [:article
        [:details
         [:summary
          "Banking Details (Optional)"]
         [:div.grid {:style "margin-top: 1rem;"}
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
       [:article
        [:h2 "Fees"]
        [:div#fee-rows
         (fee-row-template 0)]
        [:button.secondary
         {:type "button"
          :onclick "addFeeRow()"
          :style "margin-top: 0.5rem; font-size: 0.875rem;"}
         "+ Add Fee"]]

       ;; ── Schedule CSV Upload ──
       [:article
        [:h2 "Installment Schedule *"]
        [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
         "Upload a CSV file with the installment schedule. "
         "Format: Seq, Due Date, Principal Due, Profit Due, Remaining Principal (optional)"]
        [:div.form-group
         [:input {:type "file" :id "schedule-csv" :name "schedule-csv" :accept ".csv" :required true}]]
        [:details {:style "margin-top: 0.5rem;"}
         [:summary "Example CSV format"]
         [:pre
          "Seq,Due Date,Principal Due,Profit Due,Remaining Principal\n1,2024-01-31,83333.33,12500.00,1000000.00\n2,2024-02-28,83333.33,12500.00,916666.67\n3,2024-03-31,83333.34,12500.00,833333.34"]]]

       ;; ── Existing Loan: Payment History CSV ──
       (when existing?
         [:article
          [:h2 "Payment History"]
          [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
           "Upload a CSV with historical payments to replay. "
           "Format: Date, External ID, Payment Summary, Amount, Paid By, Source, Reference"]
          [:div.form-group
           [:input {:type "file" :id "payment-csv" :name "payment-csv" :accept ".csv"}]]
          [:details {:style "margin-top: 0.5rem;"}
           [:summary "Example CSV format"]
           [:pre
            "Date,External ID,Payment Summary,Amount,Paid By,Source,Reference\n2024-01-15,LOAN-001,Transfer,50000,Customer,,FT-ANB-12345\n2024-02-15,LOAN-001,Transfer,60000,Customer,,FT-ANB-12346"]]])

       ;; ── Existing Loan: Historical Disbursement ──
       (when existing?
         [:article
          [:h2 "Historical Disbursement"]
          [:p.text-muted {:style "margin-bottom: 1rem; font-size: 0.875rem;"}
           "If the loan was already disbursed, enter the disbursement details."]
          [:div.grid
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
        [:button {:type "submit"}
         (if existing? "Board Existing Contract" "Board New Contract")]
        [:a.secondary {:role "button" :href "/contracts"}
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
          row.innerHTML = '<div style=\"margin-bottom: 0;\"><select name=\"fee-type[]\"><option value=\"management\">Management</option><option value=\"insurance\">Insurance</option><option value=\"processing\">Processing</option><option value=\"documentation\">Documentation</option></select></div><div style=\"margin-bottom: 0;\"><input type=\"number\" name=\"fee-amount[]\" step=\"0.01\" min=\"0.01\" required></div><div style=\"margin-bottom: 0;\"><input type=\"number\" name=\"fee-days-after-disbursement[]\" min=\"0\" value=\"0\" required placeholder=\"0 = at disbursement\"></div><button type=\"button\" class=\"secondary\" onclick=\"this.closest(\\x27[data-fee-row]\\x27).remove()\">X</button>';
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
   [:article
    [:div.page-header
     [:h2.mb-0 "All Parties"]
     [:a {:role "button" :href "/parties/new"} "+ Create Party"]]
    (if (empty? parties)
      [:div.empty-state
       [:p "No parties found."]
       [:p.text-muted "Create a party to get started."]]
      [:table.striped
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
           [:td [:a {:role "button" :href (str "/parties/" (:party/id p))}
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
       [:a.secondary {:role "button" :href "/parties"} "\u2190 Back to Parties"]]

      [:div#flash-area]

      (when (seq errors)
        [:div.flash-danger
         [:h3 {:style "font-size: 1rem;"} "Validation Errors"]
         [:ul
          (for [err errors]
            [:li (:message err)])]])

      [:form (if editing?
               {"data-on:submit" (str "@post('/parties/" (:party/id party) "/update', {contentType: 'form'})")}
               {:method "post" :action "/parties"})
       [:article
        [:h2 (if editing? "Edit Party" "New Party")]
        [:div.grid
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
          [:small "Required for companies"]]
         [:div.form-group {:id "national-id-group"}
          [:label {:for "national-id"} "National ID"]
          [:input {:type "text" :id "national-id" :name "national-id"
                   :value (or (get values "national-id")
                              (:party/national-id party))
                   :placeholder "National ID / Iqama number"}]
          [:small "Required for persons"]]
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
        [:button {:type "submit"}
         (if editing? "Update Party" "Create Party")]
        [:a.secondary {:role "button" :href "/parties"}
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

(defn ownership-section
  "Render ownership structure section for a company party.

   Args:
   - party-id: UUID of the company
   - ownerships: Sequence of ownership records (with :ownership/owner pull)

   Returns: Hiccup div"
  [party-id ownerships]
  [:article#ownership-section
   [:h2 "Ownership Structure"]
   (if (empty? ownerships)
     [:p.text-muted "No ownership records."]
     [:table
      [:thead
       [:tr
        [:th "Owner"]
        [:th "Type"]
        [:th.text-right "Percentage"]
        [:th {:style "width: 5rem;"} ""]]]
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
          [:td
           [:button.btn-danger
            {:type "button"
             "data-on:click" (str "@post('/parties/" party-id
                                  "/ownership/" (:ownership/id o) "/remove')")}
            "Remove"]]])]])
   [:h3 {:style "margin-top: 1.5rem;"} "Add Owner"]
   [:div {"data-signals:owner-party-id" "''"
          "data-signals:owner-search" "''"
          "data-signals:show-owner-results" "false"}
    [:form {"data-on:submit" (str "@post('/parties/" party-id "/ownership', {contentType: 'form'})")}
     [:div {:style "display: flex; gap: 0.75rem; align-items: flex-end;"}
      [:div.form-group {:style "flex: 2; position: relative;"}
       [:label {:for "owner-search"} "Owner Party"]
       [:input {:type "hidden" :name "owner-party-id" "data-bind:owner-party-id" true}]
       [:input {:type "text" :autocomplete "off"
                :placeholder "Search by name, CR, or National ID..."
                "data-bind:owner-search" true
                "data-on:keyup__debounce.300ms" "$showOwnerResults = true; @get('/api/parties/search?target=owner-search-results&context=owner')"}]
       [:div#owner-search-results
        {:style "position: absolute; z-index: 10; background: var(--pico-background-color); border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); width: 100%; max-height: 200px; overflow-y: auto;"
         "data-show" "$showOwnerResults"}]]
      [:div.form-group {:style "flex: 1;"}
       [:label {:for "percentage"} "Percentage"]
       [:input {:type "number" :name "percentage"
                :min "0.01" :max "100" :step "0.01"
                :placeholder "e.g. 60"
                :required true}]]
      [:div
       [:button {:type "submit"} "Add Owner"]]]]]])

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
       [:a.secondary {:role "button" :href "/parties"} "\u2190 Back to Parties"]
       [:a {:role "button" :href (str "/parties/" party-id "/edit")} "Edit"]]

      [:div#flash-area]

      ;; Party info card
      [:div
       [:h2 (:party/legal-name party)]
       [:div.grid
        [:article
         [:small "Type"] [:br]
         [:strong (if company? "Company" "Person")]]
        [:article
         [:small (if company? "CR Number" "National ID")] [:br]
         [:strong (or (if company? (:party/cr-number party) (:party/national-id party)) "-")]]
        (when (:party/email party)
          [:article
           [:small "Email"] [:br]
           [:strong (:party/email party)]])
        (when (:party/phone party)
          [:article
           [:small "Phone"] [:br]
           [:strong (:party/phone party)]])]
       (when (:party/address party)
         [:article
          [:small "Address"] [:br]
          [:strong (:party/address party)]])]

      ;; Contracts section
      [:article
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
        (ownership-section party-id ownerships))

      ;; Ownership section (what this party owns)
      (when (seq owns)
        [:article
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
   [:article
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
   [:article
    [:h2 "500 - Internal Server Error"]
    [:p "An error occurred while processing your request."]
    [:pre (str error)]
    [:p [:a {:href "/"} "Go home"]]]))
