(ns lotuc.backoff.clock
  (:require
   [lotuc.backoff.protocols :as p]))

(def system-clock
  (delay (let [r (reify p/Clock (now [_] (System/currentTimeMillis)))]
           (defmethod print-method (type r) [_ writer]
             (print-simple "#[SystemClock]" writer))
           r)))

(extend-protocol p/Clock
  java.lang.Long
  (now [v] v))
