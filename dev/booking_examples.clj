(ns dev.booking-examples
  "Examples of leveraging XTDB's bitemporality for the booking system.
   
   Key insight: XTDB is already event-sourced under the hood.
   Every put-docs creates an immutable fact in the transaction log.
   We get history, temporal queries, and auditability for free."
  (:require
   [xtdb.api :as xt]
   [xtdb.node :as xtn])
  (:import
   [java.time Instant Duration]))

;; =============================================================================
;; Roadmap (post-MVP)
;; =============================================================================
;; - find-conflicting-requests: show users "others are also interested in this slot"

;; =============================================================================
;; Node Setup
;; =============================================================================

(defonce node (xtn/start-node))

;; =============================================================================
;; Basic Booking Operations
;; =============================================================================

(defn create-booking!
  "Create a new booking in pending state.
   XTDB automatically records transaction time."
  [node {:keys [tenant-name start-date end-date]}]
  (let [booking-id (random-uuid)]
    (xt/submit-tx node
                  [[:put-docs :bookings
                    {:xt/id booking-id
                     :tenant-name tenant-name
                     :start-date start-date
                     :end-date end-date
                     :status :pending
                     :approvals #{}}]])
    booking-id))

(defn approve-booking!
  "Record an approval. Updates the document; XTDB keeps full history."
  [node booking-id approver-name]
  (let [[booking] (xt/q node
                        '(from :bookings [{:xt/id $id} *])
                        {:args {:id booking-id}})]
    (when booking
      (xt/submit-tx node
                    [[:put-docs :bookings
                      (update booking :approvals conj approver-name)]]))))

(defn confirm-booking!
  "Transition booking to confirmed status."
  [node booking-id]
  (let [[booking] (xt/q node
                        '(from :bookings [{:xt/id $id} *])
                        {:args {:id booking-id}})]
    (when booking
      (xt/submit-tx node
                    [[:put-docs :bookings
                      (assoc booking :status :confirmed)]]))))

(defn cancel-booking!
  "Cancel a booking. The history remains - we can see it was cancelled, not deleted."
  [node booking-id reason]
  (let [[booking] (xt/q node
                        '(from :bookings [{:xt/id $id} *])
                        {:args {:id booking-id}})]
    (when booking
      (xt/submit-tx node
                    [[:put-docs :bookings
                      (assoc booking
                             :status :cancelled
                             :cancellation-reason reason)]]))))

;; =============================================================================
;; Querying
;; =============================================================================

(defn all-bookings
  "Get all current bookings."
  [node]
  (xt/q node '(from :bookings [*])))

(defn bookings-by-tenant
  "Get bookings for a specific tenant."
  [node tenant-name]
  (xt/q node
        '(-> (from :bookings [*])
             (where (= tenant-name $tenant)))
        {:args {:tenant tenant-name}}))

(defn pending-bookings
  "Get all bookings awaiting confirmation."
  [node]
  (xt/q node
        '(-> (from :bookings [*])
             (where (= status :pending)))))

(defn bookings-in-range
  "Find bookings that overlap with a given date range."
  [node range-start range-end]
  (xt/q node
        '(-> (from :bookings [*])
             (where (and (<= start-date $end)
                         (>= end-date $start))))
        {:args {:start range-start :end range-end}}))

;; =============================================================================
;; History & Auditing (This is where XTDB shines)
;; =============================================================================

(defn booking-history
  "Get the full history of a booking - every state it's been in.
   This is the 'event sourcing' benefit without manual event dispatch."
  [node booking-id]
  (xt/q node
        '(from :bookings {:bind [{:xt/id $id} *]
                          :for-valid-time :all-time})
        {:args {:id booking-id}}))

;; =============================================================================
;; Conflict Detection
;; =============================================================================

(defn find-conflicts
  "Find any existing bookings that conflict with a proposed time slot.
   
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




(defn can-book?
  "Check if a time slot is available."
  [node start-date end-date]
  (empty? (find-conflicts node start-date end-date)))

;; =============================================================================
;; Conflict Resolution: First Come First Serve
;; =============================================================================

;; Approach 1: Check-then-submit (simple, tiny race window)
;; Fine for 10 users booking a shared car - collisions are rare

(defn try-book!
  "Attempt to create a booking. Returns {:ok booking-id} or {:conflict existing-bookings}.
   Simple check-then-submit - has theoretical race condition but fine for low contention.
   Always records the attempt, even if rejected."
  [node {:keys [tenant-name start-date end-date] :as request}]
  (let [attempt-id (random-uuid)
        conflicts (find-conflicts node start-date end-date)
        outcome (if (seq conflicts) :rejected :accepted)]
    ;; Always record the attempt - this is just another document
    (xt/submit-tx node
                  [[:put-docs :booking-attempts
                    {:xt/id attempt-id
                     :tenant-name tenant-name
                     :start-date start-date
                     :end-date end-date
                     :outcome outcome
                     :conflicts (when (seq conflicts)
                                  (mapv :id conflicts))}]])
    ;; If no conflicts, also create the booking
    (if (= outcome :accepted)
      {:ok (create-booking! node request)
       :attempt-id attempt-id}
      {:conflict conflicts
       :attempt-id attempt-id})))

(defn booking-attempts
  "See all attempts for a time range - who tried, who got rejected."
  [node start-date end-date]
  (xt/q node
        '(from :booking-attempts [*]
               (where (and (<= start_date $end)
                           (>= end_date $start)))
               (order-by xt/id))  ; implicitly ordered by insertion time
        {:args {:start start-date :end end-date}}))

(defn attempts-by-tenant
  "See a tenant's booking history - successful and rejected."
  [node tenant-name]
  (xt/q node
        '(from :booking-attempts [*]
               (where (= tenant_name $tenant))
               (order-by xt/id))
        {:args {:tenant tenant-name}}))

;; =============================================================================
;; Deferred Decision Pattern
;; =============================================================================

;; Instead of deciding immediately, collect requests and decide later.
;; Flow: request-slot! → (accumulate) → resolve-slot! using a decider function

(defn request-slot!
  "Request a time slot. Does NOT create a booking yet - just records intent.
   Returns the request-id."
  [node {:keys [tenant-name start-date end-date priority note]}]
  (let [request-id (random-uuid)]
    (xt/submit-tx node
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
        '(from :slot-requests [*]
               (where (and (<= start_date $end)
                           (>= end_date $start)
                           (= status :pending)))
               (order-by requested_at))
        {:args {:start start-date :end end-date}}))

;; ---------------------------------------------------------------------------
;; Decider functions - pure-ish functions, easy to test and swap
;; ---------------------------------------------------------------------------

(defn decide-first-come-first-serve
  "Pick the earliest request."
  [requests]
  (first (sort-by :requested_at requests)))

(defn decide-by-priority
  "Pick the highest priority request (highest number wins)."
  [requests]
  (first (sort-by :priority > requests)))

(defn decide-random-lottery
  "Pick randomly - fair but unpredictable."
  [requests]
  (when (seq requests)
    (rand-nth (vec requests))))

(defn decide-least-recent-winner
  "Pick the tenant who hasn't won a booking in the longest time.
   Requires passing in booking history."
  [requests last-booking-by-tenant]
  (first (sort-by #(get last-booking-by-tenant (:tenant_name %) (Instant/parse "1970-01-01T00:00:00Z"))
                  requests)))

;; ---------------------------------------------------------------------------
;; Resolution - apply a decision
;; ---------------------------------------------------------------------------

(defn resolve-slot!
  "Resolve competing requests using a decider function.
   Creates a booking for the winner, marks others as :rejected."
  [node start-date end-date decider-fn]
  (let [requests (pending-requests-for-slot node start-date end-date)]
    (when (seq requests)
      (let [winner (decider-fn requests)
            losers (remove #(= (:xt/id %) (:xt/id winner)) requests)]
        ;; Create booking for winner
        (let [booking-id (create-booking! node
                                          {:tenant-name (:tenant_name winner)
                                           :start-date (:start_date winner)
                                           :end-date (:end_date winner)})]
          ;; Mark winner's request as accepted
          (xt/submit-tx node
                        [[:put-docs :slot-requests
                          (assoc winner
                                 :status :accepted
                                 :booking-id booking-id)]])
          ;; Mark losers as rejected
          (doseq [loser losers]
            (xt/submit-tx node
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
          '(-> (from :slot-requests [start_date end_date]
                     (where (and (= status :pending)
                                 (<= start_date $cutoff))))
               (aggregate :start_date :end_date))
          {:args {:cutoff cutoff}})))

;; Approach 2: True atomic first-come-first-serve using XTDB transaction functions
;; Use this if you need guarantees (high contention, legal requirements, etc.)

(defn install-tx-fns!
  "Install transaction functions into XTDB. Call once at startup."
  [node]
  (xt/submit-tx node
                [[:put-docs :xt/tx-fns
                  {:xt/id :book-if-available
                   :xt/fn
                   '(fn [ctx {:keys [booking-id tenant-name start-date end-date]}]
                      ;; Query for conflicts within the transaction
                      (let [conflicts (xtdb.api/q ctx
                                                  '(from :bookings [{:xt/id id} tenant_name start_date end_date status]
                                                         (where (and (<= start_date $end)
                                                                     (>= end_date $start)
                                                                     (not= status :cancelled))))
                                                  {:args {:start start-date :end end-date}})]
                        (if (seq conflicts)
                          ;; Abort transaction - no changes made
                          false
                          ;; Proceed with booking
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
  ;; In XTDB 2.x, we can check the tx status
  (let [tx-id (:tx-id tx-result)]
    (when tx-id
      (xt/q node
            '(from :xt/txs [{:xt/id $id} committed])
            {:args {:id tx-id}}))))

;; =============================================================================
;; Workflow Events (Optional - for explicit audit trail)
;; =============================================================================

;; If you want explicit events beyond what XTDB's history gives you
;; (e.g., notifications sent, objection periods started), store them separately:

(defn record-event!
  "Record an explicit workflow event for auditability."
  [node event-type data]
  (xt/submit-tx node
                [[:put-docs :events
                  (merge data
                         {:xt/id (random-uuid)
                          :event-type event-type
                          :occurred-at (Instant/now)})]]))

(defn booking-events
  "Get all events related to a booking."
  [node booking-id]
  (xt/q node
        '(from :events [*]
               (where (= booking_id $id))
               (order-by occurred_at))
        {:args {:id booking-id}}))

;; =============================================================================
;; Example Session
;; =============================================================================

(comment
  ;; ---------------------------------------------------------------------------
  ;; First Come First Serve Examples
  ;; ---------------------------------------------------------------------------

  ;; Simple approach: try-book! checks and submits
  (try-book! node
             {:tenant-name "Flo"
              :start-date (Instant/parse "2026-02-28T00:00:00Z")
              :end-date (Instant/parse "2026-03-01T00:00:00Z")})


  ;; Second person tries same slot - rejected
  (try-book! node
             {:tenant-name "Val"
              :start-date (Instant/parse "2026-02-28T00:00:00Z")
              :end-date (Instant/parse "2026-03-01T00:00:00Z")})
  ;;=> {:conflict
  ;;    [{:id #uuid "b159fdfa-ddc1-4272-92c4-8e7c58d5f2ad",
  ;;      :tenant-name "Flo",
  ;;      :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]",
  ;;      :end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;      :status :pending}],
  ;;    :attempt-id #uuid "d3cea011-8956-43d4-bdde-250e8bd1335b"}


  ;; Adjacent slot works fine
  (try-book! node
             {:tenant-name "Val"
              :start-date (Instant/parse "2026-03-01T00:00:00Z")
              :end-date (Instant/parse "2026-03-02T00:00:00Z")})
  ;;=> {:conflict
  ;;    [{:id #uuid "b159fdfa-ddc1-4272-92c4-8e7c58d5f2ad",
  ;;      :tenant-name "Flo",
  ;;      :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]",
  ;;      :end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;      :status :pending}],
  ;;    :attempt-id #uuid "8a162258-b822-4960-8e0c-f28c224f4b2f"}


  ;; ---------------------------------------------------------------------------
  ;; Atomic approach (if you need guarantees)
  ;; ---------------------------------------------------------------------------

  ;; Install tx functions once
  (install-tx-fns! node)

  ;; Now book atomically - no race conditions possible
  (def result
    (book-atomic! node
                  {:tenant-name "Aaron"
                   :start-date (Instant/parse "2026-03-05T00:00:00Z")
                   :end-date (Instant/parse "2026-03-06T00:00:00Z")}))

  ;; Check if it worked
  (tx-succeeded? node (:tx-result result))
  ;; => [{:committed true}] if succeeded
  ;; => [{:committed false}] if slot was taken

  ;; ---------------------------------------------------------------------------
  ;; Deferred Decision Examples (collect requests, decide later)
  ;; ---------------------------------------------------------------------------

  ;; Multiple people request the same weekend
  (request-slot! node
                 {:tenant-name "Flo"
                  :start-date (Instant/parse "2026-03-14T00:00:00Z")
                  :end-date (Instant/parse "2026-03-15T00:00:00Z")
                  :note "Birthday trip!"})

  (request-slot! node
                 {:tenant-name "Val"
                  :start-date (Instant/parse "2026-03-14T00:00:00Z")
                  :end-date (Instant/parse "2026-03-15T00:00:00Z")
                  :priority 10  ; high priority
                  :note "Doctor appointment"})

  (request-slot! node
                 {:tenant-name "Aaron"
                  :start-date (Instant/parse "2026-03-14T00:00:00Z")
                  :end-date (Instant/parse "2026-03-15T00:00:00Z")})

  ;; See all pending requests for that slot
  (pending-requests-for-slot node
                             (Instant/parse "2026-03-14T00:00:00Z")
                             (Instant/parse "2026-03-15T00:00:00Z"))

  ;; Resolve using first-come-first-serve
  (resolve-slot! node
                 (Instant/parse "2026-03-14T00:00:00Z")
                 (Instant/parse "2026-03-15T00:00:00Z")
                 decide-first-come-first-serve)
  ;; => {:winner {...Flo...} :booking-id #uuid"..." :rejected 2}

  ;; Or resolve by priority (Val wins because priority=10)
  (resolve-slot! node
                 (Instant/parse "2026-03-14T00:00:00Z")
                 (Instant/parse "2026-03-15T00:00:00Z")
                 decide-by-priority)

  ;; Or do a random lottery
  (resolve-slot! node
                 (Instant/parse "2026-03-14T00:00:00Z")
                 (Instant/parse "2026-03-15T00:00:00Z")
                 decide-random-lottery)

  ;; Find slots that need resolution soon (e.g., within 7 days)
  (slots-needing-resolution node 7)

  ;; You can run this as a daily job:
  ;; (doseq [{:keys [start_date end_date]} (slots-needing-resolution node 7)]
  ;;   (resolve-slot! node start_date end_date decide-first-come-first-serve))

  ;; ---------------------------------------------------------------------------
  ;; Basic Operations
  ;; ---------------------------------------------------------------------------

  ;; 1. Create a booking
  (def booking-id
    (create-booking! node
                     {:tenant-name "Flo"
                      :start-date (Instant/parse "2026-02-28T00:00:00Z")
                      :end-date (Instant/parse "2026-03-01T00:00:00Z")}))

  ;; 2. Check what we have
  (all-bookings node)
  ;;=> [{:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}
  ;;    {:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{},
  ;;     :status :pending,
  ;;     :xt/id #uuid "b159fdfa-ddc1-4272-92c4-8e7c58d5f2ad",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}]
  (pending-bookings node)
  ;;=> [{:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}
  ;;    {:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{},
  ;;     :status :pending,
  ;;     :xt/id #uuid "b159fdfa-ddc1-4272-92c4-8e7c58d5f2ad",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}]

  ;; 3. Someone approves
  (approve-booking! node booking-id "Val")
  ;;=> 8
  (approve-booking! node booking-id "Aaron")
  ;;=> 9

  ;; 4. Check the booking now
  (xt/q node
        '(from :bookings [{:xt/id $id} *])
        {:args {:id booking-id}})
  ;;=> [{:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{"Val" "Aaron"},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}]

  ;; 5. Confirm it
  (confirm-booking! node booking-id)

  ;; 6. See the FULL HISTORY - this is the magic
  ;; Every state the booking went through, with timestamps
  (sort-by :end-date (booking-history node booking-id))
  ;;=> ({:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{"Val" "Aaron"},
  ;;     :status :confirmed,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}
  ;;    {:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{"Val" "Aaron"},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}
  ;;    {:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{"Val"},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"}
  ;;    {:end-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :approvals #{},
  ;;     :status :pending,
  ;;     :xt/id #uuid "201546b2-56a7-47e1-a1b1-36f825fba5cb",
  ;;     :start-date #xt/zoned-date-time "2026-02-28T00:00Z[UTC]"})


  ;; 7. Check for conflicts before creating new booking
  (can-book? node
             (Instant/parse "2026-02-27T00:00:00Z")
             (Instant/parse "2026-02-28T12:00:00Z"))
  ;; => false (overlaps with Flo's booking)

  (can-book? node
             (Instant/parse "2026-04-04T00:00:00Z")
             (Instant/parse "2026-04-05T00:00:00Z"))
  ;; => true

  ;; 8. Optional: Record explicit events for notifications etc.
  (record-event! node :notification-sent
                 {:booking-id booking-id
                  :recipients ["Val" "Aaron" "Chris"]
                  :notification-type :approval-request})

  (booking-events node booking-id)

  ;; Cleanup
  (.close node))
