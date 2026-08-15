(ns siunertaq.postgres-bridge-job-test
  "Validates PostgresBridgeJob.dhall against the exact production loading
   path PostgresBridgeApp.scala uses at startup:
   DhallBatchRegistry.evalDhall (modules/dhall-bridge, shells out to
   dhall-to-json) followed by PostgresBridgeJobDef.decodeJson. Nothing
   validated this Dhall file at all before this test existed -- a syntax
   error, a type mismatch, or a field-name typo (this file's JSON keys
   are snake_case; ClassCompilationTarget's Decoder reads
   class_file_path/target_method/target_descriptor/allow_stubs
   specifically) would previously only surface by actually running
   PostgresBridgeApp against a live ClickHouse.

   Needs dhall-to-json on $PATH -- clojure_ci.yml installs it for this
   job the same way it already does for dhall-bridge's own tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [io.siunertaq.batch DhallBatchRegistry]
           [io.siunertaq.postgres PostgresBridgeJobDef]
           [cats.effect.unsafe IORuntime IORuntime$]
           [scala.jdk.javaapi CollectionConverters]))

(defn- unsafe-run-sync
  "Same IORuntime$/MODULE$ pattern as compilation-wiring-test.clj's
   helper of the same name -- see that file for why the class/companion-
   object split makes this necessary rather than a plain static call."
  [io]
  (.unsafeRunSync io (.global IORuntime$/MODULE$)))

(def dhall-path
  "modules/postgres-bridge/src/main/resources/PostgresBridgeJob.dhall")

(defn- load-job-def []
  (let [expr (slurp dhall-path)
        json (unsafe-run-sync (DhallBatchRegistry/evalDhall expr))]
    (PostgresBridgeJobDef/decodeJson json)))

(deftest postgres-bridge-job-dhall-parses-test
  (testing "PostgresBridgeJob.dhall type-checks as valid Dhall and its
            dhall-to-json output decodes into PostgresBridgeJobDef
            without error, with at least one target declared."
    (let [job-def (load-job-def)]
      (is (= "postgres-bridge-compile" (.jobName job-def)))
      (is (pos? (.size (.targets job-def)))))))

(deftest postgres-bridge-job-dhall-targets-well-formed-test
  (testing "Every declared target has a non-blank class_file_path and
            target_method -- catches an empty-string typo that would
            still type-check as valid Dhall/JSON but fail loudly (or
            silently no-op) much later inside CompileClassTasklet,
            rather than here."
    (let [targets (CollectionConverters/asJava (.targets (load-job-def)))]
      (is (seq targets))
      (doseq [t targets]
        (is (not (str/blank? (.classFilePath t))))
        (is (not (str/blank? (.targetMethod t))))))))