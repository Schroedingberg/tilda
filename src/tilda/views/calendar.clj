(ns tilda.views.calendar
  "Calendar UI components - month grid with drag selection"
  (:import [java.time LocalDate YearMonth ZoneId Instant]))

;; =============================================================================
;; Date Helpers
;; =============================================================================

(defn- to-local-date
  "Convert various date types to LocalDate."
  [dt]
  (cond
    (instance? LocalDate dt) dt
    (instance? Instant dt) (.toLocalDate (.atZone dt (ZoneId/of "UTC")))
    :else (.toLocalDate dt)))

(defn- month-days
  "All days in a month as LocalDate seq."
  [^YearMonth ym]
  (let [start (.atDay ym 1)
        end (.atEndOfMonth ym)]
    (take-while #(not (.isAfter % end))
                (iterate #(.plusDays % 1) start))))

(defn- pad-to-week-start
  "Pad with nils so first day aligns to Monday."
  [days]
  (let [first-day (first days)
        dow (.getValue (.getDayOfWeek first-day))]
    (concat (repeat (dec dow) nil) days)))

(defn- weeks
  "Partition month days into week rows."
  [days]
  (partition-all 7 (pad-to-week-start days)))

;; =============================================================================
;; Tenant Colors
;; =============================================================================

(defn tenant-color
  "Deterministic color from tenant name hash."
  [tenant-name]
  (let [hue (mod (hash tenant-name) 360)]
    (str "hsl(" hue ", 70%, 50%)")))

(defn tenant-color-light
  "Lighter version for pending requests."
  [tenant-name]
  (let [hue (mod (hash tenant-name) 360)]
    (str "hsl(" hue ", 70%, 85%)")))

;; =============================================================================
;; Day Classification
;; =============================================================================

(defn- day-in-range?
  "Is day within a booking/request's date range?"
  [day {:keys [start-date end-date]}]
  (let [d (to-local-date start-date)
        e (to-local-date end-date)]
    (and (not (.isBefore day d))
         (not (.isAfter day e)))))

(defn- find-bookings-for-day [day bookings]
  (filter #(day-in-range? day %) bookings))

(defn- find-requests-for-day [day requests]
  (filter #(day-in-range? day %) requests))

;; =============================================================================
;; CSS
;; =============================================================================

(def calendar-css
  "
  .calendar { user-select: none; }
  .calendar-nav { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
  .calendar-nav button { padding: 0.5rem 1rem; }
  .calendar-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; }
  .calendar-header { padding: 0.5rem; text-align: center; font-weight: bold; background: #f3f4f6; }
  .day { 
    min-height: 60px; padding: 0.5rem; border: 1px solid #e5e7eb; 
    cursor: pointer; position: relative; transition: all 0.15s;
  }
  .day:hover { background: #f0f9ff; }
  .day.past { background: #f9fafb; color: #9ca3af; cursor: not-allowed; }
  .day.today { border: 2px solid #3b82f6; }
  .day.empty { border: none; background: transparent; cursor: default; }
  .day-num { font-size: 0.875rem; }
  .day-indicators { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 4px; }
  .indicator { width: 8px; height: 8px; border-radius: 50%; }
  .indicator.booked { opacity: 1; }
  .indicator.pending { opacity: 0.5; }
  .selecting { background: #3b82f6 !important; color: white; }
  .selecting .day-num { color: white; }
  @keyframes pulse { 
    0%, 100% { box-shadow: 0 0 0 0 rgba(59,130,246,0.4); }
    50% { box-shadow: 0 0 0 4px rgba(59,130,246,0); }
  }
  .selecting { animation: pulse 1.2s ease-in-out infinite; }
  ")

;; =============================================================================
;; Components (small, composable)
;; =============================================================================

(defn day-indicator
  "Small colored dot for a booking/request."
  [{:keys [tenant-name]} booked?]
  [:div.indicator {:class (if booked? "booked" "pending")
                   :style (str "background:" (tenant-color tenant-name))
                   :title tenant-name}])

(defn day-cell
  "Single day in calendar grid."
  [day today bookings requests]
  (if (nil? day)
    [:div.day.empty]
    (let [day-bookings (find-bookings-for-day day bookings)
          day-requests (find-requests-for-day day requests)
          past? (.isBefore day today)
          today? (.equals day today)]
      [:div.day {:data-day (str day)
                 :class (cond past? "past" today? "today")}
       [:div.day-num (.getDayOfMonth day)]
       (into [:div.day-indicators]
             (concat (map #(day-indicator % true) day-bookings)
                     (map #(day-indicator % false) day-requests)))])))

(defn week-headers
  "Monday through Sunday header row."
  []
  (let [days ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]]
    (map #(vector :div.calendar-header %) days)))

(defn calendar-nav
  "Month navigation buttons."
  [^YearMonth ym]
  (let [prev (.minusMonths ym 1)
        next (.plusMonths ym 1)
        fmt (.getMonth ym)]
    [:div.calendar-nav
     [:button {:data-on-click (str "$$get('/calendar?month=" prev "')")} "← Prev"]
     [:span {:style "font-size:1.25rem; font-weight:bold"}
      (str fmt " " (.getYear ym))]
     [:button {:data-on-click (str "$$get('/calendar?month=" next "')")} "Next →"]]))

(defn calendar-grid
  "The month grid with all days."
  [^YearMonth ym bookings requests]
  (let [today (LocalDate/now)
        days (month-days ym)
        week-rows (weeks days)]
    (into [:div.calendar-grid]
          (concat (week-headers)
                  (for [week week-rows
                        day week]
                    (day-cell day today bookings requests))))))

;; =============================================================================
;; Calendar Page
;; =============================================================================

(defn calendar-fragment
  "Just the calendar div (for SSE updates)."
  [^YearMonth ym tenant-name bookings requests]
  [:div#calendar-container {:data-calendar true
                            :data-tenant tenant-name
                            :data-on-load "$$get('/calendar/events')"}
   (calendar-nav ym)
   (calendar-grid ym bookings requests)])

(defn calendar-page
  "Full calendar page with layout."
  [^YearMonth ym tenant-name bookings requests]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Tilda - Calendar"]
    [:script {:type "module"
              :src "https://cdn.jsdelivr.net/npm/@starfederation/datastar@1.0.0-beta.11/bundles/datastar.js"}]
    [:style calendar-css]]
   [:body
    [:h1 "Booking Calendar"]
    [:p "Drag to select dates for your booking request."]
    (calendar-fragment ym tenant-name bookings requests)
    [:script {:src "/js/calendar.js" :defer true}]]])
