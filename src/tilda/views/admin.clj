(ns tilda.views.admin
  "Admin UI for conflict resolution.
   
   ## Overview
   
   Displays pending requests grouped by overlapping date ranges.
   Admins can pick a specific winner or use a decider algorithm."
  (:require [tilda.views.layout :as layout])
  (:import [java.time.format DateTimeFormatter]
           [java.time ZoneId Instant]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- format-date [instant]
  (when instant
    (let [fmt (DateTimeFormatter/ofPattern "MMM d, yyyy")
          zoned (.atZone instant (ZoneId/of "UTC"))]
      (.format fmt zoned))))

(defn- format-datetime [instant]
  (when instant
    (let [fmt (DateTimeFormatter/ofPattern "MMM d HH:mm")
          zoned (.atZone instant (ZoneId/of "UTC"))]
      (.format fmt zoned))))

(defn- overlaps?
  "Check if two date ranges overlap."
  [{s1 :start-date e1 :end-date} {s2 :start-date e2 :end-date}]
  (and (<= (.compareTo s1 e2) 0)
       (>= (.compareTo e1 s2) 0)))

(defn- find-conflict-groups
  "Group requests into overlapping conflict sets.
   Each group is a vector of requests that all overlap with each other."
  [requests]
  (loop [remaining (vec requests)
         groups []]
    (if (empty? remaining)
      groups
      (let [seed (first remaining)
            ;; Find all requests overlapping with seed
            [in-group out-group] 
            (loop [to-check (rest remaining)
                   in-group [seed]
                   out-group []]
              (if (empty? to-check)
                [in-group out-group]
                (let [candidate (first to-check)]
                  (if (some #(overlaps? candidate %) in-group)
                    (recur (rest to-check)
                           (conj in-group candidate)
                           out-group)
                    (recur (rest to-check)
                           in-group
                           (conj out-group candidate))))))]
        (recur out-group
               (if (> (count in-group) 1)
                 (conj groups in-group)
                 groups))))))

;; =============================================================================
;; Components
;; =============================================================================

(defn- request-card
  "Card for a single request with 'Pick Winner' button."
  [{:keys [xt/id tenant-name start-date end-date requested-at]} group-start group-end]
  [:div {:style "border: 1px solid #ddd; padding: 0.75rem; margin: 0.5rem 0; border-radius: 4px; display: flex; justify-content: space-between; align-items: center;"}
   [:div
    [:strong tenant-name]
    [:br]
    [:small {:style "color: #666;"}
     (format-date start-date) " - " (format-date end-date)]
    (when requested-at
      [:small {:style "color: #999; margin-left: 1rem;"}
       "Requested: " (format-datetime requested-at)])]
   [:button
    {:data-on-click (str "$$post('/admin/resolve', {body: JSON.stringify({winner: '" id "', start: '" group-start "', end: '" group-end "'})})")
     :style "background: #28a745; color: white; border: none; padding: 0.5rem 1rem; border-radius: 4px;"}
    "Pick Winner"]])

(defn- conflict-group-card
  "Card showing a group of conflicting requests."
  [requests idx]
  (let [all-starts (map :start-date requests)
        all-ends (map :end-date requests)
        earliest (reduce (fn [a b] (if (neg? (.compareTo a b)) a b)) all-starts)
        latest (reduce (fn [a b] (if (pos? (.compareTo a b)) a b)) all-ends)]
    [:article {:style "background: #fff3cd; border-radius: 8px; padding: 1rem; margin-bottom: 1.5rem;"}
     [:header {:style "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"}
      [:div
       [:h3 {:style "margin: 0;"} 
        (str "Conflict #" (inc idx) " - " (count requests) " requests")]
       [:small {:style "color: #856404;"} 
        (format-date earliest) " to " (format-date latest)]]
      [:div {:style "display: flex; gap: 0.5rem;"}
       [:button
        {:data-on-click (str "$$post('/admin/resolve', {body: JSON.stringify({decider: 'fcfs', start: '" earliest "', end: '" latest "'})})")
         :style "background: #6c757d; color: white; border: none; padding: 0.5rem; border-radius: 4px; font-size: 0.8rem;"}
        "First Come First Serve"]
       [:button
        {:data-on-click (str "$$post('/admin/resolve', {body: JSON.stringify({decider: 'lottery', start: '" earliest "', end: '" latest "'})})")
         :style "background: #6c757d; color: white; border: none; padding: 0.5rem; border-radius: 4px; font-size: 0.8rem;"}
        "Lottery"]]]
     
     [:div
      (for [req (sort-by :requested-at requests)]
        (request-card req earliest latest))]]))

(defn- no-conflicts-message []
  [:article {:style "background: #d4edda; border-radius: 8px; padding: 2rem; text-align: center;"}
   [:h3 {:style "margin: 0; color: #155724;"} "No conflicts!"]
   [:p {:style "margin: 0.5rem 0 0 0; color: #155724;"} 
    "All pending requests are for non-overlapping time slots."]])

;; =============================================================================
;; Pages
;; =============================================================================

(defn admin-page
  "Main admin page showing all conflicts."
  [requests]
  (let [conflict-groups (find-conflict-groups requests)]
    (layout/layout
     [:div {:style "margin-top: 1rem;"
            :data-on-load "$$get('/admin/conflicts')"}
      
      [:header {:style "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem;"}
       [:h1 {:style "margin: 0;"} "Admin - Conflicts"]
       [:a {:href "/calendar" :style "text-decoration: none;"} "← Back to Calendar"]]
      
      [:div {:id "conflicts"}
       (if (seq conflict-groups)
         [:div
          [:p {:style "color: #856404; background: #fff3cd; padding: 0.75rem; border-radius: 4px;"}
           (str (count conflict-groups) " conflict" 
                (when (> (count conflict-groups) 1) "s")
                " need resolution")]
          (map-indexed (fn [idx group] (conflict-group-card group idx)) 
                       conflict-groups)]
         (no-conflicts-message))]])))

(defn conflicts-fragment
  "SSE fragment for conflict list updates."
  [requests]
  (let [conflict-groups (find-conflict-groups requests)]
    (if (seq conflict-groups)
      [:div
       [:p {:style "color: #856404; background: #fff3cd; padding: 0.75rem; border-radius: 4px;"}
        (str (count conflict-groups) " conflict"
             (when (> (count conflict-groups) 1) "s")
             " need resolution")]
       (map-indexed (fn [idx group] (conflict-group-card group idx))
                    conflict-groups)]
      (no-conflicts-message))))
