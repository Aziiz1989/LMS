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
            [taoensso.timbre :as log]))

(defn page-html [& body]
  (str (h/html
        (h/raw (doctype :html5))
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title "LMS - Test"]
          [:script {:src "https://unpkg.com/htmx.org@2.0.4"}]
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
          [:h2 "HTMX Test"]
          [:button {:hx-get "/api/time"
                    :hx-target "#time-display"
                    :hx-swap "innerHTML"}
           "Get Server Time"]
          [:p {:id "time-display"} "Click the button to fetch time from server"]
          [:hr]
          [:h2 "Datomic Local Test"]
          [:button {:hx-get "/api/db-test"
                    :hx-target "#db-display"
                    :hx-swap "innerHTML"}
           "Test Datomic Connection"]
          [:p {:id "db-display"} "Click to test Datomic Local connection"])})

(defn time-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str "<strong>Server time:</strong> " (java.util.Date.))})

(defn db-test-handler [_request]
  (try
    (let [client (d/client {:server-type :datomic-local
                            :storage-dir :mem
                            :system "lms-test"})
          _ (d/create-database client {:db-name "test"})
          conn (d/connect client {:db-name "test"})
          db (d/db conn)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str "<strong style=\"color:green\">✓ Datomic Local connected!</strong><br>"
                  "Database: " (pr-str db))})
    (catch Exception e
      (log/error e "Datomic connection failed")
      {:status 500
       :headers {"Content-Type" "text/html"}
       :body (str "<strong style=\"color:red\">✗ Datomic error:</strong> " (.getMessage e))})))

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
      ["/preview-payment" {:post handlers/preview-payment-handler}]
      ["/record-payment" {:post handlers/record-payment-handler}]
      ["/retract-payment" {:post handlers/retract-payment-handler}]
      ["/retract-contract" {:post handlers/retract-contract-handler}]
      ["/calculate-settlement" {:post handlers/calculate-settlement-handler}]
      ["/originate" {:post handlers/originate-handler}]
      ["/retract-origination" {:post handlers/retract-origination-handler}]]]
    ;; Legacy test routes (keep for debugging)
    ["/test" {:get home-page}]
    ["/api"
     ["/time" {:get time-handler}]
     ["/db-test" {:get db-test-handler}]]]
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
  (def server (start-server))

  ;; Stop server
  (.stop server) 
  
  (defn reload! []
    (require '[lms.core] :reload-all)
    :reloaded)
  (reload!)


  
)
