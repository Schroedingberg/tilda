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
  "Small colored dot for a booking/request.
   Pending requests use dashed border with tenant color."
  [{:keys [xt/id tenant-name]} booked?]
  (if booked?
    [:div.indicator.booked {:style (str "background:" (tenant-color tenant-name))
                            :title tenant-name}]
    [:div.indicator.pending {:data-request-id (str id)
                             :data-request-tenant tenant-name
                             :style (str "color:" (tenant-color tenant-name))
                             :title (str tenant-name " (pending - drag to cancel)")}]))

(defn- first-of-month?
  "Is this the first day of the month?"
  [^LocalDate day]
  (= 1 (.getDayOfMonth day)))

(defn- month-label
  "Short month name for indicator."
  [^LocalDate day]
  (.getDisplayName (.getMonth day) TextStyle/SHORT (Locale/getDefault)))

(defn day-cell
  "Single day in calendar grid.
   First-of-month days show a subtle month indicator."
  [day today bookings requests]
  (if (nil? day)
    [:div.day.empty]
    (let [day-bookings (find-bookings-for-day day bookings)
          day-requests (find-requests-for-day day requests)
          past? (.isBefore day today)
          today? (.equals day today)
          first? (first-of-month? day)]
      [:div.day {:id (str "day-" day)
                 :data-day (str day)
                 :class (str (cond past? "past" today? "today" :else "")
                             (when first? " month-start"))}
       (when first?
         [:div.month-label (month-label day)])
       [:div.day-num (.getDayOfMonth day)]
       (into [:div.day-indicators]
             (concat (map #(day-indicator % true) day-bookings)
                     (map #(day-indicator % false) day-requests)))])))

(defn week-headers
  "Monday through Sunday header row."
  []
  (let [days ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]]
    (map #(vector :div.calendar-header %) days)))

(defn- continuous-days
  "Generate flat sequence of days across multiple months.
   Only pads the start to align first day to Monday."
  [months]
  (let [all-days (mapcat month-days months)
        first-day (first all-days)
        start-pad (dec (.getValue (.getDayOfWeek first-day)))]
    (concat (repeat start-pad nil) all-days)))

(defn continuous-grid
  "Single continuous grid of days spanning multiple months."
  [months bookings requests]
  (let [today (LocalDate/now)
        all-days (continuous-days months)]
    (into [:div#calendar-grid.calendar-grid]
          (concat (week-headers)
                  (for [day all-days]
                    (day-cell day today bookings requests))))))

(defn load-sentinel
  "Sentinel div that triggers loading more months when visible.
   Spans full width of grid."
  [^YearMonth next-month]
  [:div.load-sentinel {:id (str "load-" next-month)
                       :style "grid-column: 1 / -1"
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
  "Main calendar container with continuous grid and lazy load sentinel."
  [^YearMonth start-month tenant-name bookings requests]
  (let [months (initial-months start-month 3)
        next-month (.plusMonths (last months) 1)
        today (LocalDate/now)
        all-days (continuous-days months)]
    [:div#calendar-container.calendar {:data-calendar true
                                       :data-tenant tenant-name
                                       :data-init "@get('/calendar/events', {openWhenHidden: true})"}
     (into [:div#calendar-grid.calendar-grid]
           (concat (week-headers)
                   (for [day all-days]
                     (day-cell day today bookings requests))
                   [(load-sentinel next-month)]))]))

(defn month-fragment
  "Days fragment for lazy loading - replaces sentinel with day cells + new sentinel.
   Loads 3 months of days at a time."
  [^YearMonth ym bookings requests]
  (let [months (take 3 (iterate #(.plusMonths % 1) ym))
        next-month (.plusMonths ym 3)
        today (LocalDate/now)
        ;; No start padding - days continue from previous
        all-days (mapcat month-days months)]
    ;; display:contents makes wrapper transparent to grid layout
    [:div {:id (str "load-" ym)
           :style "display: contents"}
     (for [day all-days]
       (day-cell day today bookings requests))
     (load-sentinel next-month)]))

(defn- page-header
  "Page header with resource name and tenant info."
  [tenant-name resource-name]
  [:header {:style "margin-bottom: 1.5rem;"}
   [:div {:style "display: flex; justify-content: space-between; align-items: flex-start;"}
    [:hgroup
     [:h1 (or resource-name "Booking Calendar")]
     [:p "Drag to select dates for your booking request"]]
    [:a {:href "/admin" 
         :style "font-size: 0.875rem; text-decoration: none; color: #666; padding: 0.5rem;"} 
     "Admin"]]
   [:small {:style "display: inline-flex; align-items: center; gap: 0.5rem;"}
    [:span {:style (str "width: 12px; height: 12px; border-radius: 50%; background:" (tenant-color tenant-name))}]
    (str "Booking as " tenant-name)]])

(defn- legend
  "Legend explaining calendar indicators."
  []
  [:div.legend
   [:div.legend-item
    [:span.legend-dot.booked]
    [:span "Confirmed"]]
   [:div.legend-item
    [:span.legend-dot.pending]
    [:span "Pending"]]
   [:div.legend-item
    [:span.legend-dot.today]
    [:span "Today"]]])

(defn calendar-page
  "Full calendar page with layout."
  ([start-month tenant-name bookings requests]
   (calendar-page start-month tenant-name bookings requests nil))
  ([^YearMonth start-month tenant-name bookings requests resource-name]
   [:html {:data-theme "light"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Tilda - Calendar"]
     ;; Pico CSS - classless semantic styling
     [:link {:rel "stylesheet"
             :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css"}]
     ;; Calendar-specific overrides
     [:link {:rel "stylesheet" :href "/css/calendar.css"}]
     ;; Datastar - server-driven reactivity
     [:script {:type "module"
               :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}]]
    [:body
     [:main.container
      [:div.sticky-header
       (page-header tenant-name resource-name)
       (legend)]
      (calendar-container start-month tenant-name bookings requests)]
     [:script {:src "/js/calendar.js" :defer true}]]]))

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
