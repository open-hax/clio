(ns hooks.promise-chain
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private chain-methods
  '#{then catch finally})

(defn- method-call? [node]
  (and (api/list-node? node)
       (let [sexpr (api/sexpr node)]
         (or (and (seq? sexpr)
                  (symbol? (first sexpr))
                  (let [n (name (first sexpr))]
                    (and (pos? (count n))
                         (= \. (first n))
                         (chain-methods (symbol (subs n 1))))))
             (and (seq? sexpr)
                  (> (count sexpr) 1)
                  (= '. (first sexpr))
                  (chain-methods (second sexpr)))))))

(defn- promise-chain-findings [node]
  (when (api/list-node? node)
    (concat
     (when (method-call? node)
       [(assoc (meta node)
               :type :promise-chain/prefer-async-workflow
               :message "Prefer async/await workflow over promise chains (.then/.catch/.finally)")])
     (mapcat promise-chain-findings (:children node)))))

(defn check [{:keys [node]}]
  (doseq [finding (promise-chain-findings node)]
    (api/reg-finding! finding))
  {:node node})

(defn check-defn [{:keys [node]}]
  (doseq [finding (promise-chain-findings node)]
    (api/reg-finding! finding))
  {:node node})

(defn check-ns [{:keys [node]}]
  (doseq [finding (promise-chain-findings node)]
    (api/reg-finding! finding))
  {:node node})
