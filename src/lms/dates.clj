(ns lms.dates
  "Date conversion and comparison utilities.

   Centralizes java.util.Date ↔ java.time.LocalDate conversions.
   All business logic should use these functions instead of
   raw java.util.Date methods for date comparison and arithmetic.

   Datomic stores :db.type/instant as java.util.Date. These utilities
   accept java.util.Date at the boundary and convert internally,
   so callers pass Datomic dates directly without boilerplate."
  (:import [java.time LocalDate ZoneId]
           [java.time.temporal ChronoUnit]))

(defn ->local-date
  "Convert java.util.Date to LocalDate using system timezone."
  ^LocalDate [^java.util.Date d]
  (.. d toInstant (atZone (ZoneId/systemDefault)) toLocalDate))

(defn ->date
  "Convert LocalDate to java.util.Date (start of day, system timezone)."
  ^java.util.Date [^LocalDate ld]
  (java.util.Date/from (.toInstant (.atStartOfDay ld (ZoneId/systemDefault)))))

(defn parse-date
  "Parse YYYY-MM-DD string to LocalDate."
  ^LocalDate [^String s]
  (LocalDate/parse s))

(defn today
  "Current date as LocalDate."
  ^LocalDate []
  (LocalDate/now))

(defn days-between
  "Number of calendar days between two java.util.Date values.
   Uses java.time to avoid DST-related off-by-one errors."
  ^long [^java.util.Date from ^java.util.Date to]
  (.between ChronoUnit/DAYS (->local-date from) (->local-date to)))

(defn after?
  "True if date a is after date b (calendar day comparison)."
  [^java.util.Date a ^java.util.Date b]
  (.isAfter (->local-date a) (->local-date b)))
