(ns lotuc.backoff
  (:require
   [potemkin]
   [lotuc.backoff.protocols :as p]
   [lotuc.backoff.exponential]
   [lotuc.backoff.tries]
   [lotuc.backoff.retry]))

(potemkin/import-vars
 [lotuc.backoff.protocols
  backoff nxt]
 [lotuc.backoff.exponential
  make-exponential-back-off]
 [lotuc.backoff.tries
  with-max-retries]
 [lotuc.backoff.retry
  retry retry<!!])

(extend-protocol p/Backoff
  nil
  (backoff [_] nil)
  (nxt [_] nil)

  java.lang.Integer
  (backoff [v] (when-not (neg? v) (long v)))
  (nxt [v] (p/backoff v))

  java.lang.Long
  (backoff [v] (when-not (neg? v) v))
  (nxt [v] (p/backoff v))

  clojure.lang.ISeq
  (backoff [v] (when (seq v) (backoff (first v))))
  (nxt [v] (rest v))

  clojure.lang.IPersistentVector
  (backoff [v] (when (seq v) (backoff (first v))))
  (nxt [v] (rest v)))
