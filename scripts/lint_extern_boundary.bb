#!/usr/bin/env bb

;; Host entrypoint for the extern.js boundary gate. Everything here is
;; filesystem traversal and exit code; the rules themselves are runtime-neutral
;; and live in scripts/clio/lint_extern_boundary.cljc, where the package test
;; suite exercises them under Babashka, NBB, and Shadow CLJS.

(require '[babashka.fs :as bfs]
         '[clio.lint-extern-boundary :as boundary]
         '[clojure.java.io :as io])

(defn package-root
  "The package directory containing `file`, found by walking up to the nearest
   ancestor holding a package.json."
  [file]
  (loop [dir (.getParentFile (.getAbsoluteFile (io/file file)))]
    (cond
      (nil? dir) nil
      (.isFile (io/file dir "package.json")) dir
      :else (recur (.getParentFile dir)))))

(defn scan-roots
  [root]
  [(io/file root "src" "clio")
   (io/file root "test" "clio")
   (io/file root "bin")])

(defn findings
  [roots]
  (let [files (->> roots
                   (filter #(.isDirectory %))
                   (mapcat file-seq)
                   (filter #(and (.isFile %)
                                 (boundary/source-path? (.getName %))))
                   sort)]
    (for [file files
          :let [path (.getCanonicalPath file)]
          violation (boundary/file-violations path (slurp file))]
      {:file (str (bfs/relativize (bfs/cwd) (.toPath file)))
       :violation violation})))

(let [found (findings (scan-roots (package-root *file*)))]
  (if (seq found)
    (do
      (binding [*out* *err*]
        (println "Clio JS boundary violations:")
        (doseq [{:keys [file violation]} found]
          (println " -" file ":" violation))
        (println "Move JS/Node access behind clio.extern.js.* and return Clojure data."))
      (System/exit 1))
    (println "Clio extern.js boundary: clean")))
