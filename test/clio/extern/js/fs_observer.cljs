(ns clio.extern.js.fs-observer
  "Observe actual Node calls while retaining the real filesystem and file descriptors."
  (:require ["node:fs" :as module-fs]
            ["node:module" :refer [syncBuiltinESMExports]]))

(def ^:private native (or (.-default module-fs) module-fs))

(defn with-observer
  "Call observe! before each sync or rename; injected failures exercise real boundary sequencing."
  [observe! operation]
  (let [original-open (.-openSync native)
        original-sync (.-fsyncSync native)
        original-rename (.-renameSync native)
        handles (atom {})]
    (set! (.-openSync native)
          (fn [& args]
            (let [fd (.apply original-open native (clj->js args))]
              (swap! handles assoc fd (first args)) fd)))
    (set! (.-fsyncSync native)
          (fn [fd]
            (observe! {:operation :sync :path (get @handles fd)
                       :directory? (.isDirectory (.fstatSync native fd))})
            (original-sync fd)))
    (set! (.-renameSync native)
          (fn [from to]
            (observe! {:operation :rename :from from :to to})
            (original-rename from to)))
    (syncBuiltinESMExports)
    (try (operation)
         (finally
           (set! (.-openSync native) original-open)
           (set! (.-fsyncSync native) original-sync)
           (set! (.-renameSync native) original-rename)
           (syncBuiltinESMExports)))))
