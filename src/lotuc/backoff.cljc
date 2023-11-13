(ns lotuc.backoff
  (:require
   #?(:clj [potemkin :refer [import-vars]]
      :cljs [lotuc.backoff.potemkin-ns :refer-macros [import-vars]])
   [lotuc.backoff.protocols :as p]
   [lotuc.backoff.exponential]
   [lotuc.backoff.tries]
   [lotuc.backoff.retry-impl]))

(import-vars
 [lotuc.backoff.protocols
  backoff nxt]
 [lotuc.backoff.exponential
  make-exponential-backoff]
 [lotuc.backoff.tries
  with-max-retries]
 [lotuc.backoff.retry-impl
  retry retry<!!])

(extend-protocol p/Backoff
  nil
  (backoff [_] nil)
  (nxt [_] nil))

#?(:clj (extend-protocol p/Backoff
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
   :cljs (extend-protocol p/Backoff
           number
           (backoff [v] (when-not (neg? v) (long v)))
           (nxt [v] (p/backoff v))

           cljs.core/ISeq
           (backoff [v] (when (seq v) (backoff (first v))))
           (nxt [v] (rest v))

           cljs.core/EmptyList
           (backoff [_v] nil)
           (nxt [_v] nil)

           cljs.core/IntegerRange
           (backoff [v] (when (seq v) (backoff (first v))))
           (nxt [v] (rest v))

           cljs.core/PersistentVector
           (backoff [v] (when (seq v) (backoff (first v))))
           (nxt [v] (rest v))

           cljs.core/IndexedSeq
           (backoff [v] (when (seq v) (backoff (first v))))
           (nxt [v] (rest v))))
