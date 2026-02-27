(ns user
  "REPL entry point with hot reload.
   
   Usage:
     clj -M:dev
     (start!)     ; starts server with hot reload
     (stop!)      ; stops server
     (restart!)   ; stop + start"
  (:require
   [com.brunobonacci.mulog :as mu]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.reload :refer [wrap-reload]]
   [tilda.routes :as routes]
   [xtdb.node :as xtn]))

(defonce ^:private state (atom nil))

(defn- make-handler
  "Create a handler that re-resolves routes/handler on each request (hot reload)."
  [node]
  (fn [req]
    ((routes/handler node) req)))

(defn start!
  "Start server with hot reload - code changes apply on next request."
  [& {:keys [port] :or {port 8080}}]
  (when @state
    (println "Already running. Use (restart!) to restart.")
    nil)

  (when-not @state
    (let [publisher (mu/start-publisher! {:type :console})
          node      (xtn/start-node)
          ;; wrap-reload reloads changed namespaces on each request
          handler   (-> (make-handler node)
                        (wrap-reload {:dirs ["src"]}))
          server    (jetty/run-jetty handler {:port port :join? false})]
      (reset! state {:node node :server server :publisher publisher})
      (mu/log :user/started :port port)
      (println (str "🚀 Dev server on http://localhost:" port " (hot reload enabled)")))))

(defn stop! []
  (when-let [{:keys [node server publisher]} @state]
    (.stop server)
    (.close node)
    (publisher)
    (reset! state nil)
    (println "Stopped")))

(defn restart! []
  (stop!)
  (start!))

(defn node []
  (:node @state))

(comment
  (start!)
  (stop!)
  (restart!))
