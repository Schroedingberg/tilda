(ns tilda.routes
  "HTTP routes - Ring + Reitit"
  (:require
   [cheshire.core :as json]
   [cheshire.generate :as json-gen]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as mu]
   [reitit.ring :as ring]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.util.response :as resp]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.ring :as d*ring]
   [tilda.booking :as b]
   [tilda.views :as views]
   [tilda.views.calendar :as cal])
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
    (let [id (parse-uuid (get-in req [:path-params :id]))]
      (if-let [booking (b/get-booking node id)]
        (json-response booking)
        (resp/not-found "Booking not found")))))

(defn cancel-booking [node]
  (fn [req]
    (let [id (parse-uuid (get-in req [:path-params :id]))]
      (b/cancel-booking! node id)
      (resp/status 204))))

(defn booking-history [node]
  (fn [req]
    (let [id (parse-uuid (get-in req [:path-params :id]))]
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

(defn calendar-page [node]
  (fn [req]
    (let [month (parse-month (get-in req [:params :month]))
          tenant (get-in req [:params :tenant] "demo-user")
          bookings (safe-query #(b/all-bookings node))
          requests (safe-query #(b/pending-requests node))]
      (html-response (cal/calendar-page month tenant bookings requests)))))

(defn month-fragment [node]
  (fn [req]
    (let [month (parse-month (get-in req [:path-params :month]))
          bookings (safe-query #(b/all-bookings node))
          requests (safe-query #(b/pending-requests node))
          html (views/render (cal/month-fragment month bookings requests))]
      (d*ring/->sse-response req
                             {d*ring/on-open
                              (fn [sse]
                                (d*/patch-elements! sse html)
                                (d*/close-sse! sse))}))))

(defn calendar-events [_node]
  (fn [_req]
    ;; SSE endpoint - for now just keep connection open
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"}
     :body "event: connected\ndata: {}\n\n"}))

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
   Body: {tenant-name, start-date, end-date, priority?}"
  [node]
  (fn [req]
    (let [body (parse-body req)]
      (if-let [{:keys [tenant-name start-date end-date priority]} body]
        (let [start (parse-instant start-date)
              end   (parse-instant end-date)]
          (cond
            (not tenant-name)
            (error-response 400 "Missing tenant-name")

            (or (nil? start) (nil? end))
            (error-response 400 "Invalid date format. Use ISO-8601 (e.g., 2026-03-01T00:00:00Z)")

            :else
            (let [request-id (b/create-request! node {:tenant-name tenant-name
                                                      :start-date  start
                                                      :end-date    end
                                                      :priority    (or priority 0)})]
              (-> (json-response {:request-id (str request-id)})
                  (resp/status 201)))))
        (error-response 400 "Invalid JSON body")))))

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
                  (json-response {:booking-id (str (:booking-id result))
                                  :winner     (str (:winner result))
                                  :rejected   (mapv str (:rejected result))}))))))
        (error-response 400 "Invalid JSON body")))))

;; =============================================================================
;; Router
;; =============================================================================

(defn router [node]
  (ring/router
   [["/" {:get (home node)}]

    ["/calendar"
     ["" {:get (calendar-page node)}]
     ["/month/:month" {:get (month-fragment node)}]
     ["/events" {:get (calendar-events node)}]]

    ["/requests"
     ["" {:get (list-requests node)
          :post (create-request node)}]
     ["/resolve" {:post (resolve-requests node)}]]

    ["/bookings"
     ["" {:get (list-bookings node)}]
     ["/:id" {:get (get-booking node)
              :delete (cancel-booking node)}]
     ["/:id/history" {:get (booking-history node)}]]]))

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

(defn handler [node]
  (-> (ring/ring-handler
       (router node)
       (ring/routes
        serve-static
        (ring/create-default-handler)))
      wrap-keyword-params
      wrap-params
      wrap-log))
