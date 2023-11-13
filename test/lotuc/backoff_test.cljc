(ns lotuc.backoff-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing async]])
   [clojure.core.async :as a]
   [lotuc.backoff :as sut]
   [lotuc.backoff.protocols :as p]))

(def ^:const MAX_LONG 9223372036854775807) ; Long/MAX_VALUE

(defn- make-test-clock [millis]
  (reify p/Clock (now [_] millis)))

(defn- test-timer [_t]
  (a/timeout 0))

(deftest constant-backoff-test
  (testing "nil backoff"
    (is (nil? (sut/backoff nil)))
    (is (nil? (sut/nxt nil))))
  (testing "Integer/Long constant backoff"
    (is (nil? (sut/backoff -1)))
    (is (nil? (sut/nxt -1)))
    (doseq [c [(int 2) 2]]
      (loop [i 0 b' c]
        (is (= c (sut/backoff b')))
        (when (< i 3)
          (recur (inc i) (sut/nxt b'))))))
  (testing "Sequence backoff"
    (doseq [b [(range 5)
               [1 2 3]]]
      (let [s b]
        (is (nil? (sut/backoff '())))
        (loop [[s0 & sr] s b s]
          (is (= s0 (sut/backoff b)))
          (when sr
            (recur sr (sut/nxt b))))))))

(deftest with-max-retries-test
  (testing "With retries"
    (let [c 2 b (sut/with-max-retries c 1)]
      (is (= 2 (sut/backoff b)))
      (is (nil? (-> b sut/nxt sut/nxt))))))

(deftest exponential-backoff-test
  (testing "Exponential Backoff"
    (let [randomization-factor 0.1
          expected-results [500, 1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000]]
      (loop [i 0
             exp (->> {:initial-interval 500
                       :randomization-factor randomization-factor
                       :multiplier 2.0
                       :max-interval 5e3
                       :max-elapsed-time (* 15 60e3)}
                      sut/make-exponential-backoff)
             [expected & more] expected-results]
        (let [delta        (* randomization-factor expected)
              min-interval (- expected delta)
              max-interval (+ expected delta)
              actual       (sut/backoff exp)]
          (is (and (<= min-interval actual) (<= actual max-interval))
              (str i ". " actual " should be in " [min-interval max-interval])))
        (when more
          (recur (inc i) (sut/nxt exp) more)))))
  (testing "Max elapsed time"
    (let [exp (-> (sut/make-exponential-backoff
                   {:clock (make-test-clock 10001)
                    :max-elapsed-time 1000})
                  (assoc :start-time 0))]
      (is (nil? (sut/backoff (sut/nxt exp))))))
  (testing "Custom stop"
    (let [exp (-> (sut/make-exponential-backoff
                   {:clock (make-test-clock 10001)
                    :max-elapsed-time 1000
                    :stop 42})
                  (assoc :start-time 0))]
      (is (= 42 (sut/backoff (sut/nxt exp))))))
  (testing "Back off overflow"
    (let [max-interval MAX_LONG
          exp (-> (sut/make-exponential-backoff
                   {:initial-interval (/ MAX_LONG 2)
                    :max-interval max-interval
                    :multiplier 2.1})
                  (assoc :start-time 0))]
      (is (= max-interval (:current-interval (sut/nxt exp)))))))

(defn- success-on [n & [ret]]
  (let [i (atom 0)]
    [i (fn []
         (swap! i inc)
         (println "function is called" @i "time")
         (if (= @i n)
           (do (println "OK") ret)
           (do (println "error") (throw (ex-info "Error" {:i @i})))))]))

#?(:cljs (do (defonce retry-promises (atom []))
             (defn add-promise [p]
               (swap! retry-promises conj p))
             (defn await-all []
               (async done (-> (.all js/Promise (clj->js @retry-promises))
                               (.then done done)))))
   :clj (defn await-all []))

(defn- run-n-times [ntimes f]
  (doseq [i (range ntimes)]
    (f i))
  (await-all))

(defn- check-retry<!!
  [s f check-fn & [check-err on-done]]
  (let [build-f (fn [typ f] (fn [& args] (when f (testing (str s "-" typ) (apply f args)))))
        _throw? (nil? check-err)
        check-fn (build-f "ok" check-fn)
        check-err (build-f "err" check-err)
        on-done (build-f "done" on-done)]
    #?(:clj (try (check-fn (f))
                 (on-done)
                 (catch Exception e
                   (check-err e)
                   (on-done)
                   (when _throw? (throw e))))
       :cljs (-> (f)
                 (.then check-fn check-err)
                 (.then on-done)
                 add-promise))))

(deftest retry-test
  (testing "retry"
    (let [n 3
          [retries f] (success-on n)
          [ret _] (sut/retry f (sut/make-exponential-backoff)
                             {:timer test-timer})]
      #?(:clj (do (a/<!! ret)
                  (is (= n @retries)))
         :cljs (async done
                      (a/go (a/<! ret)
                            (is (= n @retries))
                            (done)))))))

(deftest retry<!!-test
  (testing "retry<!!"
    (let [n 3
          expect 42
          [retries f] (success-on n expect)]
      (check-retry<!!
       (str "success-on " n)
       #(sut/retry<!! f (sut/make-exponential-backoff)
                      {:timer test-timer})
       (fn [ret]
         (is (= n @retries))
         (is (= expect ret)))))
    (await-all)))

(deftest retry-function-noitfy-test
  (testing "notify: function callback"
    (run-n-times
     10
     (fn [test-i]
       (let [n 3
             expect 42
             notifies (atom 0)
             notify (fn [_err _next-backoff]
                      (swap! notifies inc))
             [retries f] (success-on n expect)]
         (check-retry<!!
          (str "notify: function callback - " test-i)
          #(sut/retry<!! f (sut/make-exponential-backoff)
                         {:timer test-timer
                          :notify notify})
          (fn [ret]
            (is (= n @retries))
            (is (= expect ret))
            (is (= (dec n) @notifies)))))))))

(deftest retry-channel-noitfy-test
  (testing "notify: core.async channel"
    (let [n 3
          expect 42
          notifies (atom 0)
          notify (a/chan)
          _ (a/go-loop []
              (when-some [[_err _next-backoff] (a/<! notify)]
                (swap! notifies inc)
                (recur)))
          [retries f] (success-on n expect)]
      (check-retry<!!
       "notify: core.async channel"
       #(sut/retry<!! f (sut/make-exponential-backoff)
                      {:timer test-timer
                       :notify notify})
       (fn [ret]
         (a/close! notify)
         (is (= n @retries))
         (is (= expect ret))
         (is (= (dec n) @notifies)))))))

(deftest stop-retry-test
  (testing "stop before backoff retry stops"
    (run-n-times
     100
     (fn [test-i]
       (let [cancel-on 3
             i (atom 0)
             ctrl (a/chan)
             f (fn []
                 (swap! i inc)
                 (println "function is called" @i "time")
                 (if (= @i cancel-on)
                   (a/close! ctrl)
                   (throw (ex-info "Error" {:i @i}))))
             opts {:ctrl ctrl
                   :timer test-timer}]
         (check-retry<!!
          (str "stop before backoff retry stops -" test-i)
          #(sut/retry<!! f (sut/make-exponential-backoff) opts)
          (fn [_ret])
          (fn [err] (is (some? err)))))))))

(deftest stop-on-permanent-error-test
  (testing "stop on permanent error:"
    (run-n-times
     100
     (fn [test-i]
       (letfn [(throw-permanent  [m] (throw (ex-info m {:permanent true})))
               (permanent-error? [e] (:permanent (ex-data e)))]
         (doseq [{:keys [test-name f should-retry res err]}
                 [{:test-name "nil test"
                   :f (fn [] 1)
                   :should-retry false
                   :res 1}
                  {:test-name "non permanent"
                   :f (fn [] (throw (ex-info "non permanent" {})))
                   :should-retry true
                   :err #"exceeds max retries"}
                  {:test-name "permanent"
                   :f (fn [] (throw-permanent "permanent message"))
                   :should-retry false
                   :err #"permanent message"}]]
           (testing (str test-name " - " test-i)
             (let [num-retries (atom -1)
                   max-retries 1
                   f' #(do (swap! num-retries inc)
                           (if (>= @num-retries max-retries)
                             (throw-permanent "exceeds max retries")
                             (f)))
                   b (sut/make-exponential-backoff)
                   opts {:permanent-error? permanent-error?
                         :timer test-timer}]
               (check-retry<!!
                (str test-name " - " test-i)
                #(sut/retry<!! f' b opts)
                (fn [r] (when res (is (= res r))))
                (fn [e] (when err (is (re-matches err (ex-message e)))))
                (fn []
                  (if should-retry
                    (is (pos? @num-retries) "backoff should have retried")
                    (is (zero? @num-retries) "backoff should not have retried"))))))))))))
