(ns clio.extern.js.cli-process
  "Own native CLI subprocess lifetime and its deterministic filesystem read barrier."
  (:require ["node:child_process" :as child-process]
            ["node:path" :as path]))

(defn- stop-process! [^js child closed?]
  (when (and (some? (.-pid child)) (not @closed?))
    (try
      (if (= "win32" (.-platform js/process))
        (.kill child "SIGTERM")
        (.kill js/process (- (.-pid child)) "SIGTERM"))
      (catch :default _error (.kill child "SIGTERM")))))

(defn- launch! [root args env]
  (let [closed? (atom false) timed-out? (atom false)
        child (child-process/spawn (.-execPath js/process) (clj->js args)
                                  #js {:cwd root :env env :detached (not= "win32" (.-platform js/process))
                                       :stdio #js ["ignore" "pipe" "pipe"]})
        stdout (atom "") stderr (atom "")
        timer (js/setTimeout #(do (reset! timed-out? true) (stop-process! child closed?)) 30000)
        completion (js/Promise.
                    (fn [resolve _reject]
                      (.on (.-stdout child) "data" #(swap! stdout str (.toString %)))
                      (.on (.-stderr child) "data" #(swap! stderr str (.toString %)))
                      (.on child "error" (fn [error] (swap! stderr str (ex-message error))))
                      (.on child "close" (fn [code signal]
                                           (reset! closed? true)
                                           (js/clearTimeout timer)
                                           (resolve {:exit-code code :signal signal :stdout @stdout :stderr @stderr
                                                     :timed-out? @timed-out?})))))]
    #js {:child child :completion completion :closed closed?}))

(defn start!
  "Run the actual published launcher with inherited runtime settings and a bounded read barrier."
  [{:keys [root args file ready release]}]
  (let [preload (path/resolve root "test/clio/extern/js/cli_read_barrier.cjs")
        env (js/Object.assign #js {} (.-env js/process)
                              #js {:CLIO_CLI_RACE_PATH file :CLIO_CLI_RACE_READY ready :CLIO_CLI_RACE_RELEASE release
                                   :NODE_OPTIONS (str (or (.. js/process -env -NODE_OPTIONS) "") " --require=" (js/JSON.stringify preload))})]
    (launch! root (into [(path/resolve root "bin/clio.mjs")] args) env)))

(defn start-pipe-peer!
  "Start a real leader that exits while its child retains the inherited output pipes."
  [root ready]
  (launch! root [(path/resolve root "test/clio/extern/js/cli_pipe_peer.cjs") ready] (.-env js/process)))

(defn start-command!
  "Run the real launcher, optionally refusing a named native ledger durability fence."
  [{:keys [root args file phase trace]}]
  (let [preload (path/resolve root "test/clio/extern/js/cli_sync_failure.cjs")
        env (js/Object.assign #js {} (.-env js/process)
                              #js {:CLIO_CLI_SYNC_PATH (or file "") :CLIO_CLI_SYNC_PHASE (or (some-> phase name) "")
                                   :CLIO_CLI_SYNC_TRACE (or trace "")
                                   :NODE_OPTIONS (str (or (.. js/process -env -NODE_OPTIONS) "") " --require=" (js/JSON.stringify preload))})]
    (launch! root (into [(path/resolve root "bin/clio.mjs")] args) env)))

(defn leader-exited?
  "Report only the native leader's exit, distinct from output closure."
  [^js handle]
  (some? (.-exitCode ^js (.-child handle))))

(defn ^:async finish!
  "Resolve only after the real CLI process closes its output descriptors."
  [^js handle]
  (await (.-completion handle)))

(defn ^:async stop!
  "Terminate and reap a pending CLI process group before removing its fixture directory."
  [^js handle]
  (stop-process! (.-child handle) (.-closed handle))
  (await (.-completion handle)))
