(ns tilda.booking
  "Core booking functionality.
   
   Design: XTDB-interacting functions are kept minimal and grouped at the top.
   Pure business logic (deciders, validators) is separate and easily testable."
  (:require
   [xtdb.api :as xt])
  (:import
   [java.time Instant]))

;; =============================================================================
;; XTDB Layer (thin wrapper - all DB interaction here)
;; =============================================================================

(defn create-booking!
  "Create a new booking in pending state."
  [node {:keys [tenant-name start-date end-date]}]
  (let [booking-id (random-uuid)]
    (xt/execute-tx node
                   [[:put-docs :bookings
                     {:xt/id booking-id
                      :tenant-name tenant-name
                      :start-date start-date
                      :end-date end-date
                      :status :pending
                      :approvals #{}}]])
    booking-id))

(defn get-booking
  "Fetch a single booking by ID."
  [node booking-id]
  (first (xt/q node
               '(from :bookings [{:xt/id $id} *])
               {:args {:id booking-id}})))

(defn update-booking!
  "Update a booking document."
  [node booking]
  (xt/execute-tx node [[:put-docs :bookings booking]]))

(defn all-bookings
  "Get all current bookings."
  [node]
  (xt/q node '(from :bookings [*])))

(defn pending-bookings
  "Get all bookings awaiting confirmation."
  [node]
  (xt/q node
        '(-> (from :bookings [*])
             (where (= status :pending)))))

(defn find-conflicts
  "Find existing bookings that conflict with a proposed time slot.
   
   Overlap detection: two ranges conflict iff neither ends before the other starts.
   
   CONFLICT (any overlap):        NO CONFLICT (disjoint):
   |████|        req               |████|           req
       |████|    existing                    |████|  existing
       ↑                                 ↑ gap
   req.start ≤ existing.end       req.end < existing.start
   req.end ≥ existing.start       (or vice versa)"
  [node requested-start requested-end & {:keys [exclude-id]}]
  (let [conflicts (xt/q node
                        '(-> (from :bookings [xt/id tenant-name start-date end-date status])
                             (where (and (<= start-date $end)
                                         (>= end-date $start)
                                         (not (= status :cancelled)))))
                        {:args {:start requested-start :end requested-end}})]
    (if exclude-id
      (remove #(= (:xt/id %) exclude-id) conflicts)
      conflicts)))

(defn booking-history
  "Get the full history of a booking - every state it's been in."
  [node booking-id]
  (xt/q node
        '(from :bookings {:bind [{:xt/id $id} *]
                          :for-valid-time :all-time})
        {:args {:id booking-id}}))

;; =============================================================================
;; Business Logic (pure functions where possible)
;; =============================================================================

(defn can-book?
  "Check if a time slot is available."
  [node start-date end-date]
  (empty? (find-conflicts node start-date end-date)))

(defn approve-booking!
  "Record an approval on a booking."
  [node booking-id approver-name]
  (when-let [booking (get-booking node booking-id)]
    (update-booking! node (update booking :approvals conj approver-name))))

(defn confirm-booking!
  "Transition booking to confirmed status."
  [node booking-id]
  (when-let [booking (get-booking node booking-id)]
    (update-booking! node (assoc booking :status :confirmed))))

(defn cancel-booking!
  "Cancel a booking with a reason."
  [node booking-id reason]
  (when-let [booking (get-booking node booking-id)]
    (update-booking! node (assoc booking
                                 :status :cancelled
                                 :cancellation-reason reason))))

;; =============================================================================
;; Conflict Resolution
;; =============================================================================

(defn try-book!
  "Attempt to create a booking. Returns {:ok booking-id} or {:conflict bookings}.
   Simple check-then-submit - has theoretical race condition but fine for low contention."
  [node {:keys [start-date end-date] :as request}]
  (let [conflicts (find-conflicts node start-date end-date)]
    (if (seq conflicts)
      {:conflict conflicts}
      {:ok (create-booking! node request)})))

;; =============================================================================
;; Decider Functions (pure - easy to test)
;; =============================================================================

(defn decide-first-come-first-serve
  "Pick the earliest request by :requested-at timestamp."
  [requests]
  (first (sort-by :requested-at requests)))

(defn decide-by-priority
  "Pick the highest priority request (highest number wins)."
  [requests]
  (first (sort-by :priority > requests)))

(defn decide-random-lottery
  "Pick randomly - fair but unpredictable."
  [requests]
  (when (seq requests)
    (rand-nth (vec requests))))
