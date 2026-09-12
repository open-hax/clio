(ns clio.extern.jvm.fs
  (:refer-clojure :exclude [exists?])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileLock]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files LinkOption OpenOption Path Paths
            StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.util UUID]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links (make-array LinkOption 0))
(def ^:private no-attributes (make-array FileAttribute 0))
(def ^:private ^ReentrantLock process-guard (ReentrantLock. true))
(def ^:private active-locks (atom {}))

(defn- nio-path ^Path [path]
  (Paths/get ^String path (make-array String 0)))

(defn- file-key
  [path]
  (let [p (nio-path path)
        ^BasicFileAttributes attrs (Files/readAttributes p BasicFileAttributes no-links)]
    (when-not (.isRegularFile attrs)
      (throw (ex-info "Ledger path is not a regular file"
                      {:path path :clio/error :clio.fs/not-regular-file})))
    ;; A path fallback cannot distinguish hard links. Closing a second alias
    ;; descriptor after an overlapping-lock error would drop the first POSIX
    ;; lock, so an opaque/missing identity must fail closed here.
    (or (.fileKey attrs)
        (throw (ex-info "Filesystem does not expose a stable file identity"
                        {:path path :clio/error :clio.fs/unsupported-file-identity})))))

(defn- with-process-guard
  [f]
  (.lock process-guard)
  (try (f) (finally (.unlock process-guard))))

(defn- refuse-locked-path!
  [path]
  (let [key (file-key path)]
    (when (some #(= key (:file/key %)) (vals @active-locks))
      (throw (ex-info "Path is locked by this process; use the owning descriptor"
                      {:path path :clio/error :clio.fs/locked-path-read})))))

(defn exists? [path]
  (Files/exists (nio-path path) no-links))

(defn sync-directory!
  "Force directory entries on the supported Linux default POSIX filesystem.

   Directory FileChannels are not portable Java. Refuse other implementations
   and surface open/force failures instead of acknowledging weaker durability."
  [path]
  (let [directory (.toAbsolutePath (nio-path path))
        filesystem (.getFileSystem directory)]
    (when-not (and (= "Linux" (System/getProperty "os.name"))
                   (= "file" (.getScheme (.provider filesystem)))
                   (.contains (.supportedFileAttributeViews filesystem) "posix"))
      (throw (ex-info "JVM directory durability is unsupported on this filesystem"
                      {:path path :clio/error :clio.fs/directory-sync-unavailable})))
    (try
      (with-open [channel (FileChannel/open directory
                                            (into-array OpenOption [StandardOpenOption/READ]))]
        (.force channel true))
      (catch Exception cause
        (throw (ex-info "JVM directory synchronization failed"
                        {:path path :clio/error :clio.fs/directory-sync-unavailable} cause)))))
  path)

(defn- parent-path [path]
  (str (.getParent (.toAbsolutePath (nio-path path)))))

(defn ensure-dir! [path]
  (let [ancestry (loop [directory (.normalize (.toAbsolutePath (nio-path path))) result []]
                  (if (nil? directory)
                    result
                    (recur (.getParent directory) (conj result (str directory)))))]
    (Files/createDirectories (nio-path path) no-attributes)
    ;; An earlier mkdir may have succeeded before its parent force failed.
    ;; Existing paths therefore need the same durability check on retry.
    (doseq [directory ancestry]
      (sync-directory! directory)))
  path)

(defn force-file!
  "Force an owning file descriptor before acknowledging durable file content."
  [^FileChannel channel]
  (.force channel true))

(defn create-exclusive! [path]
  (with-open [channel (FileChannel/open (nio-path path)
                                      (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                                              StandardOpenOption/WRITE]))]
    (force-file! channel))
  (sync-directory! (parent-path path))
  path)

(defn read-text
  "Path reads cannot close an alias descriptor and silently drop a POSIX lock."
  [path]
  (with-process-guard
    #(do (refuse-locked-path! path)
         (Files/readString (nio-path path) StandardCharsets/UTF_8))))

(defn- write-buffer!
  [^FileChannel channel text]
  (let [buffer (ByteBuffer/wrap (.getBytes ^String text StandardCharsets/UTF_8))]
    (while (.hasRemaining buffer) (.write channel buffer))
    (.force channel true)))

(defn write-text! [path text]
  (with-process-guard
    #(do
       (when (exists? path) (refuse-locked-path! path))
       (with-open [channel (FileChannel/open
                            (nio-path path)
                            (into-array OpenOption
                                        [StandardOpenOption/CREATE
                                         StandardOpenOption/WRITE
                                         StandardOpenOption/TRUNCATE_EXISTING]))]
         (write-buffer! channel text))
       (sync-directory! (parent-path path))))
  path)

(defn rename! [from to]
  (let [parents (distinct [(parent-path from) (parent-path to)])]
    ;; Check support before changing the namespace, then force both affected
    ;; parents after the atomic move. Post-move failure remains a failed write.
    (doseq [directory parents] (sync-directory! directory))
    (Files/move (nio-path from) (nio-path to)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    (doseq [directory parents] (sync-directory! directory)))
  to)

(defn hard-link! [from to]
  (Files/createLink (nio-path to) (nio-path from))
  to)

(defn delete-if-exists! [path]
  (Files/deleteIfExists (nio-path path))
  path)

(defn remove-tree! [path]
  (when (exists? path)
    (with-open [paths (Files/walk (nio-path path)
                                 (make-array java.nio.file.FileVisitOption 0))]
      (doseq [p (reverse (sort-by #(.getNameCount ^Path %)
                                 (iterator-seq (.iterator paths))))]
        (Files/deleteIfExists p))))
  path)

(defn list-files [path]
  (if (exists? path)
    (with-open [paths (Files/list (nio-path path))]
      (mapv #(str (.getFileName ^Path %)) (iterator-seq (.iterator paths))))
    []))

(defn acquire-lock!
  "Lock an existing ledger inode using FileChannel's whole-file advisory lock.

   JVM FileChannel uses POSIX record locking on Unix and interoperates with
   Clio's Node fcntl lock. A process guard serializes acquisition before opening
   any second descriptor: closing even a failed overlapping channel could drop
   the process's existing POSIX lock. This deliberately serializes local JVM
   ledger operations; separate processes still coordinate per inode in the OS.
   The returned Clojure token carries no host channel or lock object."
  [path]
  (.lock process-guard)
  (try
    (refuse-locked-path! path)
    (let [key (file-key path)
          channel (FileChannel/open (nio-path path)
                                    (into-array OpenOption [StandardOpenOption/READ
                                                            StandardOpenOption/WRITE]))]
      (try
        (let [lock (.lock channel 0 Long/MAX_VALUE false)
              token (str (UUID/randomUUID))]
          (swap! active-locks assoc token
                 {:channel channel :file-lock lock :file/key key :path path})
          {:lock/id token :lock/path path})
        (catch Throwable cause
          (.close channel)
          (throw cause))))
    (catch Throwable cause
      (.unlock process-guard)
      (throw cause))))

(defn- lock-entry [token]
  (or (get @active-locks (:lock/id token))
      (throw (ex-info "Ledger lock token is not active"
                      {:clio/error :clio.fs/inactive-lock}))))

(defn read-locked-text
  "Read only through the descriptor that owns the inode lock."
  [token]
  (let [{:keys [^FileChannel channel]} (lock-entry token)
        size (.size channel)]
    (when (> size Integer/MAX_VALUE)
      (throw (ex-info "Ledger exceeds the in-memory reader limit"
                      {:clio/error :clio.fs/ledger-too-large :bytes size})))
    (.position channel 0)
    (let [buffer (ByteBuffer/allocate (int size))]
      (loop []
        (when (and (.hasRemaining buffer) (not= -1 (.read channel buffer)))
          (recur)))
      (.flip buffer)
      (str (.decode (.newDecoder StandardCharsets/UTF_8) buffer)))))

(defn append-locked-text!
  "Append and force file contents to stable storage before reporting success."
  [token text]
  (let [{:keys [^FileChannel channel path]} (lock-entry token)]
    (.position channel (.size channel))
    (write-buffer! channel text)
    path))

(defn release-lock!
  [token]
  (let [{:keys [^FileChannel channel ^FileLock file-lock]} (lock-entry token)]
    (try
      (.release file-lock)
      (finally
        (try (.close channel)
             (finally
               (swap! active-locks dissoc (:lock/id token))
               (.unlock process-guard))))))
  nil)
