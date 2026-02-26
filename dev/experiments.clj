(ns dev.experiments
  "Post-MVP ideas and experiments. Not yet implemented in core.
   
   These are sketches for future features - use at your own risk!"
  (:require
   [tilda.booking :as booking]
   [xtdb.api :as xt]
   [xtdb.node :as xtn])
  (:import
   [java.time Instant Duration]))

;; =============================================================================
;; Roadmap (from booking.clj)
;; =============================================================================
;; - find-conflicting-requests: show users "others are also interested in this slot"

;; =============================================================================
;; Deferred Decision Pattern
;; =============================================================================
;; Instead of deciding immediately, collect requests and decide later.
;; Flow: request-slot! → (accumulate) → resolve-slot! using a decider function
;;
;; Use case: When you want fairness over speed. Collect requests for a week,
;; then resolve using priority/lottery/least-recent-winner.

(defn request-slot!
  "Request a time slot. Does NOT create a booking yet - just records intent.
   Returns the request-id."
  [node {:keys [tenant-name start-date end-date priority note]}]
  (let [request-id (random-uuid)]
    (xt/execute-tx node
                   [[:put-docs :slot-requests
                     {:xt/id request-id
                      :tenant-name tenant-name
                      :start-date start-date
                      :end-date end-date
                      :priority (or priority 0)
                      :note note
                      :status :pending
                      :requested-at (Instant/now)}]])
    request-id))

(defn pending-requests-for-slot
  "Get all pending requests that overlap with a time range."
  [node start-date end-date]
  (xt/q node
        '(-> (from :slot-requests [*])
             (where (and (<= start-date $end)
                         (>= end-date $start)
                         (= status :pending)))
             (order-by requested-at))
        {:args {:start start-date :end end-date}}))

(defn decide-least-recent-winner
  "Pick the tenant who hasn't won a booking in the longest time.
   Requires passing in booking history."
  [requests last-booking-by-tenant]
  (first (sort-by #(get last-booking-by-tenant (:tenant-name %) (Instant/parse "1970-01-01T00:00:00Z"))
                  requests)))

(defn resolve-slot!
  "Resolve competing requests using a decider function.
   Creates a booking for the winner, marks others as :rejected."
  [node start-date end-date decider-fn]
  (let [requests (pending-requests-for-slot node start-date end-date)]
    (when (seq requests)
      (let [winner (decider-fn requests)
            losers (remove #(= (:xt/id %) (:xt/id winner)) requests)]
        (let [booking-id (booking/create-booking! node
                                                  {:tenant-name (:tenant-name winner)
                                                   :start-date (:start-date winner)
                                                   :end-date (:end-date winner)})]
          ;; Mark winner's request as accepted
          (xt/execute-tx node
                         [[:put-docs :slot-requests
                           (assoc winner
                                  :status :accepted
                                  :booking-id booking-id)]])
          ;; Mark losers as rejected
          (doseq [loser losers]
            (xt/execute-tx node
                           [[:put-docs :slot-requests
                             (assoc loser
                                    :status :rejected
                                    :rejected-for booking-id)]]))
          {:winner winner
           :booking-id booking-id
           :rejected (count losers)})))))

(defn slots-needing-resolution
  "Find time slots with pending requests that are within N days.
   Use this to trigger resolution (e.g., daily job)."
  [node within-days]
  (let [cutoff (.plus (Instant/now) (Duration/ofDays within-days))]
    (xt/q node
          '(-> (from :slot-requests [start-date end-date])
               (where (and (= status :pending)
                           (<= start-date $cutoff)))
               (aggregate start-date end-date))
          {:args {:cutoff cutoff}})))

;; =============================================================================
;; Atomic Booking (XTDB Transaction Functions)
;; =============================================================================
;; Use this if you need guarantees (high contention, legal requirements, etc.)
;; This eliminates race conditions entirely by checking conflicts inside the tx.

(defn install-tx-fns!
  "Install transaction functions into XTDB. Call once at startup."
  [node]
  (xt/submit-tx node
                [[:put-docs :xt/tx-fns
                  {:xt/id :book-if-available
                   :xt/fn
                   '(fn [ctx {:keys [booking-id tenant-name start-date end-date]}]
                      (let [conflicts (xtdb.api/q ctx
                                                  '(-> (from :bookings [xt/id tenant-name start-date end-date status])
                                                       (where (and (<= start-date $end)
                                                                   (>= end-date $start)
                                                                   (not (= status :cancelled)))))
                                                  {:args {:start start-date :end end-date}})]
                        (if (seq conflicts)
                          false ; Abort - no changes
                          [[:put-docs :bookings
                            {:xt/id booking-id
                             :tenant-name tenant-name
                             :start-date start-date
                             :end-date end-date
                             :status :pending
                             :approvals #{}}]])))}]]))

(defn book-atomic!
  "Atomically book if slot is available. Returns the booking-id.
   Check tx result to see if it succeeded."
  [node {:keys [tenant-name start-date end-date]}]
  (let [booking-id (random-uuid)]
    {:booking-id booking-id
     :tx-result (xt/submit-tx node
                              [[:call :book-if-available
                                {:booking-id booking-id
                                 :tenant-name tenant-name
                                 :start-date start-date
                                 :end-date end-date}]])}))

(defn tx-succeeded?
  "Check if a transaction was committed (not aborted)."
  [node tx-result]
  (let [tx-id (:tx-id tx-result)]
    (when tx-id
      (xt/q node
            '(from :xt/txs [{:xt/id $id} committed])
            {:args {:id tx-id}}))))

;; =============================================================================
;; Workflow Events (Explicit Audit Trail)
;; =============================================================================
;; For events beyond XTDB's history: notifications sent, objection periods, etc.

(defn record-event!
  "Record an explicit workflow event for auditability."
  [node event-type data]
  (xt/execute-tx node
                 [[:put-docs :events
                   (merge data
                          {:xt/id (random-uuid)
                           :event-type event-type
                           :occurred-at (Instant/now)})]]))

(defn booking-events
  "Get all events related to a booking."
  [node booking-id]
  (xt/q node
        '(-> (from :events [*])
             (where (= booking-id $id))
             (order-by occurred-at))
        {:args {:id booking-id}}))

;; =============================================================================
;; Booking Attempts Tracking
;; =============================================================================
;; Track all booking attempts, including rejections, for analytics/fairness.

(defn record-attempt!
  "Record a booking attempt with its outcome."
  [node {:keys [tenant-name start-date end-date outcome conflicts]}]
  (let [attempt-id (random-uuid)]
    (xt/execute-tx node
                   [[:put-docs :booking-attempts
                     {:xt/id attempt-id
                      :tenant-name tenant-name
                      :start-date start-date
                      :end-date end-date
                      :outcome outcome
                      :conflicts conflicts
                      :attempted-at (Instant/now)}]])
    attempt-id))

(defn attempts-by-tenant
  "See a tenant's booking history - successful and rejected."
  [node tenant-name]
  (xt/q node
        '(-> (from :booking-attempts [*])
             (where (= tenant-name $tenant))
             (order-by attempted-at))
        {:args {:tenant tenant-name}}))

;; =============================================================================
;; Example Session
;; =============================================================================

(comment
  (def node (xtn/start-node))

  ;; Deferred decision example
  (request-slot! node {:tenant-name "Flo"
                       :start-date (Instant/parse "2026-03-14T00:00:00Z")
                       :end-date (Instant/parse "2026-03-15T00:00:00Z")
                       :note "Birthday trip!"})

  (request-slot! node {:tenant-name "Val"
                       :start-date (Instant/parse "2026-03-14T00:00:00Z")
                       :end-date (Instant/parse "2026-03-15T00:00:00Z")
                       :priority 10
                       :note "Doctor appointment"})

  (pending-requests-for-slot node
                             (Instant/parse "2026-03-14T00:00:00Z")
                             (Instant/parse "2026-03-15T00:00:00Z"))

  ;; Resolve by priority (Val wins)
  (resolve-slot! node
                 (Instant/parse "2026-03-14T00:00:00Z")
                 (Instant/parse "2026-03-15T00:00:00Z")
                 booking/decide-by-priority)

  ;; Atomic approach
  (install-tx-fns! node)
  (def result (book-atomic! node {:tenant-name "Aaron"
                                  :start-date (Instant/parse "2026-03-20T00:00:00Z")
                                  :end-date (Instant/parse "2026-03-21T00:00:00Z")}))
  (tx-succeeded? node (:tx-result result))

  ;; Record custom events
  (record-event! node :notification-sent
                 {:booking-id (:booking-id result)
                  :recipients ["Val" "Flo"]
                  :notification-type :confirmation})

  (.close node)
  ())
