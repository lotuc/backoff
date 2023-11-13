#!/bin/sh

# core.async declared its Clojure dependencies to be 1.10, so here we only test
# over 1.10 and higher versions of Clojure
#
# https://github.com/clojure/core.async/blob/master/project.clj

versions="1.10 1.11"
for v in $versions
do
  clojure -M:test:"$v"
done

# ClojureScript
clojure -M:cljs-test
