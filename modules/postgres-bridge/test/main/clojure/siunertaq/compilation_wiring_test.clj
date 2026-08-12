(ns siunertaq.compilation-wiring-test
  (:require [clojure.test :refer [deftest is testing]])
  (:import [io.siunertaq.postgres MecrispCompiler]
           [scala None$ Some Tuple2]
           [scala.jdk.javaapi CollectionConverters]))

;; ==========================================================================
;; MecrispCompiler.compile + toRows, exercised via real JVM interop
;; ==========================================================================
;; ClassASTBridge.compileClass() used to hard-code `rows = Vector.empty`,
;; then (after a first attempted fix) called `MecrispCompiler.toRows(word)`
;; with one argument -- but toRows actually takes
;; (word: MecrispWordDef, classFileHash: String), so that call did not
;; compile at all. Both were caught by reading the source directly, not by
;; a test; this file exists so the same class of regression (an arity
;; mismatch, or rows silently going empty again) fails loudly next time.
;;
;; STATUS: unlike mecrisp-instr-test.clj (which transcribes stackDelta's
;; match table into an independent Clojure model, since no Scala 3
;; compiler was available in that file's original sandbox), this file
;; calls the real, compiled io.siunertaq.postgres.MecrispCompiler object
;; directly -- the "future" interop mecrisp-instr-test.clj's header
;; deferred. It needs:
;;   1. `sbt postgresBridge/compile` run first (produces
;;      target/scala-3/classes)
;;   2. `clojure -X:test:scala-classes` (not plain `-X:test`) so the
;;      :scala-classes alias in deps.edn puts those classes plus
;;      org.scala-lang/scala3-library_3 on the classpath
;; This sandbox has neither sbt nor network access to Maven Central to
;; actually run steps 1-2 and confirm this executes; the interop calls
;; below were written directly against MecrispCompiler.scala's real
;; current signatures (compile's 4 params, toRows' 2 params,
;; translateOpcode's opcode table) rather than assumed, but treat this as
;; unverified until it's actually run once in an environment with the
;; sbt build available -- if any interop call below has the wrong
;; signature (e.g. Scala tuple specialization), that's the first thing
;; to check.

;; ---- 1. Scala interop helpers -------------------------------------------

(defn- scala-opt
  "nil -> scala.None ; an int -> scala.Some(Integer)."
  [operand]
  (if (nil? operand)
    None$/MODULE$
    (Some. (Integer/valueOf (int operand)))))

(defn- scala-opcodes
  "[[opcode operand-or-nil] ...] -> scala.collection.immutable.Seq[(Int,
   Option[Int])], matching MecrispCompiler.compile's real 4th parameter."
  [pairs]
  (-> (java.util.ArrayList.
       (vec (for [[opcode operand] pairs]
              (Tuple2. (Integer/valueOf (int opcode)) (scala-opt operand)))))
      CollectionConverters/asScala
      .toSeq))

;; ---- 2. Fixture -----------------------------------------------------------

(def sample-opcodes
  "BIPUSH 5, ICONST_2, IADD -- raw JVM instruction-set opcode numbers
   (BIPUSH=16, ICONST_2=5, IADD=96; see the JVM spec's instruction table),
   used directly instead of ASM's Opcodes class so this test doesn't need
   ASM's jar on :scala-classes' deliberately minimal classpath.
   MecrispCompiler.scala's translateOpcode maps these to
   Literal(5), Literal(2), Plus -- no Stub, no Exit -- so hasDeadCode is
   false and word.body is exactly these 3 instructions, with no header/
   variable-declaration instructions inserted ahead of them (there is no
   VariableRef here to trigger that)."
  [[16 5] [5 nil] [96 nil]])

(def sample-hash
  "Placeholder 32-hex-char value in the same shape as a real MD5 digest
   (see ClassFileHash.scala) -- this test only checks that whatever hash
   compileClass computed is threaded through unchanged, not that it's a
   correct MD5 of anything in particular."
  "deadbeefdeadbeefdeadbeefdeadbeef")

(defn- compile-sample []
  (MecrispCompiler/compile "io/siunertaq/test/Dummy" "execute" "(I)I"
                           (scala-opcodes sample-opcodes)))

;; ---- 3. Regression guards ---------------------------------------------

(deftest to-rows-length-matches-word-body-test
  (testing "toRows produces exactly one BytecodeRow per instruction in the
            compiled word's body -- catches a regression back to the
            Vector.empty hard-code (or an empty result from any other
            silent failure)."
    (let [word (compile-sample)
          rows (MecrispCompiler/toRows word sample-hash)]
      (is (= 3 (.size (.body word))))
      (is (= (.size (.body word)) (.size rows))))))

(deftest to-rows-threads-class-file-hash-through-every-row-test
  (testing "Every row toRows produces carries the exact classFileHash it
            was called with -- catches a regression where rows are
            produced but class_file_hash silently reverts to a
            fabricated or empty value."
    (let [word (compile-sample)
          rows (CollectionConverters/asJava (MecrispCompiler/toRows word sample-hash))]
      (is (seq rows))
      (is (every? #(= sample-hash (.classFileHash %)) rows)))))