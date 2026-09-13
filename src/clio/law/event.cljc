(ns clio.law.event
  (:require [clojure.string :as str]))

(def uuid-pattern
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(defn uuid-string?
  [value]
  (and (string? value)
       (boolean (re-matches uuid-pattern value))))

(defn non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn positive-int?
  [value]
  (and (integer? value)
       (pos? value)))

(defn event-identity-valid?
  [event]
  (let [event-id (:event/id event)
        causes (:event/causes event)]
    (and (uuid-string? event-id)
         (non-blank-string? (:event/stream event))
         (positive-int? (:event/seq event))
         (vector? causes)
         (every? uuid-string? causes)
         (= (count causes) (count (set causes)))
         (not (some #{event-id} causes)))))
