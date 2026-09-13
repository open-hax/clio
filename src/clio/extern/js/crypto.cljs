(ns clio.extern.js.crypto
  (:require ["node:crypto" :as crypto]))

(defn sha256
  "Clojure string in, Clojure string out. The mutable Node hash object never
   crosses this namespace boundary."
  [text]
  (-> (crypto/createHash "sha256")
      (.update text "utf8")
      (.digest "hex")))
