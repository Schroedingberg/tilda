(ns tilda.booking
  "Two-table design: requests (expressions of interest) → bookings (confirmed reservations).
   
   Lifecycle: create-request! → resolve-slot! → booking created, losers rejected"
  (:require [xtdb.api :as xt])
  (:import [java.time Instant]))

;; =============================================================================
;; Requests - "I want this slot"
;; =============================================================================

(defn find-existing-request
  "Find an existing pending request for same tenant and exact date range."
  [node tenant-name start-date end-date]
  (first (xt/q node
               '(-> (from :requests [xt/id tenant-name start-date end-date status])
                    (where (and (= tenant-name $tenant)
                                (= start-date $start)
                                (= end-date $end)
                                (= status :pending))))
               {:args {:tenant tenant-name :start start-date :end end-date}})))

(defn create-request!
  "Submit a request for a time slot. Returns request-id.
   Idempotent: returns existing request-id if same tenant+dates already pending."
  [node {:keys [tenant-name start-date end-date priority]}]
  (if-let [existing (find-existing-request node tenant-name start-date end-date)]
    (:xt/id existing)
    (let [request-id (random-uuid)]
      (xt/execute-tx node
                     [[:put-docs :requests
                       {:xt/id request-id
                        :tenant-name tenant-name
                        :start-date start-date
                        :end-date end-date
                        :priority (or priority 0)
                        :status :pending
                        :requested-at (Instant/now)}]])
      request-id)))

(defn get-request [node request-id]
  (first (xt/q node '(from :requests [{:xt/id $id} *]) {:args {:id request-id}})))

(defn pending-requests
  "All unresolved requests."
  [node]
  (xt/q node '(-> (from :requests [*]) (where (= status :pending)))))

(defn find-conflicting-requests
  "Find pending requests that overlap with a time range."
  [node start-date end-date]
  (xt/q node
        '(-> (from :requests [*])
             (where (and (= status :pending)
                         (<= start-date $end)
                         (>= end-date $start))))
        {:args {:start start-date :end end-date}}))

(defn- update-request! [node request]
  (xt/execute-tx node [[:put-docs :requests request]]))

;; =============================================================================
;; Bookings - "This slot is confirmed"
;; =============================================================================

(defn get-booking [node booking-id]
  (first (xt/q node '(from :bookings [{:xt/id $id} *]) {:args {:id booking-id}})))

(defn all-bookings [node]
  (xt/q node '(from :bookings [*])))

(defn find-conflicts
  "Find bookings that conflict with a proposed time slot."
  [node requested-start requested-end & {:keys [exclude-id]}]
  (let [conflicts (xt/q node
                        '(-> (from :bookings [xt/id tenant-name start-date end-date])
                             (where (and (<= start-date $end)
                                         (>= end-date $start))))
                        {:args {:start requested-start :end requested-end}})]
    (if exclude-id
      (remove #(= (:xt/id %) exclude-id) conflicts)
      conflicts)))

(defn cancel-booking!
  "Delete a booking. Use booking-history to prove it existed."
  [node booking-id]
  (xt/execute-tx node [[:delete-docs :bookings booking-id]]))

(defn booking-history [node booking-id]
  (xt/q node
        '(from :bookings {:bind [{:xt/id $id} *] :for-valid-time :all-time})
        {:args {:id booking-id}}))

;; =============================================================================
;; Resolution - Request → Booking
;; =============================================================================

(defn resolve-slot!
  "Resolve competing requests using a decider function.
   Winner becomes a booking, losers are rejected. Returns {:booking-id ... :rejected [...]}."
  [node requests decider-fn]
  (when-let [winner (decider-fn requests)]
    (let [booking-id (random-uuid)
          losers (remove #(= (:xt/id %) (:xt/id winner)) requests)]
      ;; Single atomic transaction: create booking + update all request statuses
      (xt/execute-tx node
                     (concat
                      [[:put-docs :bookings
                        {:xt/id booking-id
                         :request-id (:xt/id winner)
                         :tenant-name (:tenant-name winner)
                         :start-date (:start-date winner)
                         :end-date (:end-date winner)}]
                       [:put-docs :requests (assoc winner :status :accepted)]]
                      (for [loser losers]
                        [:put-docs :requests (assoc loser :status :rejected)])))
      {:booking-id booking-id
       :winner (:xt/id winner)
       :rejected (mapv :xt/id losers)})))

;; =============================================================================
;; Deciders (pure functions)
;; =============================================================================

(defn decide-first-come-first-serve [requests]
  (first (sort-by :requested-at requests)))

(defn decide-by-priority [requests]
  (first (sort-by :priority > requests)))

(defn decide-random-lottery [requests]
  (when (seq requests) (rand-nth (vec requests))))
