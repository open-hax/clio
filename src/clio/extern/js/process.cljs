(ns clio.extern.js.process
  (:require [promesa.core :as p]
            ["node:child_process" :as child-process]))

(defn exit!
  [code]
  (.exit js/process code))

(defn cwd
  []
  (.cwd js/process))

(defn now-ms
  []
  (js/Date.now))

(defn sleep-until!
  [epoch-ms]
  (let [buffer (js/SharedArrayBuffer. 4)
        view (js/Int32Array. buffer)]
    (loop []
      (let [remaining (- epoch-ms (js/Date.now))]
        (when (pos? remaining)
          (js/Atomics.wait view 0 0 remaining)
          (recur)))))
  nil)

(defn run-command!
  [{:keys [command args cwd]}]
  (js/Promise.
   (fn [resolve reject]
     (let [stdout (atom "")
           stderr (atom "")
           options (cond-> {:stdio ["ignore" "pipe" "pipe"]}
                     cwd (assoc :cwd cwd))
           process (child-process/spawn command (clj->js args) (clj->js options))]
       (.on (.-stdout process) "data"
            (fn [chunk] (swap! stdout str (.toString chunk))))
       (.on (.-stderr process) "data"
            (fn [chunk] (swap! stderr str (.toString chunk))))
       (.on process "error" reject)
       (.on process "close"
            (fn [code signal]
              (resolve {:exit-code code
                        :signal signal
                        :stdout @stdout
                        :stderr @stderr})))))))

(defn ^:async run-concurrently!
  [commands]
  (p/let [results (js/Promise.all (clj->js (mapv run-command! commands)))]
    (mapv #(aget results %) (range (.-length results)))))
