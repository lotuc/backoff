(ns lotuc.backoff.retry
  (:require
   [clojure.core.async :as a]
   [lotuc.backoff.protocols :as p]))

(defn- retry*
  [f b {:keys [notify timer permanent-error? ctrl] :as opts}]
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
          (if-some [b' (p/nxt b)]
            (do (when notify
                  (if (fn? notify)
                    (a/thread (notify v b'))
                    (a/>! notify [v b'])))
                (a/alt!
                  done nil
                  (timer (p/backoff b)) (recur b')))
            (a/>! ctrl [::err v])))))
    [ret ctrl]))

(defn retry
  "Retry given operation `f` with backoff `b`.

   Returns two channels: [`ret` `ctrl`]

   - `ret` delivers two types of message
       - [`:err` `exception`]
       - [`:ok` `success-response`]
   - `ctrl` closes the channel stops the retrying process immediatelly.

  `opts`:
  - `notify` can be one of
      - (fn [exception next-backoff] ...)
      - a channel delivers [exception next-backoff]
  - `timer` (fn [^long msec] ...) a function that returns a channel that will
    close after msecs, defaults to be `clojure.core.async/timeout`.
  - `permanent-error?` (fn [ex] ...) checks if given exception is a permanent
    error. The retry process will stop on permanent error immediately."

  ([f b] (retry f b {}))
  ([f b {:keys [notify timer permanent-error?] :as opts}]
   (retry* f b opts)))

(defn retry<!!
  "Blocking wait `retry`'s result."
  ([f b] (retry<!! f b {}))
  ([f b {:keys [notify timer permanent-error? ctrl] :as opts}]
   (let [[ret ctrl] (retry* f b opts)
         [typ v] (try (a/<!! ret) (finally (a/close! ctrl)))]
     (when (= typ :err) (throw v))
     v)))
