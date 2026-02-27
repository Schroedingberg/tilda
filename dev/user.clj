(ns user
  "REPL entry point with hot reload.
   
   Usage:
     clj -M:dev
     (start!)     ; starts server with hot reload
     (stop!)      ; stops server
     (restart!)   ; stop + start"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as mu]
   [org.httpkit.server :as http-kit]
   [ring.middleware.reload :refer [wrap-reload]]
   [tilda.routes :as routes]
   [xtdb.node :as xtn]))

(defonce ^:private state (atom nil))

(defn- load-config []
  (let [f (io/file "config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn- make-handler
  "Create a handler that re-resolves routes/handler on each request (hot reload)."
  [node config]
  (fn [req]
    ((routes/handler node config) req)))

(defn start!
  "Start server with hot reload - code changes apply on next request."
  [& {:keys [port] :or {port 8080}}]
  (when @state
    (println "Already running. Use (restart!) to restart.")
    nil)

  (when-not @state
    (let [config    (load-config)
          port      (or port (get-in config [:server :port]) 8080)
          publisher (mu/start-publisher! {:type :console})
          node      (xtn/start-node)
          ;; wrap-reload reloads changed namespaces on each request
          handler   (-> (make-handler node config)
                        (wrap-reload {:dirs ["src"]}))
          server    (http-kit/run-server handler {:port port})]
      (reset! state {:node node :server server :publisher publisher :config config})
      (mu/log :user/started :port port)
      (println (str "🚀 Dev server on http://localhost:" port " (hot reload enabled)")))))

(defn stop! []
  (when-let [{:keys [node server publisher]} @state]
    (server)  ; http-kit returns a stop fn
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
