(ns lotuc.backoff.clock
  (:require
   [lotuc.backoff.protocols :as p]))

(def system-clock
  (delay (let [r (reify p/Clock (now [_] #?(:clj (System/currentTimeMillis)
                                            :cljs (.getTime (js/Date.)))))]
           #?(:clj (defmethod print-method (type r) [_ writer]
                     (print-simple "#[SystemClock]" writer)))
           r)))

#?(:clj (extend-protocol p/Clock
          java.lang.Long
          (now [v] v))
   :cljs (extend-protocol p/Clock
           js/Number
           (now [v] (long v))))
