(ns tilda.api-test
  "End-to-end HTTP tests for the Tilda booking API.
   
   These tests start a real HTTP server on a random port and exercise
   the full request/response cycle through the API endpoints."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cheshire.core :as json]
   [org.httpkit.server :as http-kit]
   [tilda.routes :as routes]
   [xtdb.node :as xtn])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; =============================================================================
;; Test State
;; =============================================================================

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)
(def ^:dynamic *node* nil)

;; =============================================================================
;; HTTP Client Helpers
;; =============================================================================

(def http-client (HttpClient/newHttpClient))

(defn base-url []
  (str "http://localhost:" *port*))

(defn parse-json
  "Parse JSON response body into Clojure data."
  [body]
  (when (and body (seq body))
    (json/parse-string body true)))

(defn http-get
  "Perform HTTP GET request. Returns {:status ... :body ... :json ...}"
  [path]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (base-url) path)))
                    (.GET)
                    (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    {:status (.statusCode response)
     :body body
     :json (try (parse-json body) (catch Exception _ nil))}))

(defn http-post
  "Perform HTTP POST request with JSON body. Returns {:status ... :body ... :json ...}"
  [path data]
  (let [json-body (json/generate-string data)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (base-url) path)))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString json-body))
                    (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    {:status (.statusCode response)
     :body body
     :json (try (parse-json body) (catch Exception _ nil))}))

(defn http-delete
  "Perform HTTP DELETE request. Returns {:status ... :body ...}"
  [path]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (base-url) path)))
                    (.DELETE)
                    (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn find-free-port
  "Find an available random port."
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn with-test-server
  "Fixture that starts a fresh server with in-memory XTDB for each test."
  [f]
  (let [port (find-free-port)
        node (xtn/start-node)
        handler (routes/handler node)
        server (http-kit/run-server handler {:port port})]
    (try
      (binding [*server* server
                *port* port
                *node* node]
        (f))
      (finally
        (server)  ; http-kit returns a stop fn
        (.close node)))))

(use-fixtures :each with-test-server)

;; =============================================================================
;; Helper Functions for Tests
;; =============================================================================

(defn create-test-request!
  "Create a booking request via API. Returns the request-id."
  [tenant-name start-date end-date & {:keys [priority] :or {priority 0}}]
  (let [resp (http-post "/requests"
                        {:tenant-name tenant-name
                         :start-date start-date
                         :end-date end-date
                         :priority priority})]
    (when (= 201 (:status resp))
      (:request-id (:json resp)))))

(defn resolve-requests!
  "Resolve competing requests via API. Returns resolution result."
  [start-date end-date & {:keys [decider] :or {decider "fcfs"}}]
  (let [resp (http-post "/requests/resolve"
                        {:start-date start-date
                         :end-date end-date
                         :decider decider})]
    (:json resp)))

;; =============================================================================
;; Tests: GET /bookings - List Bookings
;; =============================================================================

(deftest get-bookings-empty-test
  (testing "GET /bookings returns empty array when no bookings exist"
    (let [resp (http-get "/bookings")]
      (is (= 200 (:status resp)))
      (is (= [] (:json resp))))))

(deftest get-bookings-with-data-test
  (testing "GET /bookings returns bookings after requests are resolved"
    ;; Create and resolve a request to generate a booking
    (create-test-request! "Alice" "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")
    (resolve-requests! "2026-03-01T00:00:00Z" "2026-03-02T00:00:00Z")

    (let [resp (http-get "/bookings")]
      (is (= 200 (:status resp)))
      (is (= 1 (count (:json resp))))
      (is (= "Alice" (:tenant-name (first (:json resp))))))))

;; =============================================================================
;; Tests: GET /requests - List Pending Requests
;; =============================================================================

(deftest get-requests-empty-test
  (testing "GET /requests returns empty array initially"
    (let [resp (http-get "/requests")]
      (is (= 200 (:status resp)))
      (is (= [] (:json resp))))))

(deftest get-requests-with-pending-test
  (testing "GET /requests returns pending requests"
    (create-test-request! "Bob" "2026-04-01T00:00:00Z" "2026-04-02T00:00:00Z")

    (let [resp (http-get "/requests")]
      (is (= 200 (:status resp)))
      (is (= 1 (count (:json resp))))
      (is (= "Bob" (:tenant-name (first (:json resp)))))
      (is (= "pending" (name (:status (first (:json resp)))))))))

;; =============================================================================
;; Tests: POST /requests - Create Request
;; =============================================================================

(deftest post-requests-creates-request-test
  (testing "POST /requests creates a new pending request"
    (let [resp (http-post "/requests"
                          {:tenant-name "Charlie"
                           :start-date "2026-05-01T00:00:00Z"
                           :end-date "2026-05-02T00:00:00Z"
                           :priority 5})]
      (is (= 201 (:status resp)))
      (is (string? (:request-id (:json resp))))
      (is (uuid? (parse-uuid (:request-id (:json resp))))))

    ;; Verify request appears in list
    (let [resp (http-get "/requests")]
      (is (= 1 (count (:json resp))))
      (is (= "Charlie" (:tenant-name (first (:json resp))))))))

(deftest post-requests-multiple-test
  (testing "POST /requests can create multiple competing requests"
    (create-test-request! "Dan" "2026-06-01T00:00:00Z" "2026-06-02T00:00:00Z" :priority 3)
    (create-test-request! "Eve" "2026-06-01T00:00:00Z" "2026-06-02T00:00:00Z" :priority 7)

    (let [resp (http-get "/requests")]
      (is (= 2 (count (:json resp)))))))

;; =============================================================================
;; Tests: POST /requests/resolve - Resolve Requests
;; =============================================================================

(deftest post-requests-resolve-fcfs-test
  (testing "POST /requests/resolve with first-come-first-serve creates booking for earliest requester"
    ;; Note: timestamps are set by the server, so we can't control ordering easily
    ;; But we can test that resolution works
    (create-test-request! "Frank" "2026-07-01T00:00:00Z" "2026-07-02T00:00:00Z")

    (let [resolve-resp (resolve-requests! "2026-07-01T00:00:00Z" "2026-07-02T00:00:00Z" :decider "fcfs")]
      (is (some? (:booking-id resolve-resp)))
      (is (some? (:winner resolve-resp))))

    ;; Verify booking was created
    (let [bookings-resp (http-get "/bookings")]
      (is (= 1 (count (:json bookings-resp))))
      (is (= "Frank" (:tenant-name (first (:json bookings-resp))))))))

(deftest post-requests-resolve-by-priority-test
  (testing "POST /requests/resolve with priority decider picks highest priority"
    (create-test-request! "Grace" "2026-08-01T00:00:00Z" "2026-08-02T00:00:00Z" :priority 1)
    (create-test-request! "Hank" "2026-08-01T00:00:00Z" "2026-08-02T00:00:00Z" :priority 10)
    (create-test-request! "Ivy" "2026-08-01T00:00:00Z" "2026-08-02T00:00:00Z" :priority 5)

    (let [resolve-resp (resolve-requests! "2026-08-01T00:00:00Z" "2026-08-02T00:00:00Z" :decider "priority")]
      (is (some? (:booking-id resolve-resp)))
      ;; Should have 2 rejected requests
      (is (= 2 (count (:rejected resolve-resp)))))

    ;; Hank should win (highest priority)
    (let [bookings-resp (http-get "/bookings")]
      (is (= "Hank" (:tenant-name (first (:json bookings-resp))))))))

(deftest post-requests-resolve-no-conflicts-test
  (testing "POST /requests/resolve with no matching requests returns error"
    (let [resp (http-post "/requests/resolve"
                          {:start-date "2099-01-01T00:00:00Z"
                           :end-date "2099-01-02T00:00:00Z"
                           :decider "fcfs"})]
      (is (= 404 (:status resp)))
      (is (= "No pending requests found for this slot" (:error (:json resp)))))))

;; =============================================================================
;; Tests: GET /bookings/:id - Get Single Booking
;; =============================================================================

(deftest get-booking-by-id-test
  (testing "GET /bookings/:id returns a specific booking"
    (create-test-request! "Jack" "2026-09-01T00:00:00Z" "2026-09-02T00:00:00Z")
    (let [resolve-resp (resolve-requests! "2026-09-01T00:00:00Z" "2026-09-02T00:00:00Z")
          booking-id (:booking-id resolve-resp)]

      (let [resp (http-get (str "/bookings/" booking-id))]
        (is (= 200 (:status resp)))
        (is (= "Jack" (:tenant-name (:json resp))))
        (is (= booking-id (str (:xt/id (:json resp)))))))))

(deftest get-booking-not-found-test
  (testing "GET /bookings/:id returns 404 for non-existent booking"
    (let [fake-id (random-uuid)
          resp (http-get (str "/bookings/" fake-id))]
      (is (= 404 (:status resp))))))

;; =============================================================================
;; Tests: DELETE /bookings/:id - Cancel Booking
;; =============================================================================

(deftest delete-booking-test
  (testing "DELETE /bookings/:id cancels a booking"
    (create-test-request! "Kate" "2026-10-01T00:00:00Z" "2026-10-02T00:00:00Z")
    (let [resolve-resp (resolve-requests! "2026-10-01T00:00:00Z" "2026-10-02T00:00:00Z")
          booking-id (:booking-id resolve-resp)]

      ;; Verify booking exists
      (let [resp (http-get (str "/bookings/" booking-id))]
        (is (= 200 (:status resp))))

      ;; Delete it
      (let [delete-resp (http-delete (str "/bookings/" booking-id))]
        (is (= 204 (:status delete-resp))))

      ;; Verify it's gone
      (let [resp (http-get (str "/bookings/" booking-id))]
        (is (= 404 (:status resp)))))))

(deftest delete-booking-removes-from-list-test
  (testing "DELETE /bookings/:id removes booking from list"
    (create-test-request! "Leo" "2026-11-01T00:00:00Z" "2026-11-02T00:00:00Z")
    (let [resolve-resp (resolve-requests! "2026-11-01T00:00:00Z" "2026-11-02T00:00:00Z")
          booking-id (:booking-id resolve-resp)]

      ;; Verify it's in the list
      (let [resp (http-get "/bookings")]
        (is (= 1 (count (:json resp)))))

      ;; Delete it
      (http-delete (str "/bookings/" booking-id))

      ;; Verify list is empty
      (let [resp (http-get "/bookings")]
        (is (= 0 (count (:json resp))))))))

;; =============================================================================
;; Tests: GET /bookings/:id/history - Booking History (Audit Trail)
;; =============================================================================

(deftest get-booking-history-test
  (testing "GET /bookings/:id/history shows audit trail"
    (create-test-request! "Mia" "2026-12-01T00:00:00Z" "2026-12-02T00:00:00Z")
    (let [resolve-resp (resolve-requests! "2026-12-01T00:00:00Z" "2026-12-02T00:00:00Z")
          booking-id (:booking-id resolve-resp)]

      ;; History should have at least one entry (the creation)
      (let [resp (http-get (str "/bookings/" booking-id "/history"))]
        (is (= 200 (:status resp)))
        (is (seq (:json resp)))
        (is (= "Mia" (:tenant-name (first (:json resp)))))))))

(deftest get-booking-history-after-cancel-test
  (testing "GET /bookings/:id/history shows history even after cancellation"
    (create-test-request! "Nina" "2027-01-01T00:00:00Z" "2027-01-02T00:00:00Z")
    (let [resolve-resp (resolve-requests! "2027-01-01T00:00:00Z" "2027-01-02T00:00:00Z")
          booking-id (:booking-id resolve-resp)]

      ;; Delete the booking
      (http-delete (str "/bookings/" booking-id))

      ;; Booking itself is gone
      (is (= 404 (:status (http-get (str "/bookings/" booking-id)))))

      ;; But history persists (XTDB bitemporality)
      (let [resp (http-get (str "/bookings/" booking-id "/history"))]
        (is (= 200 (:status resp)))
        (is (seq (:json resp)))
        (is (= "Nina" (:tenant-name (first (:json resp)))))))))

;; =============================================================================
;; Tests: GET / - Home Page
;; =============================================================================

(deftest get-home-page-test
  (testing "GET / returns HTML home page"
    (let [resp (http-get "/")]
      (is (= 200 (:status resp)))
      (is (.contains (:body resp) "<!DOCTYPE html"))
      (is (.contains (:body resp) "Tilda")))))

;; =============================================================================
;; Integration Tests - Full Workflow
;; =============================================================================

(deftest full-booking-lifecycle-test
  (testing "Complete booking lifecycle: request → resolve → view → cancel → history"
    ;; Step 1: Create competing requests
    (let [r1-id (create-test-request! "Oscar" "2027-02-01T00:00:00Z" "2027-02-03T00:00:00Z" :priority 5)
          r2-id (create-test-request! "Paula" "2027-02-01T00:00:00Z" "2027-02-03T00:00:00Z" :priority 10)]

      (is (string? r1-id))
      (is (string? r2-id))

      ;; Step 2: Verify both are pending
      (let [requests (:json (http-get "/requests"))]
        (is (= 2 (count requests)))
        (is (every? #(= "pending" (name (:status %))) requests)))

      ;; Step 3: Resolve by priority - Paula should win
      (let [resolve-resp (resolve-requests! "2027-02-01T00:00:00Z" "2027-02-03T00:00:00Z" :decider "priority")
            booking-id (:booking-id resolve-resp)]

        (is (some? booking-id))
        (is (= 1 (count (:rejected resolve-resp))))

        ;; Step 4: Verify booking exists and has correct data
        (let [booking (:json (http-get (str "/bookings/" booking-id)))]
          (is (= "Paula" (:tenant-name booking))))

        ;; Step 5: Verify bookings list has one entry
        (let [bookings (:json (http-get "/bookings"))]
          (is (= 1 (count bookings))))

        ;; Step 6: Cancel the booking
        (let [delete-resp (http-delete (str "/bookings/" booking-id))]
          (is (= 204 (:status delete-resp))))

        ;; Step 7: Verify booking is gone
        (is (= 0 (count (:json (http-get "/bookings")))))

        ;; Step 8: But history remains
        (let [history (:json (http-get (str "/bookings/" booking-id "/history")))]
          (is (seq history))
          (is (= "Paula" (:tenant-name (first history)))))))))
