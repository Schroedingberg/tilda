(ns tilda.views.calendar
  "Calendar UI components for continuous-scroll calendar with lazy loading.
   
   ## Overview
   
   This namespace provides Hiccup components for rendering a calendar view
   with infinite scroll. The calendar loads 3 months initially and lazy-loads
   more months via Datastar's `data-on-intersect` as the user scrolls.
   
   ## Key Components
   
   - `calendar-page` - Full HTML page with calendar container
   - `calendar-container` - Main calendar with initial months + sentinel
   - `month-section` - Single month with header and day grid
   - `month-fragment` - Fragment for lazy-loading (returns 3 months)
   - `day-cell` - Individual day with booking/request indicators
   - `day-cells-for-range` - Generate cells for SSE broadcast
   
   ## Date Range Format
   
   All dates are handled as java.time types:
   - `YearMonth` for month navigation
   - `LocalDate` for day cells
   - `Instant` for booking/request date ranges
   
   ## Styling
   
   CSS styles are loaded from /css/calendar.css (see resources/public/css/).
   Tenant colors are generated deterministically from tenant name hash."
  (:import [java.time LocalDate YearMonth ZoneId Instant]
           [java.time.format TextStyle]
           [java.util Locale]))

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

(defn- days-in-range
  "Generate sequence of LocalDates from start to end (inclusive)."
  [^Instant start ^Instant end]
  (let [start-day (to-local-date start)
        end-day (to-local-date end)]
    (take-while #(not (.isAfter % end-day))
                (iterate #(.plusDays % 1) start-day))))

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
      [:div.day {:id (str "day-" day)
                 :data-day (str day)
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

(defn month-header
  "Month title with sticky positioning."
  [^YearMonth ym]
  (let [month-name (.getDisplayName (.getMonth ym) TextStyle/FULL (Locale/getDefault))]
    [:div.month-header (str month-name " " (.getYear ym))]))

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

(defn month-section
  "A single month section with header and grid."
  [^YearMonth ym bookings requests]
  [:div.month-section {:id (str "month-" ym)}
   (month-header ym)
   (calendar-grid ym bookings requests)])

(defn load-sentinel
  "Sentinel div that triggers loading more months when visible."
  [^YearMonth next-month]
  [:div.load-sentinel {:id (str "load-" next-month)
                       :data-on-intersect__once (str "@get('/calendar/month/" next-month "')")}
   [:span.loading-spinner "Loading more..."]])

;; =============================================================================
;; Calendar Page
;; =============================================================================

(defn- initial-months
  "Generate a sequence of YearMonths starting from given month."
  [^YearMonth start n]
  (take n (iterate #(.plusMonths % 1) start)))

(defn calendar-container
  "Main calendar container with initial months and lazy load sentinel."
  [^YearMonth start-month tenant-name bookings requests]
  (let [months (initial-months start-month 3)
        next-month (.plusMonths (last months) 1)]
    [:div#calendar-container.calendar {:data-calendar true
                                       :data-tenant tenant-name
                                       :data-init "@get('/calendar/events', {openWhenHidden: true})"}
     (for [ym months]
       (month-section ym bookings requests))
     (load-sentinel next-month)]))

(defn month-fragment
  "Single month fragment for lazy loading - replaces the load sentinel.
   Loads 3 months at a time to push sentinel below fold."
  [^YearMonth ym bookings requests]
  (let [months (take 3 (iterate #(.plusMonths % 1) ym))
        next-month (.plusMonths ym 3)]
    [:div {:id (str "load-" ym)}
     (for [m months]
       (month-section m bookings requests))
     (load-sentinel next-month)]))

(defn calendar-page
  "Full calendar page with layout."
  [^YearMonth start-month tenant-name bookings requests]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Tilda - Calendar"]
    [:script {:type "module"
              :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}]
    [:link {:rel "stylesheet" :href "/css/calendar.css"}]]
   [:body
    [:h1 "Booking Calendar"]
    [:p "Drag to select dates for your booking request."]
    (calendar-container start-month tenant-name bookings requests)
    [:script {:src "/js/calendar.js" :defer true}]]])

;; =============================================================================
;; SSE Updates
;; =============================================================================

(defn day-cells-for-range
  "Generate hiccup for day cells in a date range (for SSE broadcast)."
  [^Instant start ^Instant end bookings requests]
  (let [today (LocalDate/now)
        days (days-in-range start end)]
    (for [day days]
      (day-cell day today bookings requests))))
