(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/reshis-2026.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj
   {:basis @basis
    :ns-compile '[app.main
                  app.acl
                  app.mutations.scenario
                  app.mutations.user
                  app.resolvers.route
                  app.resolvers.user
                  app.reitit.middleware
                  app.reitit.handlers.html.route
                  app.reitit.handlers.html.user
                  scriptorium.system
                  scriptorium.registry
                  scriptorium.datahike.conn
                  scriptorium.datahike.transaction
                  scriptorium.datahike.env
                  scriptorium.datahike.pathom.resolvers
                  scriptorium.datahike.pathom.mutations
                  scriptorium.eacl
                  scriptorium.eacl.pathom
                  scriptorium.pathom.resolvers
                  scriptorium.pathom.env
                  scriptorium.reitit.ring
                  scriptorium.reitit.malli
                  scriptorium.http-kit]
    :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber-file :basis @basis :main 'app.main})
  (println uber-file))
