(ns dev.fiddle
  "REPL lab for experimenting with booking functionality.
   
   Quick start:
     (start!)      ; Fresh XTDB node
     (seed-data!)  ; Create sample requests
     (stop!)       ; Cleanup"
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

(defn instant-min [a b] (if (.isBefore a b) a b))
(defn instant-max [a b] (if (.isAfter a b) a b))

(defn seed-data!
  "Add sample requests for experimentation."
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
;; Experiments - evaluate forms in this comment block
;; =============================================================================

(comment
  ;; --- Getting started ---
  (start!)
  (seed-data!)

  ;; --- Queries ---
  (booking/pending-requests @node)
  (booking/all-bookings @node)

  ;; --- Deciders (pure functions - no node needed) ---
  (def sample-requests
    [{:tenant-name "Flo" :requested-at (instant "2026-01-01T09:00:00Z") :priority 5 :xt/id 1}
     {:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z") :priority 10 :xt/id 2}
     {:tenant-name "Aaron" :requested-at (instant "2026-01-01T08:00:00Z") :priority 3 :xt/id 3}])

  (booking/decide-first-come-first-serve sample-requests) ; => Aaron (earliest)
  (booking/decide-by-priority sample-requests)            ; => Val (highest priority)
  (booking/decide-random-lottery sample-requests)         ; => random

  ;; --- Resolution flow ---
  (let [requests (booking/find-conflicting-requests @node
                                                    (instant "2026-03-01T00:00:00Z")
                                                    (instant "2026-03-03T23:59:59Z"))]
    (booking/resolve-slot! @node requests booking/decide-first-come-first-serve))

  ;; --- History (XTDB time-travel) ---
  (let [id (-> (booking/all-bookings @node) first :xt/id)]
    (booking/booking-history @node id))

  ;; --- Cleanup ---
  (stop!)


  ())
