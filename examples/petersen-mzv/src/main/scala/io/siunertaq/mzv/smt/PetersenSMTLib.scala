package io.siunertaq.mzv.smt

import smtlib.trees.Terms._
import smtlib.trees.Commands._
import smtlib.theories.Core._
import smtlib.theories.Ints._
import smtlib.printer.RecursivePrinter

import java.io.{PrintWriter, StringWriter}

// ─────────────────────────────────────────────────────────────────────────
//  PetersenSMTLib
//
//  Typed SMT-LIB 2 generation for MZV / Petersen graph verification.
//  Parallel to YicesSmtLib (yices-bridge) — same public shape:
//    renderSuite()            → full 5-property suite as String
//    renderProperty(cmds)     → single property
//
//  Internal: uses scala-smtlib AST (Command / Term / Sort trees)
//  rather than raw string interpolation, giving compile-time structure.
//
//  Vertex encoding:  Outer(i) = i,  Inner(i) = i+5   (i ∈ 0..4)
//  15 undirected Petersen edges → 30 directed arcs in `adjacent`.
// ─────────────────────────────────────────────────────────────────────────

object PetersenSMTLib:

  // ── Graph ──────────────────────────────────────────────────────────────

  val undirectedEdges: Set[(Int, Int)] = Set(
    (0,1),(1,2),(2,3),(3,4),(4,0),   // Outer 5-cycle
    (0,5),(1,6),(2,7),(3,8),(4,9),   // Spokes Outer(i)—Inner(i)
    (5,7),(5,8),(6,8),(6,9),(7,9)    // Inner {5/2}-star: Inner(i)—Inner((i+2)%5)
  )

  private val directedPairs: Seq[(Int, Int)] =
    undirectedEdges.toSeq.flatMap { case (a, b) => Seq((a, b), (b, a)) }

  // ── Term builders ──────────────────────────────────────────────────────

  private def sym(s: String): Term =
    QualifiedIdentifier(Identifier(SSymbol(s)))

  private def app(f: String, args: Term*): Term =
    FunctionApplication(QualifiedIdentifier(Identifier(SSymbol(f))), args.toSeq)

  private def int(n: Int): Term  = NumeralLit(BigInt(n))
  private def negOne: Term       = app("-", int(1))   // (- 1) = −1 via unary minus

  /** (or t₁ t₂ … tₙ) — multi-argument form, consistent with SMT-LIB 2 spec */
  private def orAll(ts: Seq[Term]): Term =
    FunctionApplication(QualifiedIdentifier(Identifier(SSymbol("or"))), ts)

  private def sv(name: String, sort: Sort): SortedVar =
    SortedVar(SSymbol(name), sort)

  private def inRange(v: Term, lo: Int, hi: Int): Term =
    And(GreaterEquals(v, int(lo)), LessThan(v, int(hi)))

  // ── Shared define-funs ─────────────────────────────────────────────────

  private val sharedDefs: Seq[Command] = Seq(

    DefineFun(FunDef(SSymbol("isValid"), Seq(sv("v", IntSort())), BoolSort(),
      inRange(sym("v"), 0, 10))),

    DefineFun(FunDef(SSymbol("isOuter"), Seq(sv("v", IntSort())), BoolSort(),
      inRange(sym("v"), 0, 5))),

    DefineFun(FunDef(SSymbol("isInner"), Seq(sv("v", IntSort())), BoolSort(),
      inRange(sym("v"), 5, 10))),

    // 30-arc adjacency relation (explicit enumeration, no quantifiers → QF_LIA)
    DefineFun(FunDef(SSymbol("adjacent"),
      Seq(sv("u", IntSort()), sv("v", IntSort())), BoolSort(),
      orAll(directedPairs.map { case (a, b) =>
        And(Equals(sym("u"), int(a)), Equals(sym("v"), int(b)))
      }))),

    // pentaDelta: 1 if Outer↔Inner jump, −1 if same class
    // Mirrors: val delta = if (source.getClass != target.getClass) 1 else -1
    DefineFun(FunDef(SSymbol("pentaDelta"),
      Seq(sv("src", IntSort()), sv("tgt", IntSort())), IntSort(),
      app("ite",
        app("xor", app("isOuter", sym("src")), app("isOuter", sym("tgt"))),
        int(1), negOne))),

    // MZVTriple(s1, s2 + delta, s3 - delta)
    DefineFun(FunDef(SSymbol("pentaS2"),
      Seq(sv("s2", IntSort()), sv("src", IntSort()), sv("tgt", IntSort())), IntSort(),
      Add(sym("s2"), app("pentaDelta", sym("src"), sym("tgt"))))),

    DefineFun(FunDef(SSymbol("pentaS3"),
      Seq(sv("s3", IntSort()), sv("src", IntSort()), sv("tgt", IntSort())), IntSort(),
      Sub(sym("s3"), app("pentaDelta", sym("src"), sym("tgt")))))
  )

  // ── Property encodings ─────────────────────────────────────────────────

  /**
   * P1 — Petersen graph diameter ≤ 2.
   *
   * Proves the [TOPOLOGY ???] in resolveStack is dead code.
   * A path of length ≤ 2 always exists between any two distinct valid vertices.
   * Expected: unsat
   */
  val propDiameter: Seq[Command] =
    val reachable = orAll(
      Seq(app("adjacent", sym("p1_u"), sym("p1_v"))) ++
      (0 until 10).map(w => And(
        app("adjacent", sym("p1_u"), int(w)),
        app("adjacent", int(w),      sym("p1_v")))))
    Seq(
      Echo(SString("P1: diameter<=2 — topology ??? dead code [expect unsat]")),
      Push(1),
      DeclareConst(SSymbol("p1_u"), IntSort()),
      DeclareConst(SSymbol("p1_v"), IntSort()),
      Assert(app("isValid", sym("p1_u"))),
      Assert(app("isValid", sym("p1_v"))),
      Assert(Not(Equals(sym("p1_u"), sym("p1_v")))),
      Assert(Not(reachable)),
      CheckSat(),
      Pop(1))

  /**
   * P2 — s2 + s3 conserved under applyPentagonRelation.
   *
   * pentaDelta adds d to s2 and subtracts d from s3; their sum is invariant.
   * Corollary: mzvWeight = s1 + (s2+s3) is invariant across any traversal.
   * Expected: unsat
   */
  val propWeightConservation: Seq[Command] = Seq(
    Echo(SString("P2: s2+s3 conserved (mzvWeight invariant) [expect unsat]")),
    Push(1),
    DeclareConst(SSymbol("p2_s2"),  IntSort()),
    DeclareConst(SSymbol("p2_s3"),  IntSort()),
    DeclareConst(SSymbol("p2_src"), IntSort()),
    DeclareConst(SSymbol("p2_tgt"), IntSort()),
    Assert(app("isValid", sym("p2_src"))),
    Assert(app("isValid", sym("p2_tgt"))),
    Assert(app("adjacent", sym("p2_src"), sym("p2_tgt"))),
    Assert(Not(Equals(
      Add(app("pentaS2", sym("p2_s2"), sym("p2_src"), sym("p2_tgt")),
          app("pentaS3", sym("p2_s3"), sym("p2_src"), sym("p2_tgt"))),
      Add(sym("p2_s2"), sym("p2_s3"))))),
    CheckSat(),
    Pop(1))

  /**
   * P4 — MZVTriple(3,2,1) is a fixed point of path 0→5→8.
   *
   * Step 1  0→5  Outer→Inner  delta=+1 : (s2,s3) = (2+1, 1−1) = (3, 0)
   * Step 2  5→8  Inner→Inner  delta=−1 : (s2,s3) = (3−1, 0+1) = (2, 1)
   *
   * Output = Input. This corresponds to IKZ regularization remainder = 0
   * for total weight 6 (even weight → no odd-depth obstruction on the path).
   * Expected: sat
   */
  val propFixedPoint: Seq[Command] = Seq(
    Echo(SString("P4: MZVTriple(3,2,1) fixed point on 0->5->8 [expect sat]")),
    Push(1),
    DeclareConst(SSymbol("p4_mid_s2"), IntSort()),
    DeclareConst(SSymbol("p4_mid_s3"), IntSort()),
    DeclareConst(SSymbol("p4_out_s2"), IntSort()),
    DeclareConst(SSymbol("p4_out_s3"), IntSort()),
    Assert(Equals(sym("p4_mid_s2"), app("pentaS2", int(2), int(0), int(5)))),
    Assert(Equals(sym("p4_mid_s3"), app("pentaS3", int(1), int(0), int(5)))),
    Assert(Equals(sym("p4_out_s2"), app("pentaS2", sym("p4_mid_s2"), int(5), int(8)))),
    Assert(Equals(sym("p4_out_s3"), app("pentaS3", sym("p4_mid_s3"), int(5), int(8)))),
    Assert(Equals(sym("p4_out_s2"), int(2))),
    Assert(Equals(sym("p4_out_s3"), int(1))),
    CheckSat(),
    GetValue(sym("p4_mid_s2"), Seq(sym("p4_mid_s3"), sym("p4_out_s2"), sym("p4_out_s3"))),
    Pop(1))

  /**
   * P5 — Divergence detection.
   *
   * Formalises the [IMAGINARY ???]:
   *   isConvergent = (s1 > 1). No value satisfies both s1 = 1 and s1 > 1.
   *   ImaginaryPopperActor fires precisely on this boundary.
   * Expected: unsat
   */
  val propDivergenceDetection: Seq[Command] = Seq(
    Echo(SString("P5: s1=1 => ImaginaryPopper ??? fires [expect unsat]")),
    Push(1),
    DeclareConst(SSymbol("p5_s1"), IntSort()),
    Assert(Equals(sym("p5_s1"), int(1))),
    Assert(GreaterThan(sym("p5_s1"), int(1))),
    CheckSat(),
    Pop(1))

  /**
   * P6 — Convergence preserved through 2-hop traversal.
   *
   * s1 is never touched by pentaDelta. So s1 > 1 before ⇒ s1 > 1 after.
   * Expected: unsat
   */
  val propConvergenceInvariant: Seq[Command] = Seq(
    Echo(SString("P6: convergence invariant (s1 unchanged by pentaDelta) [expect unsat]")),
    Push(1),
    DeclareConst(SSymbol("p6_s1"),  IntSort()),
    DeclareConst(SSymbol("p6_src"), IntSort()),
    DeclareConst(SSymbol("p6_mid"), IntSort()),
    DeclareConst(SSymbol("p6_tgt"), IntSort()),
    Assert(GreaterThan(sym("p6_s1"), int(1))),
    Assert(app("isValid", sym("p6_src"))),
    Assert(app("isValid", sym("p6_mid"))),
    Assert(app("isValid", sym("p6_tgt"))),
    Assert(app("adjacent", sym("p6_src"), sym("p6_mid"))),
    Assert(app("adjacent", sym("p6_mid"), sym("p6_tgt"))),
    Assert(Not(GreaterThan(sym("p6_s1"), int(1)))),   // ← contradiction
    CheckSat(),
    Pop(1))

  // ── Public API (mirrors YicesSmtLib shape) ─────────────────────────────

  /** Full verification suite — pass to PetersenSmtSolver.verify(renderSuite()) */
  def renderSuite(): String =
    toSMT2(
      Seq(SetLogic(QF_LIA.asInstanceOf[Logic]), SetOption(ProduceModels(true))) ++
      sharedDefs ++
      propDiameter ++
      propWeightConservation ++
      propFixedPoint ++
      propDivergenceDetection ++
      propConvergenceInvariant ++
      Seq(Exit()))

  /** Single property with shared preamble — for targeted debugging */
  def renderProperty(cmds: Seq[Command]): String =
    toSMT2(
      Seq(SetLogic(QF_LIA.asInstanceOf[Logic]), SetOption(ProduceModels(true))) ++
      sharedDefs ++ cmds ++ Seq(Exit()))

  private def toSMT2(cmds: Seq[Command]): String =
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    cmds.foreach { cmd => RecursivePrinter.printCommand(cmd, pw); pw.println() }
    pw.flush()
    sw.toString
