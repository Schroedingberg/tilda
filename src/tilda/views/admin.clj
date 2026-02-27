(ns tilda.views.admin
  "Admin UI for conflict resolution.
   
   ## Overview
   
   Displays pending requests grouped by overlapping date ranges.
   Admins can pick a specific winner or use a decider algorithm."
  (:require [tilda.views.layout :as layout]
            [clojure.string :as str])
  (:import [java.time.format DateTimeFormatter]
           [java.time ZoneId Instant ZonedDateTime]))

;; =============================================================================
;; Date Helpers
;; =============================================================================

(defn- to-zoned
  "Convert Instant or ZonedDateTime to ZonedDateTime UTC."
  [temporal]
  (condp instance? temporal
    ZonedDateTime temporal
    Instant (.atZone temporal (ZoneId/of "UTC"))
    nil))

(defn- format-date [temporal]
  (some-> (to-zoned temporal)
          (.format (DateTimeFormatter/ofPattern "MMM d, yyyy"))))

(defn- format-datetime [temporal]
  (some-> (to-zoned temporal)
          (.format (DateTimeFormatter/ofPattern "MMM d HH:mm"))))

(defn- to-iso
  "Convert to ISO-8601 string for API calls."
  [temporal]
  (some-> (to-zoned temporal)
          .toInstant
          (.format DateTimeFormatter/ISO_INSTANT)))

;; =============================================================================
;; Conflict Detection
;; =============================================================================
;; TODO