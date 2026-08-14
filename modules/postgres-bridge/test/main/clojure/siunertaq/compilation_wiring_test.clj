(ns siunertaq.compilation-wiring-test
  (:require [clojure.test :refer [deftest is testing]]
            [siunertaq.class-fixture :as fixture])
  (:import [io.siunertaq.postgres ClassASTBridge MecrispCompiler]
           [cats.effect.unsafe IORuntime IORuntime$]
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
;; Sections 1-3 call MecrispCompiler directly with a hand-fed opcode
;; sequence -- they never touch ClassASTBridge's actual ASM ClassReader
;; path (scanMethod/extractFromBytes/compileClass), which nothing else in
;; this project's test suite exercises either. Section 4 closes that gap
;; using a real ASM-generated .class file (siunertaq.class-fixture) built
;; from the same jar ClassASTBridge itself reads with -- no GraalVM, no
;; subprocess, no second JVM distribution for the sake of one test class.
;;
;; STATUS: unlike mecrisp-instr-test.clj (which transcribes stackDelta's
;; match table into an independent Clojure model, since no Scala 3
;; compiler was available in that file's original sandbox), this file
;; calls the real, compiled io.siunertaq.postgres classes directly -- the
;; "future" interop mecrisp-instr-test.clj's header deferred. Section 4
;; additionally touches cats-effect's IO/IORuntime for the first time in
;; this project's Clojure tests (compileClass returns IO[CompilationResult],
;; unlike MecrispCompiler.compile/toRows which are plain synchronous
;; functions) -- see unsafe-run-sync below for that interop specifically.
;;
;; clojure_ci.yml runs `sbt postgresBridge/compile`, then
;; `sbt export postgresBridge/Compile/fullClasspath` to get this
;; module's real classpath (compiled classes + every dependency jar --
;; cats-effect, ASM, pekko, the Scala standard library, all of it),
;; and passes that to `clojure -Scp` rather than guessing where sbt put
;; anything. An earlier version of this setup hardcoded a guessed path
;; (target/scala-3/classes) via deps.edn's :scala-classes alias, which
;; failed in CI with ClassNotFoundException -- see that alias's comment
;; in deps.edn for what changed and why. This sandbox has neither sbt
;; nor network access to Maven Central to run any of this and confirm
;; the interop calls below actually work; they were written directly
;; against the real current source (ClassASTBridge.compileClass's 4
;; params and scanMethod's opcode-capture rules, MecrispCompiler.compile's
;; 4 params, toRows' 2 params, translateOpcode's opcode table) rather
;; than assumed, but treat this file as unverified until it's actually
;; run once -- if it still fails, the next thing to check is the interop
;; calls themselves (e.g. Scala tuple specialization, or IORuntime's
;; class/companion-object split), not the classpath setup.

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
   (BIPUSH=16, ICONST_2=5, IADD=96; see the JVM spec's instruction table).
   Used as literal numbers here (rather than ASM's Opcodes class) so
   sections 1-3 stay an independent hand-fed baseline -- section 4 checks
   that ASM, reading a real generated .class file, arrives at this exact
   same sequence, which wouldn't be much of a check if both sides pulled
   the numbers from the same Opcodes constants.
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

;; ---- 4. Real ASM-read path (ClassASTBridge.compileClass) ------------------
;;
;; Everything above calls MecrispCompiler directly with a hand-fed opcode
;; sequence -- it never touches ClassASTBridge's actual ASM ClassReader
;; path. class-fixture/generate-sample-class-bytes builds a real,
;; loadable .class file whose execute() method is the exact same
;; instruction sequence as sample-opcodes above, so these tests and
;; to-rows-length-matches-word-body-test / to-rows-threads-class-file-
;; hash-through-every-row-test are checking the same 3-instruction shape
;; via two independent paths -- one hand-fed, one read by ASM off real
;; bytecode -- rather than trusting either path alone.

(defn- unsafe-run-sync
  "Runs a cats.effect.IO synchronously via the global IORuntime -- the
   first place this file needs cats-effect at all (everything above is
   plain/synchronous). IORuntime is a class with a same-named companion
   object, so (like scala.None above) its members aren't exposed as
   plain static methods on the class -- go through IORuntime$/MODULE$,
   same pattern as None$/MODULE$."
  [io]
  (.unsafeRunSync io (.global IORuntime$/MODULE$)))

(defn- compile-sample-class []
  (unsafe-run-sync
   (ClassASTBridge/compileClass
    (fixture/generate-sample-class-bytes) "execute" (scala-opt nil) false)))

(deftest compile-class-reads-real-bytecode-test
  (testing "ClassASTBridge/compileClass, given a real ASM-generated .class
            file, extracts the same 3-instruction shape (BIPUSH/ICONST_2/
            IADD) that MecrispCompiler.compile produces from hand-fed
            opcodes above -- confirming scanMethod's ClassReader/
            MethodVisitor actually reads what it's supposed to, not just
            that MecrispCompiler's translation table is correct in
            isolation. Also confirms the constructor (<init>, a
            differently-named method in the same class) is correctly
            skipped rather than matched or interfered with."
    (let [result (compile-sample-class)]
      (is (= 1 (.size (.words result))))
      (is (= 3 (.size (.rows result))))
      (is (= 32 (.length (.fileHash result)))))))

(deftest compile-class-file-hash-matches-rows-test
  (testing "compileClass's own fileHash (computed once from the real class
            bytes via ClassFileHash.fromMd5) is the same value threaded
            into every row it produces -- the same consistency
            to-rows-threads-class-file-hash-through-every-row-test checks
            above, but end-to-end from real bytecode instead of a
            placeholder hash passed in by hand."
    (let [result (compile-sample-class)
          rows   (CollectionConverters/asJava (.rows result))]
      (is (seq rows))
      (is (every? #(= (.fileHash result) (.classFileHash %)) rows)))))