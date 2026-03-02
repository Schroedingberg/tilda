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
;; Date Comparison Tests (pure)
;; =============================================================================

(deftest before-or-equal?-test
  (let [t1 (instant "2026-03-01T00:00:00Z")
        t2 (instant "2026-03-02T00:00:00Z")]
    (is (b/before-or-equal? t1 t2))
    (is (b/before-or-equal? t1 t1))
    (is (not (b/before-or-equal? t2 t1)))))

(deftest after-or-equal?-test
  (let [t1 (instant "2026-03-01T00:00:00Z")
        t2 (instant "2026-03-02T00:00:00Z")]
    (is (b/after-or-equal? t2 t1))
    (is (b/after-or-equal? t1 t1))
    (is (not (b/after-or-equal? t1 t2)))))

(deftest overlaps?-test
  ;; [1--3] overlaps [2--4]
  (is (b/overlaps? (instant "2026-03-01T00:00:00Z") (instant "2026-03-03T00:00:00Z")
                   (instant "2026-03-02T00:00:00Z") (instant "2026-03-04T00:00:00Z")))
  ;; [1--2] overlaps [2--3] (touching)
  (is (b/overlaps? (instant "2026-03-01T00:00:00Z") (instant "2026-03-02T00:00:00Z")
                   (instant "2026-03-02T00:00:00Z") (instant "2026-03-03T00:00:00Z")))
  ;; [1--2] does not overlap [3--4]
  (is (not (b/overlaps? (instant "2026-03-01T00:00:00Z") (instant "2026-03-02T00:00:00Z")
                        (instant "2026-03-03T00:00:00Z") (instant "2026-03-04T00:00:00Z"))))
  ;; [3--4] does not overlap [1--2] (reverse)
  (is (not (b/overlaps? (instant "2026-03-03T00:00:00Z") (instant "2026-03-04T00:00:00Z")
                        (instant "2026-03-01T00:00:00Z") (instant "2026-03-02T00:00:00Z"))))
  ;; [1--4] contains [2--3]
  (is (b/overlaps? (instant "2026-03-01T00:00:00Z") (instant "2026-03-04T00:00:00Z")
                   (instant "2026-03-02T00:00:00Z") (instant "2026-03-03T00:00:00Z"))))

;; =============================================================================
;; Pure Logic Tests (no DB required)
;; =============================================================================

(deftest find-overlapping-request-logic-test
  (let [existing [{:xt/id #uuid "00000000-0000-0000-0000-000000000001"
                   :tenant-name "Flo"
                   :start-date (instant "2026-03-01T00:00:00Z")
                   :end-date (instant "2026-03-03T00:00:00Z")}
                  {:xt/id #uuid "00000000-0000-0000-0000-000000000002"
                   :tenant-name "Val"
                   :start-date (instant "2026-03-05T00:00:00Z")
                   :end-date (instant "2026-03-07T00:00:00Z")}]]
    ;; Overlapping request for same tenant
    (is (= #uuid "00000000-0000-0000-0000-000000000001"
           (:xt/id (b/find-overlapping-request-logic
                    existing "Flo"
                    (instant "2026-03-02T00:00:00Z")
                    (instant "2026-03-04T00:00:00Z")))))
    ;; Same time range but different tenant - no overlap
    (is (nil? (b/find-overlapping-request-logic
               existing "Kai"
               (instant "2026-03-02T00:00:00Z")
               (instant "2026-03-04T00:00:00Z"))))
    ;; No overlap in time
    (is (nil? (b/find-overlapping-request-logic
               existing "Flo"
               (instant "2026-04-01T00:00:00Z")
               (instant "2026-04-02T00:00:00Z"))))))

(deftest build-request-doc-test
  (let [doc (b/build-request-doc {:request-id #uuid "00000000-0000-0000-0000-000000000001"
                                  :tenant-name "Flo"
                                  :start-date (instant "2026-03-01T00:00:00Z")
                                  :end-date (instant "2026-03-02T00:00:00Z")
                                  :priority 5
                                  :requested-at (instant "2026-02-28T10:00:00Z")})]
    (is (= #uuid "00000000-0000-0000-0000-000000000001" (:xt/id doc)))
    (is (= "Flo" (:tenant-name doc)))
    (is (= 5 (:priority doc))))
  ;; Default priority to 0
  (is (= 0 (:priority (b/build-request-doc {:request-id (random-uuid)
                                            :tenant-name "Flo"
                                            :start-date (instant "2026-03-01T00:00:00Z")
                                            :end-date (instant "2026-03-02T00:00:00Z")
                                            :priority nil
                                            :requested-at (instant "2026-02-28T10:00:00Z")})))))

(deftest find-conflicts-logic-test
  (let [bookings [{:xt/id #uuid "00000000-0000-0000-0000-000000000001"
                   :tenant-name "Flo"
                   :start-date (instant "2026-03-01T00:00:00Z")
                   :end-date (instant "2026-03-03T00:00:00Z")}
                  {:xt/id #uuid "00000000-0000-0000-0000-000000000002"
                   :tenant-name "Val"
                   :start-date (instant "2026-03-05T00:00:00Z")
                   :end-date (instant "2026-03-07T00:00:00Z")}]]
    ;; Overlapping with first booking
    (is (= 1 (count (b/find-conflicts-logic
                     bookings
                     (instant "2026-03-02T00:00:00Z")
                     (instant "2026-03-04T00:00:00Z")))))
    ;; No overlap
    (is (empty? (b/find-conflicts-logic
                 bookings
                 (instant "2026-04-01T00:00:00Z")
                 (instant "2026-04-02T00:00:00Z"))))
    ;; Filter by exclude-id
    (is (empty? (b/find-conflicts-logic
                 bookings
                 (instant "2026-03-02T00:00:00Z")
                 (instant "2026-03-04T00:00:00Z")
                 :exclude-id #uuid "00000000-0000-0000-0000-000000000001")))))

(deftest build-booking-doc-test
  (let [winner {:xt/id #uuid "00000000-0000-0000-0000-000000000001"
                :tenant-name "Flo"
                :start-date (instant "2026-03-01T00:00:00Z")
                :end-date (instant "2026-03-02T00:00:00Z")}
        booking-id #uuid "00000000-0000-0000-0000-000000000099"
        doc (b/build-booking-doc booking-id winner)]
    (is (= booking-id (:xt/id doc)))
    (is (= #uuid "00000000-0000-0000-0000-000000000001" (:request-id doc)))
    (is (= "Flo" (:tenant-name doc)))
    (is (= (instant "2026-03-01T00:00:00Z") (:start-date doc)))
    (is (= (instant "2026-03-02T00:00:00Z") (:end-date doc)))))

(deftest resolve-slot-logic-test
  (let [requests [{:xt/id #uuid "00000000-0000-0000-0000-000000000001"
                   :tenant-name "Flo" :priority 5
                   :start-date (instant "2026-03-01T00:00:00Z")
                   :end-date (instant "2026-03-02T00:00:00Z")}
                  {:xt/id #uuid "00000000-0000-0000-0000-000000000002"
                   :tenant-name "Val" :priority 10
                   :start-date (instant "2026-03-01T00:00:00Z")
                   :end-date (instant "2026-03-02T00:00:00Z")}]
        booking-id #uuid "00000000-0000-0000-0000-000000000099"
        result (b/resolve-slot-logic requests b/decide-by-priority booking-id)]
    ;; Val wins (higher priority)
    (is (= "Val" (:tenant-name (:winner result))))
    (is (= 1 (count (:losers result))))
    (is (= "Flo" (:tenant-name (first (:losers result)))))
    ;; Booking doc is correct
    (is (= booking-id (:xt/id (:booking-doc result))))
    (is (= "Val" (:tenant-name (:booking-doc result))))
    ;; All request IDs marked for deletion
    (is (= 2 (count (:delete-request-ids result)))))
  ;; Empty requests returns nil
  (is (nil? (b/resolve-slot-logic [] b/decide-by-priority (random-uuid)))))

(deftest build-resolution-tx-test
  (let [result {:booking-doc {:xt/id #uuid "00000000-0000-0000-0000-000000000099"
                              :tenant-name "Val"
                              :start-date (instant "2026-03-01T00:00:00Z")
                              :end-date (instant "2026-03-02T00:00:00Z")}
                :delete-request-ids [#uuid "00000000-0000-0000-0000-000000000001"
                                     #uuid "00000000-0000-0000-0000-000000000002"]}
        tx (b/build-resolution-tx result)]
    (is (= 3 (count tx)))
    ;; First op is put booking
    (is (= :put-docs (first (first tx))))
    (is (= :bookings (second (first tx))))
    ;; Rest are deletes
    (is (every? #(= :delete-docs (first %)) (rest tx)))))

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


