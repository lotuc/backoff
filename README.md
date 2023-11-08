# backoff

This library mimics the go package
[github.com/cenkalti/backoff/v4](https://github.com/cenkalti/backoff).

## Usage

Backoff

```clojure
(require '[lotuc.backoff :as b])

;; nil represents *stop* backoff
(-> nil b/backoff)  ; => nil

;; integer/long as constant backoff
(-> 2 b/backoff)       ; => 2
(-> 2 b/nxt b/backoff) ; => 2
;; negative value *stops* backoff
(-> -1 b/backoff)      ; => nil

;; integer/long sequence as backoff
(-> (range 1 5) b/backoff)       ; => 1
(-> (range 1 5) b/nxt b/backoff) ; => 2
(-> '() b/backoff)               ; => nil

;; exponential backoff
(def b0 (b/make-exponential-back-off))
[(-> b0 b/backoff) (-> b0 b/nxt b/backoff) (-> b0 b/nxt b/nxt b/backoff)] ; => [503 1099 1128]
```

Retries

```clojure
(require '[lotuc.backoff :as b])

(let [i (atom 0)
      f (fn [] (if (< (Math/random) 0.5) @i
                   (do (swap! i inc)
                       (throw (ex-info "error" {:i @i})))))
      notify (fn [err b] (println "i:" (:i (ex-data err)) ", backoff=" (b/backoff b)))]
  (b/retry<!! f (b/make-exponential-back-off) {:notify notify}))
i: 1 , backoff= 595
i: 2 , backoff= 1108
i: 3 , backoff= 2303
;; => 3

;; setup max-retries with `with-retries`
(let [i (atom 0)
      f (fn [] (do (swap! i inc)
                   (throw (ex-info "error" {:i @i}))))
      notify (fn [err b] (println "i:" (:i (ex-data err)) ", backoff=" (b/backoff b)))]
  (b/retry<!! f (b/with-retries 100 5) {:notify notify}))
i: 1 , backoff= 100
i: 2 , backoff= 100
i: 3 , backoff= 100
i: 4 , backoff= 100
i: 5 , backoff= 100
;; => throws error
```
