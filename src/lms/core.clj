(ns lms.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.params :refer [wrap-params]]
            [reitit.ring :as ring]
            [hiccup2.core :as h]
            [hiccup.page :refer [doctype]]
            [datomic.client.api :as d]
            [lms.db :as db]
            [lms.handlers :as handlers]
            [taoensso.timbre :as log]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.ring :as ring-sse]))

(defn page-html [& body]
  (str (h/html
        (h/raw (doctype :html5))
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title "LMS - Test"]
          [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.0-RC.7/bundles/datastar.js" :type "module"}]
          [:style "body { font-family: system-ui, sans-serif; max-width: 800px; margin: 2rem auto; padding: 0 1rem; }"]]
         [:body body]])))

(defn home-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (page-html
          [:h1 "LMS - All Systems Working!"]
          [:ul
           [:li "✓ Ring server running"]
           [:li "✓ Reitit routing working"]
           [:li "✓ Hiccup rendering HTML"]]
          [:hr]
          [:h2 "Datastar Test"]
          [:button {"data-on:click" "@get('/api/time')"}
           "Get Server Time"]
          [:p {:id "time-display"} "Click the button to fetch time from server"]
          [:hr]
          [:h2 "Datomic Local Test"]
          [:button {"data-on:click" "@get('/api/db-test')"}
           "Test Datomic Connection"]
          [:p {:id "db-display"} "Click to test Datomic Local connection"])})

(defn time-handler [request]
  (ring-sse/->sse-response request
                           {ring-sse/on-open
                            (fn [sse]
                              (d*/patch-elements! sse (str "<p id=\"time-display\"><strong>Server time:</strong> " (java.util.Date.) "</p>"))
                              (d*/close-sse! sse))}))

(defn db-test-handler [request]
  (try
    (let [client (d/client {:server-type :datomic-local
                            :storage-dir :mem
                            :system "lms-test"})
          _ (d/create-database client {:db-name "test"})
          conn (d/connect client {:db-name "test"})
          db (d/db conn)]
      (ring-sse/->sse-response request
                               {ring-sse/on-open
                                (fn [sse]
                                  (d*/patch-elements! sse
                                                      (str "<p id=\"db-display\"><strong style=\"color:green\">✓ Datomic Local connected!</strong><br>"
                                                           "Database: " (pr-str db) "</p>"))
                                  (d*/close-sse! sse))}))
    (catch Exception e
      (log/error e "Datomic connection failed")
      (ring-sse/->sse-response request
                               {ring-sse/on-open
                                (fn [sse]
                                  (d*/patch-elements! sse
                                                      (str "<p id=\"db-display\"><strong style=\"color:red\">✗ Datomic error:</strong> " (.getMessage e) "</p>"))
                                  (d*/close-sse! sse))}))))

;; ============================================================
;; Connection — created once, injected via middleware
;; ============================================================

(def conn
  "Database connection. Initialized on first use (delay).
   Moved here from handlers.clj — core is the composition root."
  (delay
    (let [c (db/get-connection)]
      (db/install-schema c)
      (log/info "Database schema installed")
      c)))

(defn wrap-conn
  "Middleware that injects :conn into every request."
  [handler]
  (fn [request]
    (handler (assoc request :conn @conn))))

(def router
  (ring/router
   [["/" {:get handlers/home-handler}]
    ["/contracts"
     ["" {:get handlers/list-contracts-handler}]
     ["/new" {:get handlers/new-contract-handler}]
     ["/board" {:post handlers/board-contract-handler}]
     ["/board-existing" {:post handlers/board-existing-contract-handler}]
     ["/:id"
      ["" {:get handlers/view-contract-handler}]
      ["/history-tab" {:get handlers/history-tab-handler}]
      ["/preview-payment" {:post handlers/preview-payment-handler}]
      ["/record-payment" {:post handlers/record-payment-handler}]
      ["/retract-payment" {:post handlers/retract-payment-handler}]
      ["/retract-contract" {:post handlers/retract-contract-handler}]
      ["/calculate-settlement" {:post handlers/calculate-settlement-handler}]
      ["/originate" {:post handlers/originate-handler}]
      ["/retract-origination" {:post handlers/retract-origination-handler}]
      ["/guarantors" {:post handlers/add-guarantor-handler}]
      ["/guarantors/:party-id/remove" {:post handlers/remove-guarantor-handler}]
      ["/signatories" {:post handlers/add-signatory-handler}]
      ["/signatories/:party-id/remove" {:post handlers/remove-signatory-handler}]
      ["/generate-clearance-letter" {:post handlers/generate-clearance-letter-handler}]
      ["/generate-statement" {:post handlers/generate-statement-handler}]
      ["/generate-contract-agreement" {:post handlers/generate-contract-agreement-handler}]
      ["/documents/:type/:doc-id/download" {:get handlers/download-document-pdf-handler}]]]
    ["/parties"
     ["" {:get handlers/list-parties-handler
          :post handlers/create-party-handler}]
     ["/new" {:get handlers/new-party-handler}]
     ["/:id"
      ["" {:get handlers/view-party-handler}]
      ["/update" {:post handlers/update-party-handler}]
      ["/ownership" {:post handlers/add-ownership-handler}]
      ["/ownership/:ownership-id/remove" {:post handlers/remove-ownership-handler}]]]
    ;; Legacy test routes (keep for debugging)
    ["/test" {:get home-page}]
    ["/api"
     ["/time" {:get time-handler}]
     ["/db-test" {:get db-test-handler}]
     ["/parties/search" {:get handlers/search-parties-handler}]
     ["/fee-row-template" {:get handlers/fee-row-template-handler}]]]
   {:conflicts nil}))

(def app
  (-> (ring/ring-handler
       router
       (ring/create-default-handler))
      wrap-params
      wrap-conn
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))))

(defn start-server [& {:keys [port] :or {port 3001}}]
  (log/info "Starting server on port" port)
  (jetty/run-jetty app {:port port :join? false}))

(defn -main [& _args]
  (start-server)
  (log/info "Server running at http://localhost:3000"))

(comment
  ;; Start server from REPL
  (def server
    (start-server))

;; Stop server
  (.stop server)

  (defn reload! []
    (require '[lms.core] :reload-all)
    :reloaded)
  (reload!))
