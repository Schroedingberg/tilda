(ns tilda.booking
  "Domain logic for the booking system.
   
   ## Data Model - Existence = State
   
   Simple two-table design where document existence represents state:
   
   - **Request exists** → pending (someone wants this slot)
   - **Request deleted** → resolved (accepted or rejected)
   - **Booking exists** → confirmed reservation
   
   XTDB history preserves audit trail for deleted documents.
   
   ## Resolution
   
   When multiple requests compete for a slot, `resolve-slot!`:
   1. Picks winner via decider function
   2. Creates booking for winner
   3. Deletes all requests (history preserved)
   
   Deciders: `decide-first-come-first-serve`, `decide-by-priority`,
   `decide-random-lottery`
   
   ## Idempotency
   
   `create-request!` is idempotent: calling with the same tenant and
   exact date range returns the existing request-id.
   
   ## Architecture
   
   Pure business logic is separated into `-logic` suffixed functions
   for unit testing. DB operations delegate to these pure functions."
  (:require [xtdb.api :as xt])
  (:import [java.time Instant]))

;; =============================================================================
;; Date Comparison Helpers
;; =============================================================================

(defn before-or-equal?
  "True if a <= b for Comparable types (Instant, LocalDate, etc.)"
  [a b]
  (not (pos? (compare a b))))

(defn after-or-equal?
  "True if a >= b for Comparable types (Instant, LocalDate, etc.)"
  [a b]
  (not (neg? (compare a b))))

(defn overlaps?
  "True if time range [start-a, end-a] overlaps with [start-b, end-b].
   Ranges are inclusive on both ends."
  [start-a end-a start-b end-b]
  (and (before-or-equal? start-a end-b)
       (after-or-equal? end-a start-b)))

;; =============================================================================
;; Pure Business Logic (unit-testable)
;; =============================================================================

(defn find-overlapping-request-logic
  "Given a list of existing requests, find one that overlaps with the time range
   for the same tenant. Returns nil if no overlap found."
  [existing-requests tenant-name start-date end-date]
  (first (filter (fn [req]
                   (and (= (:tenant-name req) tenant-name)
                        (overlaps? (:start-date req) (:end-date req)
                                   start-date end-date)))
                 existing-requests)))

(defn build-request-doc
  "Build a request document. Pure function for testing."
  [{:keys [request-id tenant-name start-date end-date priority requested-at]}]
  {:xt/id request-id
   :tenant-name tenant-name
   :start-date start-date
   :end-date end-date
   :priority (or priority 0)
   :requested-at requested-at})

(defn find-conflicts-logic
  "Given a list of bookings, find those that conflict with proposed time range.
   Pure function for testing."
  [bookings requested-start requested-end & {:keys [exclude-id]}]
  (let [conflicts (filter (fn [booking]
                            (overlaps? (:start-date booking) (:end-date booking)
                                       requested-start requested-end))
                          bookings)]
    (if exclude-id
      (remove #(= (:xt/id %) exclude-id) conflicts)
      conflicts)))

(defn build-booking-doc
  "Build a booking document from a winning request. Pure function for testing."
  [booking-id winner]
  {:xt/id booking-id
   :request-id (:xt/id winner)
   :tenant-name (:tenant-name winner)
   :start-date (:start-date winner)
   :end-date (:end-date winner)})

(defn resolve-slot-logic
  "Resolve competing requests using a decider function. Pure function for testing.
   Returns {:booking-doc ... :winner ... :losers ... :delete-request-ids ...}
   or nil if no requests."
  [requests decider-fn booking-id]
  (when-let [winner (decider-fn requests)]
    (let [losers (remove #(= (:xt/id %) (:xt/id winner)) requests)]
      {:booking-doc (build-booking-doc booking-id winner)
       :winner winner
       :losers losers
       :delete-request-ids (mapv :xt/id requests)})))

(defn build-resolution-tx
  "Build XTDB transaction operations from resolution result. Pure function."
  [{:keys [booking-doc delete-request-ids]}]
  (concat
   [[:put-docs :bookings booking-doc]]
   (for [req-id delete-request-ids]
     [:delete-docs :requests req-id])))

;; =============================================================================
;; Deciders (pure functions)
;; =============================================================================

(defn decide-first-come-first-serve [requests]
  (first (sort-by :requested-at requests)))

(defn decide-by-priority [requests]
  (first (sort-by :priority > requests)))

(defn decide-random-lottery [requests]
  (when (seq requests) (rand-nth (vec requests))))

(defn decide-by-redistribution
  "Prioritize requests by tenants who often lost a booking so far."
  [requests]
  (->> requests))

;; =============================================================================
;; XTDB Queries - Data Loading
;; =============================================================================

(defn find-overlapping-request-query
  "Query for requests that overlap with a given time range for a tenant."
  [node tenant-name start-date end-date]
  (xt/q node
        '(-> (from :requests [xt/id tenant-name start-date end-date])
             (where (and (= tenant-name $tenant)
                         (<= start-date $end)
                         (>= end-date $start))))
        {:args {:tenant tenant-name :start start-date :end end-date}}))

(defn get-request [node request-id]
  (first (xt/q node '(from :requests [{:xt/id $id} *]) {:args {:id request-id}})))

(defn pending-requests
  "All pending requests. Existence = pending, deletion = resolved."
  [node]
  (xt/q node '(from :requests [*])))

(defn find-conflicting-requests
  "Find requests that overlap with a time range, to find competitors for resolution.
   This queries over ALL tenants."
  [node start-date end-date]
  (xt/q node
        '(-> (from :requests [*])
             (where (and (<= start-date $end)
                         (>= end-date $start))))
        {:args {:start start-date :end end-date}}))

(defn request-history
  "Audit trail for a request via XTDB time-travel."
  [node request-id]
  (xt/q node
        '(from :requests {:bind [{:xt/id $id} *] :for-valid-time :all-time})
        {:args {:id request-id}}))

(defn get-booking [node booking-id]
  (first (xt/q node '(from :bookings [{:xt/id $id} *]) {:args {:id booking-id}})))

(defn all-bookings [node]
  (xt/q node '(from :bookings [*])))

(defn find-conflicts-query
  "Query for bookings that conflict with a proposed time slot."
  [node requested-start requested-end]
  (xt/q node
        '(-> (from :bookings [xt/id tenant-name start-date end-date])
             (where (and (<= start-date $end)
                         (>= end-date $start))))
        {:args {:start requested-start :end requested-end}}))

(defn booking-history [node booking-id]
  (xt/q node
        '(from :bookings {:bind [{:xt/id $id} *] :for-valid-time :all-time})
        {:args {:id booking-id}}))

;; =============================================================================
;; XTDB Commands - Data Writing (delegate to pure logic)
;; =============================================================================

(defn create-request!
  "Submit a request for a time slot. Returns request-id.
   Idempotent: returns existing request-id if tenant already has overlapping request."
  [node {:keys [tenant-name start-date end-date priority]}]
  (let [existing (find-overlapping-request-query node tenant-name start-date end-date)]
    (if-let [overlapping (find-overlapping-request-logic existing tenant-name start-date end-date)]
      (:xt/id overlapping)
      (let [request-id (random-uuid)
            doc (build-request-doc {:request-id request-id
                                    :tenant-name tenant-name
                                    :start-date start-date
                                    :end-date end-date
                                    :priority priority
                                    :requested-at (Instant/now)})]
        (xt/execute-tx node [[:put-docs :requests doc]])
        request-id))))

(defn cancel-request!
  "Delete a pending request. Use request-history to prove it existed."
  [node request-id]
  (xt/execute-tx node [[:delete-docs :requests request-id]]))

(defn find-conflicts
  "Find bookings that conflict with a proposed time slot.
   Combines query + pure logic for filtering."
  [node requested-start requested-end & {:keys [exclude-id]}]
  (let [conflicts (find-conflicts-query node requested-start requested-end)]
    (if exclude-id
      (remove #(= (:xt/id %) exclude-id) conflicts)
      conflicts)))

(defn cancel-booking!
  "Delete a booking. Use booking-history to prove it existed."
  [node booking-id]
  (xt/execute-tx node [[:delete-docs :bookings booking-id]]))

(defn resolve-slot!
  "Resolve competing requests using a decider function.
   Winner becomes a booking, all requests are deleted (history preserved).
   Returns {:booking-id ... :winner ... :rejected [...]}."
  [node requests decider-fn]
  (let [booking-id (random-uuid)]
    (when-let [result (resolve-slot-logic requests decider-fn booking-id)]
      (xt/execute-tx node (build-resolution-tx result))
      {:booking-id booking-id
       :winner (:xt/id (:winner result))
       :rejected (mapv :xt/id (:losers result))})))
