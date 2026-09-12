(ns clio.extern.js.canonical-fixture)

(defn invalid-instant [] (js/Date. ##NaN))
(defn arbitrary-object [] #js {})
