(ns clio.domain.projection)

(defn state
  "Fold canonical events into derived state. The reducer must be pure with
   respect to event data; projection files are disposable materializations."
  [canonical initial apply-event]
  (reduce apply-event initial (:canonical/events canonical)))

(defn projection
  [canonical initial apply-event]
  {:projection/event-ids (:canonical/event-ids canonical)
   :projection/event-count (count (:canonical/event-ids canonical))
   :projection/state (state canonical initial apply-event)})
