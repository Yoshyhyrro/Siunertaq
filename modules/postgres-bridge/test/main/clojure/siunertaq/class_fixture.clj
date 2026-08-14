(ns siunertaq.class-fixture
  "Generates real, loadable .class bytecode via ASM's ClassWriter --
   physically, from Clojure, rather than mocking JVM classfile parsing
   or reaching for a second JDK distribution (GraalVM) just to produce
   one small .class file. org.ow2.asm:asm is already a dependency of
   postgresBridge and already on this classpath (compilation-wiring-
   test.clj's own :scala-classes / sbt-exported classpath includes it,
   the same jar ClassASTBridge.scala uses to *read* .class files) -- the
   writer half (ClassWriter) lives in that exact same jar, so this adds
   no new dependency, no subprocess, no second JVM.

   Why this matters beyond just having a fixture: nothing has ever
   exercised ClassASTBridge's actual ASM ClassReader path (scanMethod /
   extractFromBytes / compileClass) until now. compilation-wiring-
   test.clj calls MecrispCompiler.compile directly with a hand-built
   opcode sequence, which never touches ClassReader/ClassVisitor/
   MethodVisitor at all. A real generated .class file closes that gap."
  (:import [org.objectweb.asm ClassWriter Opcodes]))

(def sample-class-internal-name
  "ASM/JVM internal name (slash-separated), matching
   PostgresBridgeJob.dhall's example target path convention."
  "io/siunertaq/postgres/example/Sample")

(defn generate-sample-class-bytes
  "A minimal, real, loadable class: a public no-arg constructor, plus a
   public execute() method with body BIPUSH 5, ICONST_2, IADD, IRETURN
   (returns 7). Opcodes match compilation-wiring-test.clj's
   sample-opcodes exactly (BIPUSH=16, ICONST_2=5, IADD=96) -- the point
   is for the ASM-read path and the hand-fed-opcode path to agree on
   the same instruction sequence, not to test a different one.

   COMPUTE_MAXS | COMPUTE_FRAMES: ASM computes the stack-map table and
   max stack/locals itself, so visitMaxs' explicit arguments below are
   ignored placeholders -- still required to be called, per ASM's
   MethodVisitor contract."
  ^bytes []
  (let [cw (ClassWriter. (bit-or ClassWriter/COMPUTE_MAXS ClassWriter/COMPUTE_FRAMES))]
    (.visit cw Opcodes/V17
            (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
            sample-class-internal-name nil
            "java/lang/Object" nil)

    ;; public Sample() { super(); }
    (let [ctor (.visitMethod cw Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)]
      (.visitCode ctor)
      (.visitVarInsn ctor Opcodes/ALOAD 0)
      (.visitMethodInsn ctor Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
      (.visitInsn ctor Opcodes/RETURN)
      (.visitMaxs ctor 0 0)
      (.visitEnd ctor))

    ;; public int execute() { return 5 + 2; }  (as BIPUSH/ICONST_2/IADD/IRETURN,
    ;; not as a constant-folded ICONST_7, so ClassASTBridge sees 3 real
    ;; instructions to translate rather than javac's likely-folded 1)
    (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC "execute" "()I" nil nil)]
      (.visitCode mv)
      (.visitIntInsn mv Opcodes/BIPUSH 5)
      (.visitInsn mv Opcodes/ICONST_2)
      (.visitInsn mv Opcodes/IADD)
      (.visitInsn mv Opcodes/IRETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))

    (.visitEnd cw)
    (.toByteArray cw)))

(defn write-sample-class-file!
  "Writes generate-sample-class-bytes to disk at `path`, creating parent
   directories as needed. Not used by compilation-wiring-test.clj itself
   (that test feeds the bytes to ClassASTBridge/compileClass directly,
   in memory -- compileClass takes Array[Byte], not a file path). This
   exists for a future end-to-end smoke test of PostgresBridgeApp /
   CompileClassTasklet, which goes through compileFromClassFile and
   therefore does need a real file on disk -- PostgresBridgeJob.dhall's
   example target already points at this exact path
   (.../example/Sample.class), unwritten until something calls this."
  [^String path]
  (let [f (java.io.File. path)]
    (some-> (.getParentFile f) .mkdirs)
    (with-open [out (java.io.FileOutputStream. f)]
      (.write out (generate-sample-class-bytes)))))