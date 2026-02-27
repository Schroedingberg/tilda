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
  (booking/create-request! @node
                           {:tenant-name "Flo"
                            :start-date (instant "2026-03-01T00:00:00Z")
                            :end-date (instant "2026-03-03T00:00:00Z")})
  (booking/create-request! @node
                           {:tenant-name "Val"
                            :start-date (instant "2026-03-05T00:00:00Z")
                            :end-date (instant "2026-03-07T00:00:00Z")})
  (booking/create-request! @node
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

  (booking/pending-requests @node)
  ;;=> [{:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-07T00:00Z[UTC]",
  ;;     :tenant-name "Val",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:43.644716Z[UTC]",
  ;;     :xt/id #uuid "199f85a0-6faf-4ec7-a3b4-16aacd78fd20",
  ;;     :start-date #xt/zoned-date-time "2026-03-05T00:00Z[UTC]"}
  ;;    {:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-07T00:00Z[UTC]",
  ;;     :tenant-name "Val",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:29.391619Z[UTC]",
  ;;     :xt/id #uuid "5406bf25-4608-4384-aed8-c6c4a80a5f5e",
  ;;     :start-date #xt/zoned-date-time "2026-03-05T00:00Z[UTC]"}
  ;;    {:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-03T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:29.378286Z[UTC]",
  ;;     :xt/id #uuid "67a485e8-b580-4f69-bdde-1dac9be9cc16",
  ;;     :start-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]"}
  ;;    {:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-12T00:00Z[UTC]",
  ;;     :tenant-name "Aaron",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:43.650004Z[UTC]",
  ;;     :xt/id #uuid "9284b024-5148-486c-9e0a-8a7e91393d6c",
  ;;     :start-date #xt/zoned-date-time "2026-03-10T00:00Z[UTC]"}
  ;;    {:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-12T00:00Z[UTC]",
  ;;     :tenant-name "Aaron",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:29.398422Z[UTC]",
  ;;     :xt/id #uuid "b2b39b60-4e18-41c0-86f7-ef5fe99911d6",
  ;;     :start-date #xt/zoned-date-time "2026-03-10T00:00Z[UTC]"}
  ;;    {:priority 0,
  ;;     :end-date #xt/zoned-date-time "2026-03-03T00:00Z[UTC]",
  ;;     :tenant-name "Flo",
  ;;     :status :pending,
  ;;     :requested-at #xt/zoned-date-time "2026-02-26T12:52:43.637336Z[UTC]",
  ;;     :xt/id #uuid "d0f308d6-0c44-40c8-9f46-b49afcdaf057",
  ;;     :start-date #xt/zoned-date-time "2026-03-01T00:00Z[UTC]"}]



  ;; View history
  (let [id (first (map :xt/id (booking/all-bookings @node)))]
    (booking/booking-history @node id))

  ;; Test deciders (pure functions - no node needed)
  (def sample-requests
    [{:tenant-name "Flo" :requested-at (instant "2026-01-01T09:00:00Z") :priority 5 :xt/id 123}
     {:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z") :priority 10 :xt/id 124}
     {:tenant-name "Aaron" :requested-at (instant "2026-01-01T08:00:00Z") :priority 3 :xt/id 125}])


  (booking/decide-first-come-first-serve sample-requests) ;;=> Aaron

  (def one-tenant-multiple-requests
    [{:tenant-name "Val" :requested-at (instant "2026-01-01T09:00:00Z")
      :start-date (instant "2026-01-01T09:00:00Z")
      :end-date (instant "2026-01-03T10:00:00Z")
      :priority 5 :xt/id 123}
     {:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z")
      :start-date (instant "2026-01-03T10:00:00Z")
      :end-date (instant "2026-01-03T11:00:00Z")
      :priority 10 :xt/id 124}])


  (booking/all-bookings @node)
  ;;=> [{:request-id 125, :tenant-name "Aaron", :xt/id #uuid "a8c0e417-bfd0-4063-ae5a-c6e47b61d315"}]
  ;; Cleanup
  (stop!)

  ())
