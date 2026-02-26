(ns tilda.views
  "Hiccup templates + Datastar"
  (:require [hiccup2.core :as h]))




(defn render [hiccup]
  (str "<!DOCTYPE html>" (h/html hiccup)))

;; =============================================================================
;; Layout
;; =============================================================================

(defn layout [& body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Tilda - Booking"]
    ;; Datastar - server-driven reactivity
    [:script {:type "module"
              :src "https://cdn.jsdelivr.net/npm/@starfederation/datastar@1.0.0-beta.11/bundles/datastar.js"}]
    [:style (str "body { font-family: system-ui; max-width: 800px; margin: 2rem auto; padding: 0 1rem; }"
                 "table { width: 100%; border-collapse: collapse; }"
                 "th, td { padding: 0.5rem; text-align: left; border-bottom: 1px solid #ddd; }"
                 ".pending { background: #fff3cd; }"
                 ".confirmed { background: #d4edda; }"
                 "button { cursor: pointer; }")]]
   [:body body]])

;; =============================================================================
;; Components
;; =============================================================================

(defn booking-row [{:keys [xt/id tenant-name start-date end-date]}]
  [:tr.confirmed
   [:td tenant-name]
   [:td (str start-date)]
   [:td (str end-date)]
   [:td [:button {:data-on-click (str "$$delete('/bookings/" id "')")} "Cancel"]]])

(defn request-row [{:keys [tenant-name start-date end-date status]}]
  [:tr.pending
   [:td tenant-name]
   [:td (str start-date)]
   [:td (str end-date)]
   [:td (name status)]])

;; =============================================================================
;; Pages
;; =============================================================================

(defn home [bookings requests]
  (layout
   [:h1 "Tilda Booking"]

   [:h2 "Confirmed Bookings"]
   (if (seq bookings)
     [:table
      [:thead [:tr [:th "Tenant"] [:th "Start"] [:th "End"] [:th "Action"]]]
      [:tbody (map booking-row bookings)]]
     [:p "No bookings yet."])

   [:h2 "Pending Requests"]
   (if (seq requests)
     [:table
      [:thead [:tr [:th "Tenant"] [:th "Start"] [:th "End"] [:th "Status"]]]
      [:tbody (map request-row requests)]]
     [:p "No pending requests."])))
