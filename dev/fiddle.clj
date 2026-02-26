(ns dev.fiddle
  "REPL lab for experimenting with booking functionality.
   
   Start here: evaluate (start!) to get a fresh XTDB node."
  (:require
   [tilda.booking :as booking]
   [xtdb.node :as xtn])
  (:import
   [java.time Instant]))

;; =============================================================================
;; Node Setup
;; =============================================================================

(defonce node (atom nil))

(defn start!
  "Start a fresh in-memory XTDB node."
  []
  (when @node (.close @node))
  (reset! node (xtn/start-node))
  :started)

(defn stop!
  "Stop the current node."
  []
  (when @node
    (.close @node)
    (reset! node nil))
  :stopped)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn instant [s]
  (Instant/parse s))

(defn seed-data!
  "Add some sample bookings for experimentation."
  []
  (booking/create-booking! @node
                           {:tenant-name "Flo"
                            :start-date (instant "2026-03-01T00:00:00Z")
                            :end-date (instant "2026-03-03T00:00:00Z")})
  (booking/create-booking! @node
                           {:tenant-name "Val"
                            :start-date (instant "2026-03-05T00:00:00Z")
                            :end-date (instant "2026-03-07T00:00:00Z")})
  (booking/create-booking! @node
                           {:tenant-name "Aaron"
                            :start-date (instant "2026-03-10T00:00:00Z")
                            :end-date (instant "2026-03-12T00:00:00Z")})
  :seeded)

;; =============================================================================
;; Experiments - try things here!
;; =============================================================================

(comment
  ;; Start fresh
  (start!)
  (seed-data!)

  ;; Basic queries
  (booking/all-bookings @node)
  (booking/pending-bookings @node)

  ;; Try booking a slot
  (booking/try-book! @node
                     {:tenant-name "Chris"
                      :start-date (instant "2026-03-15T00:00:00Z")
                      :end-date (instant "2026-03-16T00:00:00Z")})

  ;; Try conflicting booking
  (booking/try-book! @node
                     {:tenant-name "Chris"
                      :start-date (instant "2026-03-02T00:00:00Z")
                      :end-date (instant "2026-03-04T00:00:00Z")})

  ;; Check conflicts
  (booking/find-conflicts @node
                          (instant "2026-03-02T00:00:00Z")
                          (instant "2026-03-04T00:00:00Z"))

  (booking/can-book? @node
                     (instant "2026-03-20T00:00:00Z")
                     (instant "2026-03-21T00:00:00Z"))

  ;; Approval workflow
  (let [id (first (map :xt/id (booking/pending-bookings @node)))]
    (booking/approve-booking! @node id "Val")
    (booking/approve-booking! @node id "Aaron")
    (booking/confirm-booking! @node id)
    (booking/get-booking @node id))

  ;; View history
  (let [id (first (map :xt/id (booking/all-bookings @node)))]
    (booking/booking-history @node id))

  ;; Test deciders (pure functions - no node needed)
  (def sample-requests
    [{:tenant-name "Flo" :requested-at (instant "2026-01-01T09:00:00Z") :priority 5}
     {:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z") :priority 10}
     {:tenant-name "Aaron" :requested-at (instant "2026-01-01T08:00:00Z") :priority 3}])

  (booking/decide-first-come-first-serve sample-requests)
  ;; => Aaron (earliest)

  (booking/decide-by-priority sample-requests)
  ;; => Val (highest priority)

  (booking/decide-random-lottery sample-requests)
  ;; => random pick

  ;; Cleanup
  (stop!)

  ())
