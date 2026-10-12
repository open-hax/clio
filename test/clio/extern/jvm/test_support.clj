(ns clio.extern.jvm.test-support
  (:import [java.io File]
           [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util.concurrent TimeUnit]))

(defn start!
  [args output-file]
  (-> (ProcessBuilder. ^java.util.List args)
      ;; stdout is a structured EDN response. Keep diagnostics visible on the
      ;; test runner's stderr rather than corrupting that response stream.
      (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
      (.redirectOutput (File. ^String output-file))
      (.start)))

(defn exited?
  [^Process process timeout-ms]
  (.waitFor process timeout-ms TimeUnit/MILLISECONDS))

(defn finish!
  [^Process process]
  (when-not (exited? process 20000)
    (.destroyForcibly process)
    (throw (ex-info "Timed out waiting for Clio peer" {})))
  (.exitValue process))

(defn stop!
  [^Process process]
  (when (.isAlive process)
    (.destroyForcibly process)
    (.waitFor process)))

(defn now-ms [] (System/currentTimeMillis))

(defn symbolic-link! [target link]
  (Files/createSymbolicLink (Paths/get ^String link (make-array String 0))
                            (Paths/get ^String target (make-array String 0))
                            (make-array java.nio.file.attribute.FileAttribute 0)))

(defn relative-to-cwd [path]
  (str (.relativize (.toAbsolutePath (Paths/get "" (make-array String 0)))
                    (Paths/get ^String path (make-array String 0)))))

(defn wait-for-path!
  [path]
  (let [deadline (+ (now-ms) 20000)]
    (loop []
      (cond
        (Files/exists (Paths/get ^String path (make-array String 0))
                      (make-array LinkOption 0)) true
        (> (now-ms) deadline) (throw (ex-info "Peer never signaled readiness" {:path path}))
        :else (do (Thread/sleep 10) (recur))))))

(defn invalid-instant [] (java.util.Date. Long/MAX_VALUE))
(defn non-edn-instant [] (java.time.Instant/now))
(defn arbitrary-object [] (Object.))
(defn instant-at [millis] (java.util.Date. (long millis)))

(defn permissions!
  "Set real POSIX fixture permissions; descriptor-mode tests also cover privileged hosts."
  [path permissions]
  (Files/setPosixFilePermissions (Paths/get ^String path (make-array String 0))
                                (PosixFilePermissions/fromString permissions))
  path)
