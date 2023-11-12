(ns lotuc.backoff-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [lotuc.backoff :as sut]
   [lotuc.backoff.protocols :as p]))

(defmacro testing* [ntimes string & body]
  `(testing ~string
     (doseq [i# (range ~ntimes)]
       (testing (str "[" i# "]")
         ~@body))))

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
                      sut/make-exponential-back-off)
             [expected & more] expected-results]
        (let [delta        (* randomization-factor expected)
              min-interval (- expected delta)
              max-interval (+ expected delta)
              actual       (.backoff exp)]
          (is (and (<= min-interval actual) (<= actual max-interval))
              (str i ". " actual " should be in " [min-interval max-interval])))
        (when more
          (recur (inc i) (.nxt exp) more)))))
  (testing "Max elapsed time"
    (let [exp (-> (sut/make-exponential-back-off
                   {:clock (make-test-clock 10001)
                    :max-elapsed-time 1000})
                  (assoc :start-time 0))]
      (is (nil? (.backoff (.nxt exp))))))
  (testing "Custom stop"
    (let [exp (-> (sut/make-exponential-back-off
                   {:clock (make-test-clock 10001)
                    :max-elapsed-time 1000
                    :stop 42})
                  (assoc :start-time 0))]
      (is (= 42 (.backoff (.nxt exp))))))
  (testing "Back off overflow"
    (let [max-interval Long/MAX_VALUE
          exp (-> (sut/make-exponential-back-off
                   {:initial-interval (/ Long/MAX_VALUE 2)
                    :max-interval max-interval
                    :multiplier 2.1})
                  (assoc :start-time 0))]
      (is (= max-interval (:current-interval (.nxt exp)))))))

(defn- success-on [n & [ret]]
  (let [i (atom 0)]
    [i (fn []
         (swap! i inc)
         (println (format "function is called %s time" @i))
         (if (= @i n)
           (do (println "OK") ret)
           (do (println "error") (throw (ex-info "Error" {:i @i})))))]))

(deftest retry-test
  (testing "retry"
    (let [n 3
          [retries f] (success-on n)
          [ret _] (sut/retry f (sut/make-exponential-back-off)
                             {:timer test-timer})]
      (a/<!! ret)
      (is (= n @retries))))
  (testing "retry<!!"
    (let [n 3
          expect 42
          [retries f] (success-on n expect)
          ret (sut/retry<!! f (sut/make-exponential-back-off)
                            {:timer test-timer})]
      (is (= n @retries))
      (is (= expect ret)))))

(deftest retry-noitfy-test
  (testing*
   10 "notify: function callback"

   (let [n 3
         expect 42
         notifies (atom 0)
         notify (fn [_err _next-backoff]
                  (swap! notifies inc))
         [retries f] (success-on n expect)
         ret (sut/retry<!! f (sut/make-exponential-back-off)
                           {:timer test-timer
                            :notify notify})]
     (is (= n @retries))
     (is (= expect ret))
     (is (= (dec n) @notifies))))

  (testing "notify: core.async channel"
    (let [n 3
          expect 42
          notifies (atom 0)
          notify (a/chan)
          _ (a/go-loop []
              (when-some [[_err _next-backoff] (a/<! notify)]
                (swap! notifies inc)
                (recur)))
          [retries f] (success-on n expect)
          ret (sut/retry<!! f (sut/make-exponential-back-off)
                            {:timer test-timer
                             :notify notify})]
      (a/close! notify)
      (is (= n @retries))
      (is (= expect ret))
      (is (= (dec n) @notifies)))))

(deftest stop-retry-test
  (testing*
   100 "stop before backoff retry stops"

   (let [cancel-on 3
         i (atom 0)
         ctrl (a/chan)
         f (fn []
             (swap! i inc)
             (println (format "function is called %s time" @i))
             (if (= @i cancel-on)
               (a/close! ctrl)
               (throw (ex-info "Error" {:i @i}))))
         opts {:ctrl ctrl
               :timer test-timer}]
     (is (thrown? InterruptedException
                  (sut/retry<!! f (sut/make-exponential-back-off)
                                opts))))))

(deftest stop-on-permanent-error-test
  (testing*
   100 "stop on permanent error:"

   (letfn [(throw-permanent  [m] (throw (ex-info m {:permanent true})))
           (permanent-error? [e] (:permanent (ex-data e)))]
     (doseq [{:keys [test-name f should-retry res err]}
             [{:test-name "nil test"
               :f (fn [] 1)
               :should-retry false
               :res 1}
              {:test-name "non permanent"
               :f (fn [] (throw (RuntimeException. "non permanent")))
               :should-retry true
               :err #"exceeds max retries"}
              {:test-name "permanent"
               :f (fn [] (throw-permanent "permanent message"))
               :should-retry false
               :err #"permanent message"}]]
       (testing test-name
         (let [num-retries (atom -1)
               max-retries 1
               f' #(do (swap! num-retries inc)
                       (if (>= @num-retries max-retries)
                         (throw-permanent "exceeds max retries")
                         (f)))
               b (sut/make-exponential-back-off)
               opts {:permanent-error? permanent-error?
                     :timer test-timer}]
           (try
             (let [r (sut/retry<!! f' b opts)]
               (when res (is (= res r))))
             (catch Exception e
               (when err (is (re-matches err (ex-message e))))))
           (if should-retry
             (is (pos? @num-retries) "backoff should have retried")
             (is (zero? @num-retries) "backoff should not have retried"))))))))
