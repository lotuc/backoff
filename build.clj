(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deps-deploy]))

(def lib 'org.lotuc/backoff)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(def pom-file "target/classes/META-INF/maven/org.lotuc/backoff/pom.xml")
(def uber-file "target/lotuc-backoff.jar")
(def scm {:url "https://github.com/lotuc/backoff"
          :connection "scm:git:git://github.com/lotuc/backoff.git"
          :developerConnection "scm:git:ssh://git@github.com/lotuc/backoff.git"
          :tag (b/git-process {:git-args "rev-parse HEAD"})})

(defn clean [_]
  (b/delete {:path "target"}))

(defn prep [_]
  (-> {:class-dir class-dir
       :lib lib
       :version version
       :basis basis
       :src-pom "./pom0.xml"
       :src-dirs ["src"]
       :scm scm}
      b/write-pom)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir}))

(defn uber [_]
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))

(defn build [_]
  (clean nil) (prep nil) (uber nil))

(defn deploy [_]
  (when (:build _)
    (build _))

  ;; clj -T:build deploy :installer :remote
  (when-not (.exists (io/file uber-file))
    (println "file not found:" uber-file)
    (println "build first: clj -T:build build")
    (System/exit 1))

  (when-not (.exists (io/file pom-file))
    (println "file not found:" pom-file)
    (println "build first: clj -T:build build")
    (System/exit 1))

  (io/copy (io/file pom-file) (io/file "pom.xml"))

  (-> {:installer (or (:installer _) :local)
       :sign-releases? false
       :artifact uber-file}
      deps-deploy/deploy))
