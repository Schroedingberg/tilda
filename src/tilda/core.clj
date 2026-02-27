(ns tilda.core
  "Application entry point and lifecycle management.
   
   ## Overview
   
   This namespace provides the main entry point for Tilda. It manages:
   - XTDB node lifecycle (database)
   - http-kit server lifecycle (HTTP/SSE)
   - mulog publisher lifecycle (logging)
   
   ## Usage
   
   From REPL:
   ```clojure
   (require '[tilda.core :as app])
   (app/start!)           ;; Start with defaults (port 8080, in-memory DB)
   (app/start! :port 3000) ;; Start on custom port
   (app/stop!)             ;; Stop everything
   (app/node)              ;; Access the XTDB node
   ```
   
   From command line:
   ```bash
   clj -M -m tilda.core
   ```
   
   ## State Management
   
   State is held in a single atom containing:
   - :node - XTDB node instance
   - :server - http-kit stop function
   - :publisher - mulog publisher stop function"
  (:require
   [com.brunobonacci.mulog :as mu]
   [org.httpkit.server :as http-kit]
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

  (let [publisher (mu/start-publisher! {:type :console})
        node      (if xtdb
                    (xtn/start-node xtdb)
                    (xtn/start-node))
        handler   (routes/handler node)
        server    (http-kit/run-server handler {:port port})]
    (reset! state {:node node :server server :publisher publisher})
    (mu/log ::started :port port)
    (println (str "Tilda running on http://localhost:" port))
    @state))

(defn stop! []
  (when-let [{:keys [node server publisher]} @state]
    (server)  ; http-kit returns a stop fn
    (.close node)
    (publisher) ; stop the publisher
    (reset! state nil)
    (println "Stopped")))

(defn node []
  (:node @state))

(defn -main [& _args]
  (start!)
  ;; Keep main thread alive
  @(promise))
