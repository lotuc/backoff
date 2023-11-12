(ns lotuc.backoff.tries
  (:require
   [lotuc.backoff.protocols :as p]))

(defrecord BackoffRetries [delegate max-retries num-retries]
  p/Backoff
  (backoff [_] (p/backoff delegate))
  (nxt [v] (when (< num-retries max-retries)
             (-> v
                 (update :num-retries inc)
                 (update :delegate p/nxt)))))

(defn with-max-retries [b max-retries]
  (map->BackoffRetries {:delegate b :max-retries max-retries :num-retries 0}))
