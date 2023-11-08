(ns lotuc.backoff
  (:require
   [clojure.core.async :as a]))

(defprotocol Backoff
  (backoff [_])
  (nxt [_]))

(defprotocol Clock
  (now [_]))

(def system-clock
  (delay (let [r (reify Clock (now [_] (System/currentTimeMillis)))]
           (defmethod print-method (type r) [_ writer]
             (print-simple "#[SystemClock]" writer))
           r)))

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
  Backoff
  (backoff [_] backoff)
  (nxt [v]
    (let [v' (assoc v :current-interval (min (* multiplier current-interval) max-interval))
          elapsed (- (now clock) start-time)
          backoff (let [b (calc-exponential-backoff v')]
                    (if (or (nil? max-elapsed-time)
                            (not (pos? max-elapsed-time))
                            (<= (+ (bigint b) elapsed) max-elapsed-time))
                      b
                      stop))]
      (map->ExponentialBackoff (assoc v' :backoff backoff)))))

(defrecord BackoffRetries [delegate max-retries num-retries]
  Backoff
  (backoff [_] (backoff delegate))
  (nxt [v] (when (< num-retries max-retries)
             (-> v
                 (update :num-retries inc)
                 (update :delegate nxt)))))

(defn with-retries [b max-retries]
  (map->BackoffRetries {:delegate b :max-retries max-retries :num-retries 0}))

(defn make-exponential-back-off
  ([] (make-exponential-back-off {}))
  ([values-map]
   (-> {:initial-interval     500
        :randomization-factor 0.5
        :multiplier           1.5
        :max-interval         60e3
        :max-elapsed-time     (* 15 60e3)
        :stop                 nil
        :clock                @system-clock}
       (merge values-map)
       (as-> $ (let [{:keys [initial-interval max-interval clock]} $]
                 (-> $
                     (assoc :current-interval (min max-interval initial-interval))
                     (assoc :start-time       (now clock)))))
       (as-> $ (assoc $ :backoff (calc-exponential-backoff $)))
       map->ExponentialBackoff)))

(extend-protocol Backoff
  nil
  (backoff [_] nil)
  (nxt [_] nil)

  java.lang.Integer
  (backoff [v] (when-not (neg? v) (long v)))
  (nxt [v] (backoff v))

  java.lang.Long
  (backoff [v] (when-not (neg? v) v))
  (nxt [v] (backoff v))

  clojure.lang.ISeq
  (backoff [v] (when (seq v) (max 0 (long (first v)))))
  (nxt [v] (rest v)))

(defn- retry* [f b {:keys [notify timer permanent-error? ctrl] :as opts}]
  (let [ret (a/chan)
        ctrl (or ctrl (a/chan))
        permanent-error? (or permanent-error? (constantly false))
        timer (or timer a/timeout)
        run! #(a/thread
                (try [::ok (f)]
                     (catch Exception e
                       (if (permanent-error? e)
                         [::permanent-err e]
                         [::err e]))))
        done (a/go-loop []
               (if-some [[typ d] (a/<! ctrl)]
                 (case typ
                   ::ok  (a/>! ret [:ok d])
                   ::err (a/>! ret [:err d])
                   ::permanent-err (a/>! ret [:err d])
                   (recur))
                 (a/>! ret [:err (InterruptedException.)])))]

    (a/go-loop [b b]
      (when-some [[typ v :as v'] (a/<! (run!))]
        (cond
          (#{::permanent-err ::ok} typ)
          (a/>! ctrl v')

          (= typ ::err)
          (if-some [b' (nxt b)]
            (do (when notify
                  (if (fn? notify)
                    (a/thread (notify v b'))
                    (a/>! notify [v b'])))
                (a/alt!
                  done nil
                  (timer (backoff b)) (recur b')))
            (a/>! ctrl [::err v])))))
    [ret ctrl]))

(defn retry
  ([op b] (retry op b {}))
  ([op b {:keys [notify timer permanent-error?] :as opts}]
   (retry* op b opts)))

(defn retry<!!
  ([op b] (retry<!! op b {}))
  ([op b {:keys [notify timer permanent-error? ctrl] :as opts}]
   (let [[ret ctrl] (retry* op b opts)
         [typ v] (try (a/<!! ret) (finally (a/close! ctrl)))]
     (when (= typ :err) (throw v))
     v)))
