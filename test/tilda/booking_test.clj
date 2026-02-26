(ns tilda.booking-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [tilda.booking :as booking]
   [xtdb.node :as xtn])
  (:import
   [java.time Instant]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-node* nil)

(defn with-test-node [f]
  (with-open [node (xtn/start-node)]
    (binding [*test-node* node]
      (f))))

(use-fixtures :each with-test-node)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn instant [s]
  (Instant/parse s))

(defn make-booking! [tenant start end]
  (booking/create-booking! *test-node*
                           {:tenant-name tenant
                            :start-date (instant start)
                            :end-date (instant end)}))

;; =============================================================================
;; Pure Function Tests (no XTDB needed)
;; =============================================================================

(deftest decide-first-come-first-serve-test
  (testing "picks earliest by requested-at"
    (let [requests [{:tenant-name "Val" :requested-at (instant "2026-01-01T10:00:00Z")}
                    {:tenant-name "Flo" :requested-at (instant "2026-01-01T09:00:00Z")}
                    {:tenant-name "Aaron" :requested-at (instant "2026-01-01T11:00:00Z")}]]
      (is (= "Flo" (:tenant-name (booking/decide-first-come-first-serve requests))))))

  (testing "returns nil for empty list"
    (is (nil? (booking/decide-first-come-first-serve [])))))

(deftest decide-by-priority-test
  (testing "picks highest priority"
    (let [requests [{:tenant-name "Val" :priority 5}
                    {:tenant-name "Flo" :priority 10}
                    {:tenant-name "Aaron" :priority 3}]]
      (is (= "Flo" (:tenant-name (booking/decide-by-priority requests))))))

  (testing "returns nil for empty list"
    (is (nil? (booking/decide-by-priority [])))))

(deftest decide-random-lottery-test
  (testing "returns one of the requests"
    (let [requests [{:tenant-name "Val"} {:tenant-name "Flo"}]
          result (booking/decide-random-lottery requests)]
      (is (some #(= % result) requests))))

  (testing "returns nil for empty list"
    (is (nil? (booking/decide-random-lottery [])))))

;; =============================================================================
;; XTDB Integration Tests
;; =============================================================================

(deftest create-booking-test
  (testing "creates a booking with pending status"
    (let [booking-id (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")
          booking (booking/get-booking *test-node* booking-id)]
      (is (uuid? booking-id))
      (is (= "Flo" (:tenant-name booking)))
      (is (= :pending (:status booking)))
      (is (= #{} (:approvals booking))))))

(deftest approve-booking-test
  (testing "adds approver to approvals set"
    (let [booking-id (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")]
      (booking/approve-booking! *test-node* booking-id "Val")
      (booking/approve-booking! *test-node* booking-id "Aaron")
      (let [booking (booking/get-booking *test-node* booking-id)]
        (is (= #{"Val" "Aaron"} (:approvals booking)))))))

(deftest confirm-booking-test
  (testing "transitions status to confirmed"
    (let [booking-id (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")]
      (booking/confirm-booking! *test-node* booking-id)
      (is (= :confirmed (:status (booking/get-booking *test-node* booking-id)))))))

(deftest cancel-booking-test
  (testing "transitions status to cancelled with reason"
    (let [booking-id (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")]
      (booking/cancel-booking! *test-node* booking-id "Changed plans")
      (let [booking (booking/get-booking *test-node* booking-id)]
        (is (= :cancelled (:status booking)))
        (is (= "Changed plans" (:cancellation-reason booking)))))))

(deftest find-conflicts-overlap-test
  (testing "detects overlapping bookings"
    (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-03T00:00:00Z")

    ;; Partial overlap from left
    (is (seq (booking/find-conflicts *test-node*
                                     (instant "2026-02-28T00:00:00Z")
                                     (instant "2026-03-02T00:00:00Z"))))

    ;; Partial overlap from right
    (is (seq (booking/find-conflicts *test-node*
                                     (instant "2026-03-02T00:00:00Z")
                                     (instant "2026-03-04T00:00:00Z"))))

    ;; Contained within existing
    (is (seq (booking/find-conflicts *test-node*
                                     (instant "2026-03-01T12:00:00Z")
                                     (instant "2026-03-02T12:00:00Z"))))

    ;; Contains existing (superset)
    (is (seq (booking/find-conflicts *test-node*
                                     (instant "2026-02-15T00:00:00Z")
                                     (instant "2026-03-15T00:00:00Z"))))))

(deftest find-conflicts-disjoint-test
  (testing "no conflict when disjoint"
    (make-booking! "Flo" "2026-03-01T00:00:00Z" "2026-03-03T00:00:00Z")

    ;; Before
    (is (empty? (booking/find-conflicts *test-node*
                                        (instant "2026-02-01T00:00:00Z")
                                        (instant "2026-02-15T00:00:00Z"))))

    ;; After
    (is (empty? (booking/find-conflicts *test-node*
                                        (instant "2026-03-10T00:00:00Z")
                                        (instant "2026-03-15T00:00:00Z"))))))

(deftest find-conflicts-cancelled-test
  (testing "ignores cancelled bookings"
    (let [booking-id (make-booking! "Flo" "2026-04-01T00:00:00Z" "2026-04-03T00:00:00Z")]
      (booking/cancel-booking! *test-node* booking-id "Cancelled")
      (is (empty? (booking/find-conflicts *test-node*
                                          (instant "2026-04-01T00:00:00Z")
                                          (instant "2026-04-03T00:00:00Z")))))))

(deftest try-book-test
  (testing "succeeds when slot is available"
    (let [result (booking/try-book! *test-node*
                                    {:tenant-name "Flo"
                                     :start-date (instant "2026-05-01T00:00:00Z")
                                     :end-date (instant "2026-05-02T00:00:00Z")})]
      (is (:ok result))
      (is (uuid? (:ok result)))))

  (testing "fails when slot is taken"
    (make-booking! "Flo" "2026-06-01T00:00:00Z" "2026-06-03T00:00:00Z")
    (let [result (booking/try-book! *test-node*
                                    {:tenant-name "Val"
                                     :start-date (instant "2026-06-02T00:00:00Z")
                                     :end-date (instant "2026-06-04T00:00:00Z")})]
      (is (:conflict result))
      (is (seq (:conflict result))))))

(deftest booking-history-test
  (testing "tracks all state changes"
    (let [booking-id (make-booking! "Flo" "2026-07-01T00:00:00Z" "2026-07-02T00:00:00Z")]
      (booking/approve-booking! *test-node* booking-id "Val")
      (booking/confirm-booking! *test-node* booking-id)
      (let [history (booking/booking-history *test-node* booking-id)]
        ;; Should have multiple versions
        (is (>= (count history) 3))
        ;; Should include different states
        (is (some #(= :pending (:status %)) history))
        (is (some #(= :confirmed (:status %)) history))))))
