(ns clio.extern.js.fs
  (:refer-clojure :exclude [exists?])
  (:require ["fs-ext-extra-prebuilt" :as fs-ext]
            ["node:fs" :as fs]))

(defn exists?
  [path]
  (boolean (fs/existsSync path)))

(defn ensure-dir!
  [path]
  (fs/mkdirSync path #js {:recursive true})
  path)

(defn create-exclusive!
  [path]
  (let [fd (fs/openSync path "wx")]
    (fs/closeSync fd))
  path)

(def ^:private locked-paths
  "Ledger paths this process currently holds a kernel lock on.

   A POSIX record lock is released when the process closes *any* descriptor
   for that file, not only the one that took the lock. So opening a locked
   ledger a second time by path and closing it drops the lock silently — no
   error, no signal, just an unserialized critical section. read-text refuses
   such a path rather than letting that happen.

   The guard keys on the path while the lock keys on the inode, so a hard-link
   alias reaching the same inode under another name is not caught. Callers
   inside a critical section must use read-locked-text regardless."
  (atom #{}))

(defn read-text
  [path]
  (when (contains? @locked-paths path)
    (throw (ex-info
            "Path is locked by this process; read through read-locked-text"
            {:path path :clio/error :clio.fs/locked-path-read})))
  (fs/readFileSync path "utf8"))

(defn append-text!
  [path text]
  (fs/appendFileSync path text "utf8")
  path)

(defn write-text!
  [path text]
  (fs/writeFileSync path text "utf8")
  path)

(defn rename!
  [from to]
  (fs/renameSync from to)
  to)

(defn hard-link!
  [from to]
  (fs/linkSync from to)
  to)

(defn delete-if-exists!
  [path]
  (when (exists? path)
    (fs/unlinkSync path))
  path)

(defn remove-tree!
  [path]
  (when (exists? path)
    (fs/rmSync path #js {:recursive true :force true}))
  path)

(defn list-files
  [path]
  (if (exists? path)
    (vec (fs/readdirSync path))
    []))

(defn ^:async wait-for-exists!
  [path timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(poll []
                 (cond
                   (exists? path) (resolve true)
                   (< (js/Date.now) deadline) (js/setTimeout poll 20)
                   :else
                   (reject
                    (ex-info "Timed out waiting for path"
                             {:path path :timeout-ms timeout-ms}))))]
         (poll))))))

(defn- native-constant
  [name]
  (aget (.-constants fs-ext) name))

(defn- windows?
  []
  (= "win32" (.-platform js/process)))

(defn- unsupported-flock?
  [cause]
  (contains? #{"ENOSYS" "ENOTSUP" "EOPNOTSUPP"} (.-code cause)))

(defn- acquire-unix-lock!
  [fd]
  ;; flock gives open-file-description exclusion on local filesystems, which
  ;; also protects separate descriptors in one process. Some NFS mounts do not
  ;; implement flock, so unsupported-flock errors deliberately fall through.
  ;; The whole-file F_SETLKW below is the authoritative Unix lock and the path
  ;; used by NFS implementations that support POSIX record locking. It is an
  ;; advisory lock — "mandatory locking" is an unrelated, effectively dead
  ;; POSIX feature, not what F_SETLKW does.
  (try
    (fs-ext/flockSync fd "ex")
    (catch :default cause
      (when-not (unsupported-flock? cause)
        (throw cause))))
  (fs-ext/fcntlSync fd "setlkw" (native-constant "F_WRLCK") 0 0))

(defn- acquire-native-lock!
  [fd]
  (if (windows?)
    (fs-ext/lockFileExSync
     fd
     (native-constant "LOCKFILE_EXCLUSIVE_LOCK")
     0 0 0xffffffff 0xffffffff)
    (acquire-unix-lock! fd)))

(defn acquire-lock!
  "Open an existing ledger and hold an OS-backed exclusive lock on its inode.

   The descriptor stays open for the entire read/validate/append critical
   section. The kernel releases the lock automatically if the process exits,
   is killed, or crashes; there is no stale lockfile, lease, PID record,
   reclamation path, or fencing token to race. Hard-link and symlink aliases
   reach the same inode and therefore the same lock.

   Unix uses a whole-file blocking fcntl write lock as the authoritative
   advisory lock, with flock as an additional local-filesystem guard when
   supported. Windows uses an exclusive LockFileEx range covering the full
   file address space.

   Two consequences of the POSIX record lock shape are load-bearing. It is
   released when this process closes *any* descriptor for the file, so every
   read inside the critical section must go through read-locked-text; see
   locked-paths, which refuses the path-based read outright. And the open uses
   the numeric O_APPEND|O_RDWR flags rather than \"a+\": both \"a\" and \"a+\"
   imply O_CREAT, which would let a misspelled or deleted ledger be created
   here and appended to as an empty history. create-ledger! is the only
   creation path; an absent ledger fails with ENOENT."
  [path]
  (let [flags (bit-or (.-O_APPEND (.-constants fs)) (.-O_RDWR (.-constants fs)))
        fd (fs/openSync path flags)]
    (try
      (acquire-native-lock! fd)
      (swap! locked-paths conj path)
      {:lock/path path :lock/fd fd}
      (catch :default cause
        (fs/closeSync fd)
        (throw cause)))))

(defn read-locked-text
  "Read the locked ledger through the same descriptor that owns the lock."
  [{:lock/keys [fd]}]
  (fs/readFileSync fd "utf8"))

(defn append-locked-text!
  "Append through the descriptor that owns the kernel lock."
  [{:lock/keys [fd path]} text]
  (fs/appendFileSync fd text "utf8")
  path)

(defn release-lock!
  "Close the owning descriptor. Kernel file locks are released by close."
  [{:lock/keys [fd path]}]
  (swap! locked-paths disj path)
  (fs/closeSync fd)
  nil)
