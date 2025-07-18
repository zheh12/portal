(ns tasks.deploy
  (:require [tasks.ci :refer [ci]]
            [tasks.info :refer [options]]
            [tasks.package :as pkg]
            [tasks.tools :refer [*cwd* clj gradle npx]]))

(defn- deploy-vscode []
  (binding [*cwd* "extension-vscode"]
    (npx :vsce :publish)))

(defn- deploy-open-vsx []
  (binding [*cwd* "extension-vscode"]
    (npx :ovsx :publish)))

(defn deploy-intellij []
  (binding [*cwd* "extension-intellij"]
    (gradle :publishPlugin)))

(defn- deploy-clojars []
  (pkg/pom)
  (clj "-X:deploy"
       ":installer" ":remote"
       ":artifact"  (-> options :jar-file str pr-str)
       ":pom-file"  (-> options pkg/pom-file str pr-str)))

(defn- deploy-local []
  (pkg/pom)
  (clj "-X:deploy"
       ":installer" ":local"
       ":artifact"  (-> options :jar-file str pr-str)
       ":pom-file"  (-> options pkg/pom-file str pr-str)))

(defn install
  []
  (pkg/jar)
  (deploy-local))

(defn deploy []
  (pkg/all)
  (deploy-clojars)
  (deploy-vscode)
  (deploy-open-vsx)
  (deploy-intellij))

(defn all
  "Deploy all artifacts."
  []
  (ci)
  (deploy))

(defn -main [] (deploy))
