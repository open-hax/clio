(ns clio.extern.jvm.crypto
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn sha256
  "The same UTF-8 SHA-256 protocol as clio.extern.js.crypto."
  [text]
  (->> (.getBytes ^String text StandardCharsets/UTF_8)
       (.digest (MessageDigest/getInstance "SHA-256"))
       (.formatHex (HexFormat/of))))
