(ns tilda.booking-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [tilda.booking :as b]
   [xtdb.node :as xtn])
  (:import [java.time Instant]))

(def ^:dynamic *node* nil)

(defn with-node [f]
  (with-open [node (xtn/start-node)]
    (binding [*node* node] (f))))

(use-fixtures :each with-node)

(defn instant [s] (Instant/parse s))

;; =============================================================================
;; Decider Tests (pure)
;; =============================================================================

(deftest decide-first-come-first-serve-test
  (let [requests [{:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z")}
                  {:tenant-name "Flo" :requested-at (instant "2026-01-01T09:00:00Z")}]]
    (is (= "Flo" (:tenant-name (b/decide-first-come-first-serve requests))))))

(deftest decide-by-priority-test
  (let [requests [{:tenant-name "Val" :priority 5}
                  {:tenant-name "Flo" :priority 10}]]
    (is (= "Flo" (:tenant-name (b/decide-by-priority requests))))))

(deftest decide-random-lottery-test
  (let [requests [{:tenant-name "Val"} {:tenant-name "Flo"}]
        result (b/decide-random-lottery requests)]
    (is (some #(= % result) requests))
    (is (nil? (b/decide-random-lottery [])))))

;; =============================================================================
;; Request Tests
;; =============================================================================

(deftest create-request-test
  (let [req-id (b/create-request! *node* {:tenant-name "Flo"
                                          :start-date (instant "2026-03-01T00:00:00Z")
                                          :end-date (instant "2026-03-02T00:00:00Z")})
        req (b/get-request *node* req-id)]
    (is (uuid? req-id))
    (is (= "Flo" (:tenant-name req)))
    (is (= 0 (:priority req)))))

(deftest find-conflicting-requests-test
  (b/create-request! *node* {:tenant-name "Flo"
                             :start-date (instant "2026-03-01T00:00:00Z")
                             :end-date (instant "2026-03-03T00:00:00Z")})
  (is (seq (b/find-conflicting-requests *node*
                                        (instant "2026-03-02T00:00:00Z")
                                        (instant "2026-03-04T00:00:00Z"))))
  (is (empty? (b/find-conflicting-requests *node*
                                           (instant "2026-04-01T00:00:00Z")
                                           (instant "2026-04-02T00:00:00Z")))))

;; =============================================================================
;; Resolution Tests  
;; =============================================================================

(deftest resolve-slot-test
  (let [r1 (b/create-request! *node* {:tenant-name "Flo" :priority 5
                                      :start-date (instant "2026-03-01T00:00:00Z")
                                      :end-date (instant "2026-03-02T00:00:00Z")})
        r2 (b/create-request! *node* {:tenant-name "Val" :priority 10
                                      :start-date (instant "2026-03-01T00:00:00Z")
                                      :end-date (instant "2026-03-02T00:00:00Z")})
        requests (map #(b/get-request *node* %) [r1 r2])
        result (b/resolve-slot! *node* requests b/decide-by-priority)]
    ;; Val wins (higher priority)
    (is (= r2 (:winner result)))
    (is (= [r1] (:rejected result)))
    ;; Booking created
    (let [booking (b/get-booking *node* (:booking-id result))]
      (is (= "Val" (:tenant-name booking))))
    ;; Requests are deleted after resolution (not updated)
    (is (nil? (b/get-request *node* r1)))
    (is (nil? (b/get-request *node* r2)))
    ;; But history preserves them
    (is (seq (b/request-history *node* r1)))
    (is (seq (b/request-history *node* r2)))))

(deftest cancel-request-test
  (let [req-id (b/create-request! *node* {:tenant-name "Flo"
                                          :start-date (instant "2026-06-01T00:00:00Z")
                                          :end-date (instant "2026-06-02T00:00:00Z")})]
    ;; Request exists
    (is (some? (b/get-request *node* req-id)))
    (b/cancel-request! *node* req-id)
    ;; Request gone
    (is (nil? (b/get-request *node* req-id)))
    ;; But history proves it existed
    (is (seq (b/request-history *node* req-id)))))

;; =============================================================================
;; Booking Tests
;; =============================================================================

(deftest find-conflicts-test
  ;; Create a request and resolve it to make a booking
  (let [req-id (b/create-request! *node* {:tenant-name "Flo"
                                          :start-date (instant "2026-03-01T00:00:00Z")
                                          :end-date (instant "2026-03-03T00:00:00Z")})
        req (b/get-request *node* req-id)
        result (b/resolve-slot! *node* [req] first)]
    (is (:booking-id result))
    ;; Now there's a conflict
    (is (seq (b/find-conflicts *node*
                               (instant "2026-03-02T00:00:00Z")
                               (instant "2026-03-04T00:00:00Z"))))
    ;; No conflict outside range
    (is (empty? (b/find-conflicts *node*
                                  (instant "2026-04-01T00:00:00Z")
                                  (instant "2026-04-02T00:00:00Z"))))))

(deftest cancel-booking-test
  (let [req-id (b/create-request! *node* {:tenant-name "Flo"
                                          :start-date (instant "2026-05-01T00:00:00Z")
                                          :end-date (instant "2026-05-02T00:00:00Z")})
        req (b/get-request *node* req-id)
        {:keys [booking-id]} (b/resolve-slot! *node* [req] first)]
    ;; Booking exists
    (is (some? (b/get-booking *node* booking-id)))
    (b/cancel-booking! *node* booking-id)
    ;; Booking gone
    (is (nil? (b/get-booking *node* booking-id)))
    ;; But history proves it existed
    (is (seq (b/booking-history *node* booking-id)))))


