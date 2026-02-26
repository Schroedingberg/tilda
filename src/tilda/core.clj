(ns tilda.core
  "Entry point - starts XTDB node and HTTP server"
  (:require
   [ring.adapter.jetty :as jetty]
   [tilda.routes :as routes]
   [xtdb.node :as xtn])
  (:gen-class))

(defonce ^:private state (atom nil))

(defn start!
  "Start the server. Options:
   :port - HTTP port (default 8080)
   :xtdb - XTDB node config (default in-memory)"
  [& {:keys [port xtdb] :or {port 8080}}]
  (when @state
    (throw (ex-info "Already started" {})))

  (let [node (if xtdb
               (xtn/start-node xtdb)
               (xtn/start-node))
        handler (routes/handler node)
        server (jetty/run-jetty handler {:port port :join? false})]
    (reset! state {:node node :server server})
    (println (str "Tilda running on http://localhost:" port))
    @state))

(defn stop! []
  (when-let [{:keys [node server]} @state]
    (.stop server)
    (.close node)
    (reset! state nil)
    (println "Stopped")))

(defn node []
  (:node @state))

(defn -main [& _args]
  (start!)
  ;; Keep main thread alive
  @(promise))
