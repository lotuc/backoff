(ns lotuc.backoff.protocols)

(defprotocol Backoff
  (backoff [_]
    "Returns a `long` value represents the millis duration to wait before trying
     the operation, or backoff. `nil` to indicate that no more retries should be
     made")
  (nxt [_]
    "Next backoff for next retry."))

(defprotocol Clock
  (now [_]
    "Returns current timestamp in millis (`long`)."))
