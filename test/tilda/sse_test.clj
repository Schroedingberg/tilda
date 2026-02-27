(ns tilda.sse-test
  "Tests for the SSE pub/sub infrastructure."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [tilda.sse :as sse]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-channels [f]
  (sse/reset-channels!)
  (f)
  (sse/reset-channels!))

(use-fixtures :each reset-channels)

;; =============================================================================
;; Message Formatting Tests
;; =============================================================================

(deftest format-datastar-fragment-test
  (testing "formats HTML as Datastar patch-elements event"
    (let [html "<div id=\"day-2026-03-01\">1</div>"
          result (sse/format-datastar-fragment html)]
      (is (str/starts-with? result "event: datastar-patch-elements\n"))
      (is (str/includes? result "data: elements "))
      (is (str/includes? result html))
      (is (str/ends-with? result "\n\n"))))

  (testing "handles empty HTML"
    (let [result (sse/format-datastar-fragment "")]
      (is (= "event: datastar-patch-elements\ndata: elements \n\n" result))))

  (testing "handles HTML with newlines"
    (let [html "<div>\n  <span>test</span>\n</div>"
          result (sse/format-datastar-fragment html)]
      (is (str/includes? result html)))))

;; =============================================================================
;; Channel Registry Tests
;; =============================================================================

(deftest channel-count-test
  (testing "starts at zero"
    (is (= 0 (sse/channel-count)))))

(deftest reset-channels-test
  (testing "reset clears all channels"
    ;; We can't easily add channels without http-kit, but we can verify
    ;; reset returns the count to zero
    (is (= 0 (sse/channel-count)))
    (sse/reset-channels!)
    (is (= 0 (sse/channel-count)))))

;; =============================================================================
;; Handler Tests
;; =============================================================================

(deftest sse-handler-test
  (testing "returns a function"
    (let [handler (sse/sse-handler)]
      (is (fn? handler))))

  (testing "accepts options map"
    (let [handler (sse/sse-handler {:on-connect (fn [_] :connected)})]
      (is (fn? handler))))

  (testing "accepts empty options"
    (let [handler (sse/sse-handler {})]
      (is (fn? handler)))))

;; =============================================================================
;; Broadcast Tests (unit level - no actual channels)
;; =============================================================================

(deftest broadcast-with-no-channels-test
  (testing "broadcast! with no connected clients doesn't throw"
    (is (nil? (sse/broadcast! "<div>test</div>")))))

(deftest broadcast-raw-with-no-channels-test
  (testing "broadcast-raw! with no connected clients doesn't throw"
    (is (nil? (sse/broadcast-raw! "event: test\ndata: hello\n\n")))))
