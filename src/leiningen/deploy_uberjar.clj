(ns leiningen.deploy-uberjar
  (:require [leiningen.uberjar :as luber]
            [leiningen.jar :as jar]
            [clojure.java.io :as io]
            [leiningen.core.main :as main :refer [info]]
            [leiningen.pom :as lpom]
            [leiningen.deploy :as ldeploy]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [cemerick.pomegranate.aether :as aether])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(defn exit [message]
  (info message)
  (System/exit 1))

(defn repo-target?
  "verify the given repository is defined in the project"
  [project repo]
  (some #(= repo %)
        (concat (map first (:deploy-repositories project))
                (map first (:repositories project)))))

(defn verify-repo [project repo]
  (when-not (repo-target? project repo)
    (exit (format "Could not find the target repository: %s." repo))))

(defn get-group-name [project]
  (format "%s/%s" (:group project) (:name project)))

(defn insert [v pos item]
  (into [] (apply conj (subvec v 0 pos) item (subvec v pos))))

(defn copy-file [orig-path orig-file dest-path dest-file]
  (io/copy (io/file (format "%s/%s" orig-path orig-file))
           (io/file (format "%s/%s" dest-path dest-file)))
  (format "%s/%s" dest-path dest-file))

(defn zip
  "Writes the contents of input to output, compressed.
  input: something which can be copied from by io/copy.
  output: something which can be opend by io/output-stream.
  The bytes written to the resulting stream will be gzip compressed."
  [input output & opts]
  (with-open [zos (-> output io/output-stream ZipOutputStream.)]
    (.putNextEntry zos (ZipEntry. (-> input java.io.File. .getName)))
    (apply io/copy (io/file input) zos opts))
    output)

(defn get-uberjar-path [{:keys [version target-path] {{:keys [uberjar-name]} :uberjar} :profiles :as project}]
  (let [uj-file (string/join "-" (-> uberjar-name
                                     (string/split #"-")
                                     (insert 1 version)))]
    (copy-file target-path uberjar-name target-path uj-file)))

(defn find-regular-jar [{:keys [target-path name version]}]
  (let [p (re-pattern (str name "-" version ".jar"))]
    (first
      (filter #(re-matches p %) (seq (.list (io/file target-path)))))))

(defn uberjar? [uberjar]
  (.exists (io/as-file uberjar)))

(defn files-for [project repo uberjar-gz]
  (merge (jar/jar project)
         {[:extension "pom"] (lpom/pom project)}
         {[:extension "zip"] uberjar-gz}))

(defn deploy-uberjar
  "Deploy uberjar to the given repository"
  [{group :group target-path :target-path version :version :as project} & args]
  (let [repo    (first args)
        regular-jar (str target-path "/" (find-regular-jar project))
        uberjar (-> project get-uberjar-path)
        uberjar-gz (zip uberjar (str uberjar ".zip"))]
    (verify-repo project repo)
    (if (uberjar? uberjar)
      (aether/deploy
         :coordinates  [(symbol group (:name project)) version]
         :artifact-map (files-for (assoc project :auto-clean false) repo uberjar-gz)
         :transfer-listener :stdout
         :repository   [(ldeploy/repo-for project repo)])
      (exit "uberjar does not exist, try lein uberjar first"))))
