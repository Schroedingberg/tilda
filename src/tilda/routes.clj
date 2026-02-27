(ns tilda.routes
  "HTTP routes for the Tilda booking system.
   
   ## Overview
   
   This namespace defines all HTTP endpoints using Reitit. It delegates
   to domain logic in tilda.booking and renders views from tilda.views.layout.
   
   ## Endpoints
   
   | Method | Path                | Description                    |
   |--------|---------------------|--------------------------------|
   | GET    | /                   | Home page with bookings list   |
   | GET    | /calendar           | Interactive calendar view      |
   | GET    | /calendar/month/:m  | Lazy-load month fragment       |
   | GET    | /calendar/events    | SSE endpoint for live updates  |
   | GET    | /requests           | List pending requests (JSON)   |
   | POST   | /requests           | Create booking request         |
   | DELETE | /requests/:id       | Cancel a pending request       |
   | POST   | /requests/resolve   | Resolve competing requests     |
   | GET    | /bookings           | List all bookings (JSON)       |
   | GET    | /bookings/:id       | Get single booking             |
   | DELETE | /bookings/:id       | Cancel a booking               |
   | GET    | /bookings/:id/history | Booking version history      |
   | GET    | /admin              | Admin conflict resolution UI |
   | GET    | /admin/conflicts    | Conflicts fragment (SSE)     |
   | POST   | /admin/resolve      | Resolve with winner/decider  |
   
   ## Live Updates
   
   The calendar supports real-time updates via SSE. When bookings or
   requests change, the affected day cells are broadcast to all connected
   clients using Datastar's patch-elements event format."
  (:require
   [cheshire.core :as json]
   [cheshire.generate :as json-gen]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as mu]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as ring]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.util.response :as resp]
   [tilda.auth :as auth]
   [tilda.booking :as b]
   [tilda.sse :as sse]
   [tilda.views.layout :as views]
   [tilda.views.calendar :as cal]
   [tilda.views.admin :as admin])
  (:import [java.time Instant ZonedDateTime LocalDate LocalDateTime YearMonth]
           [java.time.format DateTimeParseException]))

;; Register JSON encoders for Java time types
(json-gen/add-encoder Instant (fn [v jg] (.writeString jg (str v))))
(json-gen/add-encoder ZonedDateTime (fn [v jg] (.writeString jg (str (.toInstant v)))))
(json-gen/add-encoder LocalDate (fn [v jg] (.writeString jg (str v))))
(json-gen/add-encoder LocalDateTime (fn [v jg] (.writeString jg (str v))))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn json-response [data]
  (-> (resp/response (json/generate-string data))
      (resp/content-type "application/json")))

(defn html-response [hiccup]
  (-> (resp/response (views/render hiccup))
      (resp/content-type "text/html")))

(defn error-response
  "Return a JSON error response with given status code."
  [status message]
  (-> (json-response {:error message})
      (resp/status status)))

(defn parse-body
  "Parse JSON body from request. Returns nil on failure."
  [req]
  (try
    (some-> req :body slurp (json/parse-string true))
    (catch Exception _ nil)))

(defn parse-instant
  "Parse ISO-8601 date string to java.time.Instant. Returns nil on failure."
  [s]
  (try
    (Instant/parse s)
    (catch DateTimeParseException _ nil)))

(defn safe-query
  "Returns empty seq if query fails (e.g., table doesn't exist yet)."
  [f]
  (try (f) (catch Exception _ [])))

(defn path-id
  "Extract and parse UUID from path params :id."
  [req]
  (some-> req (get-in [:path-params :id]) parse-uuid))

;; =============================================================================
;; Live Update Broadcasting
;; =============================================================================

(defn broadcast-calendar-update!
  "Broadcast updated day cells to all connected SSE clients.
   
   Called after any mutation that affects the calendar (create request,
   resolve booking, cancel booking). Queries current state and broadcasts
   the affected date range."
  [node start end]
  (let [bookings (safe-query #(b/all-bookings node))
        requests (safe-query #(b/pending-requests node))
        day-cells (cal/day-cells-for-range start end bookings requests)
        html (apply str (map views/render day-cells))]
    (sse/broadcast! html)))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn home [node]
  (fn [_req]
    (html-response (views/home (safe-query #(b/all-bookings node))
                               (safe-query #(b/pending-requests node))))))

(defn list-bookings [node]
  (fn [_req]
    (json-response (safe-query #(b/all-bookings node)))))

(defn list-requests [node]
  (fn [_req]
    (json-response (safe-query #(b/pending-requests node)))))

(defn get-booking [node]
  (fn [req]
    (let [id (path-id req)]
      (if-let [booking (b/get-booking node id)]
        (json-response booking)
        (resp/not-found "Booking not found")))))

(defn cancel-booking [node]
  (fn [req]
    (let [id (path-id req)]
      (if-let [{:keys [start-date end-date]} (b/get-booking node id)]
        (do
          (b/cancel-booking! node id)
          (broadcast-calendar-update! node start-date end-date)
          (resp/status 204))
        (resp/not-found "Booking not found")))))

(defn booking-history [node]
  (fn [req]
    (let [id (path-id req)]
      (json-response (safe-query #(b/booking-history node id))))))

;; -----------------------------------------------------------------------------
;; Calendar Handlers
;; -----------------------------------------------------------------------------

(defn parse-month
  "Parse YYYY-MM to YearMonth, defaulting to current month."
  [s]
  (try
    (if s (YearMonth/parse s) (YearMonth/now))
    (catch Exception _ (YearMonth/now))))

(defn calendar-page [node config]
  (fn [req]
    (let [month (parse-month (get-in req [:params :month]))
          tenant (or (get-in req [:user :name])
                     (get-in req [:params :tenant])
                     "Guest")
          resource-name (get-in config [:resource :name])
          bookings (safe-query #(b/all-bookings node))
          requests (safe-query #(b/pending-requests node))]
      (html-response (cal/calendar-page month tenant bookings requests resource-name)))))

(defn month-fragment [node]
  (fn [req]
    (let [month (parse-month (get-in req [:path-params :month]))
          bookings (safe-query #(b/all-bookings node))
          requests (safe-query #(b/pending-requests node))
          html (views/render (cal/month-fragment month bookings requests))
          message (sse/format-datastar-fragment html)]
      (http-kit/as-channel req
                           {:on-open (fn [channel]
                                       ;; Send initial headers + fragment, then close
                                       (http-kit/send! channel
                                                       {:headers {"Content-Type" "text/event-stream"
                                                                  "Cache-Control" "no-cache"}
                                                        :body message}
                                                       true))}))))  ; true = close after send

(defn calendar-events [_node]
  (sse/sse-handler
   {:on-connect (fn [channel]
                  ;; Send SSE headers and connection confirmation
                  (http-kit/send! channel
                                  {:headers {"Content-Type" "text/event-stream"
                                             "Cache-Control" "no-cache"}}
                                  false)
                  ;; Self-removing script confirms connection in browser console
                  (sse/send! channel
                             (str "event: datastar-patch-elements\n"
                                  "data: mode append\n"
                                  "data: selector body\n"
                                  "data: elements <script data-effect=\"el.remove()\">console.log('SSE connected')</script>\n\n")))}))

;; -----------------------------------------------------------------------------
;; POST Handlers
;; -----------------------------------------------------------------------------

(def deciders
  "Map of decider names to functions."
  {"fcfs"     b/decide-first-come-first-serve
   "priority" b/decide-by-priority
   "lottery"  b/decide-random-lottery})

(defn create-request
  "POST /requests - Create a booking request.
   Body: {start-date, end-date, priority?, tenant-name?}
   tenant-name from body takes precedence; falls back to authenticated user."
  [node]
  (fn [req]
    (let [body (parse-body req)
          ;; Body tenant-name takes precedence (for API/testing), else use auth
          tenant-name (or (:tenant-name body)
                          (get-in req [:user :name]))]
      (if-let [{:keys [start-date end-date priority]} body]
        (let [start (parse-instant start-date)
              end   (parse-instant end-date)]
          (cond
            (not tenant-name)
            (error-response 400 "Missing tenant-name (not authenticated)")

            (or (nil? start) (nil? end))
            (error-response 400 "Invalid date format. Use ISO-8601 (e.g., 2026-03-01T00:00:00Z)")

            :else
            (let [request-id (b/create-request! node {:tenant-name tenant-name
                                                      :start-date  start
                                                      :end-date    end
                                                      :priority    (or priority 0)})]
              (broadcast-calendar-update! node start end)
              (-> (json-response {:request-id (str request-id)})
                  (resp/status 201)))))
        (error-response 400 "Invalid JSON body")))))

(defn cancel-request
  "DELETE /requests/:id - Cancel a pending request."
  [node]
  (fn [req]
    (let [id (path-id req)]
      (if-let [request (and id (b/get-request node id))]
        (do
          (b/cancel-request! node id)
          (broadcast-calendar-update! node (:start-date request) (:end-date request))
          (resp/status (resp/response "") 204))
        (error-response 404 "Request not found")))))

(defn resolve-requests
  "POST /requests/resolve - Resolve competing requests for a time slot.
   Body: {start-date, end-date, decider: fcfs|priority|lottery}"
  [node]
  (fn [req]
    (let [body (parse-body req)]
      (if-let [{:keys [start-date end-date decider]} body]
        (let [start      (parse-instant start-date)
              end        (parse-instant end-date)
              decider-fn (get deciders decider)]
          (cond
            (or (nil? start) (nil? end))
            (error-response 400 "Invalid date format. Use ISO-8601 (e.g., 2026-03-01T00:00:00Z)")

            (nil? decider-fn)
            (error-response 400 (str "Invalid decider. Use: " (keys deciders)))

            :else
            (let [conflicts (safe-query #(b/find-conflicting-requests node start end))]
              (if (empty? conflicts)
                (error-response 404 "No pending requests found for this slot")
                (let [result (b/resolve-slot! node conflicts decider-fn)]
                  (broadcast-calendar-update! node start end)
                  (json-response {:booking-id (str (:booking-id result))
                                  :winner     (str (:winner result))
                                  :rejected   (mapv str (:rejected result))}))))))
        (error-response 400 "Invalid JSON body")))))

;; =============================================================================
;; Admin Handlers
;; =============================================================================

(defn admin-page
  "GET /admin - Admin page showing conflicts that need resolution."
  [node]
  (fn [_req]
    (let [requests (safe-query #(b/pending-requests node))]
      (html-response (admin/admin-page requests)))))

(defn admin-conflicts-fragment
  "GET /admin/conflicts - SSE fragment for conflicts list."
  [node]
  (fn [_req]
    (let [requests (safe-query #(b/pending-requests node))]
      (html-response (admin/conflicts-fragment requests)))))

(defn admin-resolve
  "POST /admin/resolve - Resolve with specific winner or decider.
   Body: {winner: uuid} OR {decider: fcfs|lottery, start: ..., end: ...}"
  [node]
  (fn [req]
    (let [body (parse-body req)]
      (cond
        ;; Pick specific winner
        (:winner body)
        (if-let [winner-id (parse-uuid (:winner body))]
          (let [start (parse-instant (:start body))
                end (parse-instant (:end body))
                conflicts (safe-query #(b/find-conflicting-requests node start end))
                winner (first (filter #(= (:xt/id %) winner-id) conflicts))]
            (if winner
              (let [result (b/resolve-slot! node conflicts (constantly winner))]
                (broadcast-calendar-update! node start end)
                (json-response {:success true
                                :booking-id (str (:booking-id result))
                                :winner (str winner-id)}))
              (error-response 404 "Winner not found in conflicts")))
          (error-response 400 "Invalid winner UUID"))
        
        ;; Use decider
        (:decider body)
        (let [start (parse-instant (:start body))
              end (parse-instant (:end body))
              decider-fn (get deciders (:decider body))]
          (if decider-fn
            (let [conflicts (safe-query #(b/find-conflicting-requests node start end))]
              (if (empty? conflicts)
                (error-response 404 "No conflicts found")
                (let [result (b/resolve-slot! node conflicts decider-fn)]
                  (broadcast-calendar-update! node start end)
                  (json-response {:success true
                                  :booking-id (str (:booking-id result))
                                  :winner (str (:winner result))}))))
            (error-response 400 (str "Invalid decider. Use: " (keys deciders)))))
        
        :else
        (error-response 400 "Provide 'winner' or 'decider' in body")))))

;; =============================================================================
;; Router
;; =============================================================================

(defn router [node config]
  (ring/router
   [["/" {:get (home node)}]

    ["/calendar"
     ["" {:get (calendar-page node config)}]
     ["/month/:month" {:get (month-fragment node)}]
     ["/events" {:get (calendar-events node)}]]

    ["/requests"
     ["" {:get (list-requests node)
          :post (create-request node)}]
     ["/resolve" {:post (resolve-requests node)}]
     ["/:id" {:delete (cancel-request node)}]]

    ["/bookings"
     ["" {:get (list-bookings node)}]
     ["/:id" {:get (get-booking node)
              :delete (cancel-booking node)}]
     ["/:id/history" {:get (booking-history node)}]]

    ["/admin"
     ["" {:get (admin-page node)}]
     ["/conflicts" {:get (admin-conflicts-fragment node)}]
     ["/resolve" {:post (admin-resolve node)}]]]
   {:conflicts nil}))

(defn serve-static [req]
  (let [path (subs (:uri req) 1)]
    (when-let [resource (io/resource (str "public/" path))]
      {:status 200
       :headers {"Content-Type" (cond
                                  (.endsWith path ".js") "application/javascript"
                                  (.endsWith path ".css") "text/css"
                                  :else "application/octet-stream")}
       :body (slurp resource)})))

(defn wrap-log
  "Ring middleware that logs each request/response via mulog."
  [handler]
  (fn [req]
    (let [start  (System/currentTimeMillis)
          resp   (handler req)
          ms     (- (System/currentTimeMillis) start)]
      (mu/log ::http-request
              :method (:request-method req)
              :uri    (:uri req)
              :status (:status resp)
              :ms     ms)
      resp)))

(defn handler
  "Create Ring handler with XTDB node and optional config."
  ([node] (handler node {}))
  ([node config]
   (let [auth-config (merge {:strategy :dev :public #{"/favicon.ico" "/_/health"}}
                            (:auth config))]
     (-> (ring/ring-handler
          (router node config)
          (ring/routes
           serve-static
           (ring/create-default-handler)))
         (auth/wrap-auth auth-config)
         wrap-cookies
         wrap-keyword-params
         wrap-params
         wrap-log))))
