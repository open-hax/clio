(ns clio.extern.jvm.runtime
  (:refer-clojure :exclude [random-uuid])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
           [java.util UUID]))

(def ^:private iso-milliseconds
  (.toFormatter (.appendInstant (DateTimeFormatterBuilder.) 3)))

(defn random-uuid
  []
  (str (UUID/randomUUID)))

(defn now-iso
  "UTC with millisecond precision, matching JavaScript Date.toISOString."
  []
  (.format ^DateTimeFormatter iso-milliseconds (Instant/now)))
