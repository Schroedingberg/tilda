(ns tilda.core
  "Application entry point and lifecycle management.
   
   ## Overview
   
   This namespace provides the main entry point for Tilda. It manages:
   - XTDB node lifecycle (database)
   - http-kit server lifecycle (HTTP/SSE)
   - mulog publisher lifecycle (logging)
   
   ## Usage
   
   From REPL:
     (require '[tilda.core :as app])
     (app/start!)            ; Start with config.edn settings
     (app/start! :port 3000) ; Override port
     (app/stop!)             ; Stop everything
     (app/node)              ; Access the XTDB node
   
   From command line:
     clj -M -m tilda.core
   
   ## Configuration
   
   Settings are read from config.edn at startup:
   - :server {:port 8080}     ; HTTP port
   - :storage-dir \"data/xtdb\" ; XTDB persistence (nil = in-memory)
   
   ## State Management
   
   State is held in a single atom containing:
   - :node - XTDB node instance
   - :server - http-kit stop function
   - :publisher - mulog publisher stop function
   - :config - loaded configuration"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mu]
   [org.httpkit.server :as http-kit]
   [tilda.routes :as routes]
   [xtdb.node :as xtn])
  (:gen-class))

(defonce ^:private state (atom nil))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn- parse-users-env
  "Parse TILDA_USERS env var: 'token:Name,token2:Name2,...'"
  [s]
  (when s
    (->> (str/split s #",")
         (map #(str/split % #":" 2))
         (filter #(= 2 (count %)))
         (map (fn [[token name]] [token {:name name}]))
         (into {}))))

(defn load-config
  "Load configuration from config.edn, with env var overrides.
   TILDA_USERS env var: 'token:Name,token2:Name2' for magic-link auth."
  []
  (let [f (io/file "config.edn")
        base (if (.exists f)
               (edn/read-string (slurp f))
               {})
        users-env (System/getenv "TILDA_USERS")]
    (if-let [users (parse-users-env users-env)]
      (assoc base :auth {:strategy :magic-link :users users})
      base)))

(defn- xtdb-config
  "Build XTDB node config. If storage-dir is provided, uses persistent storage.
   Otherwise uses in-memory (default for development)."
  [storage-dir]
  (when storage-dir
    (let [dir (io/file storage-dir)]
      (.mkdirs dir)
      {:xtdb.log/local-directory-log {:path (io/file dir "log")}
       :xtdb.buffer-pool/buffer-pool {:cache-path (io/file dir "buffers")}
       :xtdb.object-store/file-system-object-store {:root-path (io/file dir "objects")}})))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Start the server. Reads config.edn, with optional overrides:
   :port - HTTP port (overrides config)
   :storage-dir - XTDB storage directory (overrides config, nil = in-memory)"
  [& {:keys [port storage-dir]}]
  (when @state
    (throw (ex-info "Already started" {})))

  (let [config      (load-config)
        port        (or port (get-in config [:server :port]) 8080)
        storage-dir (or storage-dir (:storage-dir config))
        xtdb-cfg    (xtdb-config storage-dir)
        publisher   (mu/start-publisher! {:type :console})
        node        (if xtdb-cfg
                      (xtn/start-node xtdb-cfg)
                      (xtn/start-node))
        handler     (routes/handler node config)
        server      (http-kit/run-server handler {:port port})]
    (reset! state {:node node :server server :publisher publisher :config config})
    (mu/log ::started :port port :storage (or storage-dir "in-memory"))
    (println (str "Tilda running on http://localhost:" port
                  (if storage-dir (str " (storage: " storage-dir ")") " (in-memory)")))
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

(defn config []
  (:config @state))

(defn -main [& _args]
  (start!)
  ;; Keep main thread alive
  @(promise))
