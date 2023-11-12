(ns lotuc.backoff.exponential
  (:require
   [lotuc.backoff.protocols :as p]
   [lotuc.backoff.clock :as c]))

(defn- calc-exponential-backoff
  [{:keys [randomization-factor multiplier
           max-interval current-interval]}]
  (-> (if (zero? randomization-factor)
        current-interval
        (let [random       (Math/random)
              delta        (bigint (* randomization-factor current-interval))
              min-interval (- current-interval delta)
              max-interval (+ current-interval delta)]
          (+ min-interval (* random (inc (- max-interval min-interval))))))
      (min (or max-interval Long/MAX_VALUE))
      long))

(defrecord ExponentialBackoff [initial-interval
                               randomization-factor
                               multiplier
                               max-interval
                               max-elapsed-time
                               stop
                               clock
                               current-interval
                               start-time
                               backoff]
  p/Backoff
  (backoff [_] backoff)
  (nxt [v]
    (let [v' (assoc v :current-interval (min (* multiplier current-interval) max-interval))
          elapsed (- (p/now clock) start-time)
          backoff (let [b (calc-exponential-backoff v')]
                    (if (or (nil? max-elapsed-time)
                            (not (pos? max-elapsed-time))
                            (<= (+ (bigint b) elapsed) max-elapsed-time))
                      b
                      stop))]
      (map->ExponentialBackoff (assoc v' :backoff backoff)))))

(defn make-exponential-back-off
  ([] (make-exponential-back-off {}))
  ([values-map]
   (-> {:initial-interval     500
        :randomization-factor 0.5
        :multiplier           1.5
        :max-interval         60e3
        :max-elapsed-time     (* 15 60e3)
        :stop                 nil
        :clock                @c/system-clock}
       (merge values-map)
       (as-> $ (let [{:keys [initial-interval max-interval clock]} $]
                 (-> $
                     (assoc :current-interval (min max-interval initial-interval))
                     (assoc :start-time       (p/now clock)))))
       (as-> $ (assoc $ :backoff (calc-exponential-backoff $)))
       map->ExponentialBackoff)))
