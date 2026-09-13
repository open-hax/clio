(ns clio.extern.js.runtime
  (:refer-clojure :exclude [random-uuid])
  (:require ["node:crypto" :as crypto]))

(defn random-uuid
  []
  (crypto/randomUUID))

(defn now-iso
  []
  (.toISOString (js/Date.)))
