package io.siunertaq.postgres

import cats.effect.IO

// ─── DeadCodeAnalyzer ────────────────────────────────────────────────────────
//
//  Static dead code analysis over compiled Mecrisp words.
//
//  Three levels of dead code:
//
//  Level 1 — INTRA-PROCEDURAL: instructions after EXIT/RETURN within a word
//             Detected in MecrispCompiler (pass 3, is_after_return flag)
//
//  Level 2 — INTER-PROCEDURAL: Forth words defined but never called
//             Detected here via call graph reachability from known entry points
//
//  Level 3 — LOOP-INVARIANT dead code (future; requires data-flow analysis)
//
//  Input:  Vector[MecrispWordDef]  (from ClassASTBridge.compileClass)
//  Output: DeadCodeReport
//
//  ClickHouse queries for Level 2 are in clickhouse_schema.sql (dead_forth_words view).
//  This Scala implementation handles the offline / in-process version.

object DeadCodeAnalyzer:

  // ─── §1  Call graph ───────────────────────────────────────────────────────

  /** Directed call graph: caller → set of callees. */
  type CallGraph = Map[String, Set[String]]

  def buildCallGraph(words: Vector[MecrispWordDef]): CallGraph =
    words.map(w => w.name -> w.directCallees).toMap

  /** Transitive closure: all words reachable from a set of entry points. */
  def reachableFrom(
    graph:       CallGraph,
    entryPoints: Set[String]
  ): Set[String] =
    var visited = Set.empty[String]
    val queue   = scala.collection.mutable.Queue.from(entryPoints)
    while queue.nonEmpty do
      val word = queue.dequeue()
      if !visited.contains(word) then
        visited += word
        graph.getOrElse(word, Set.empty).foreach(queue.enqueue)
    visited

  // ─── §2  Dead code report ─────────────────────────────────────────────────

  final case class DeadWord(
    name:             String,
    sourceClass:      String,
    sourceMethod:     String,
    instructionCount: Int,
    reason:           String                   // why it's considered dead
  )

  final case class DeadInstruction(
    wordName:       String,
    instructionIdx: Int,
    token:          String,
    reason:         String
  )

  final case class DeadCodeReport(
    deadWords:        Vector[DeadWord],
    deadInstructions: Vector[DeadInstruction],
    totalWords:       Int,
    reachableWords:   Int,
    totalInstrs:      Int,
    deadInstrCount:   Int
  ):
    def deadWordPct:  Double = if totalWords  == 0 then 0.0 else deadWords.size * 100.0 / totalWords
    def deadInstrPct: Double = if totalInstrs == 0 then 0.0 else deadInstrCount * 100.0 / totalInstrs

    def summary: String =
      s"""Dead Code Report
         |================
         |Words:        $reachableWords / $totalWords reachable (${f"${100.0 - deadWordPct}%.1f"}% live)
         |Instructions: ${totalInstrs - deadInstrCount} / $totalInstrs reachable (${f"${100.0 - deadInstrPct}%.1f"}% live)
         |
         |Dead words (Level 2 — never called):
         |${deadWords.map(w => s"  ${w.name}  [${w.instructionCount} instrs, ${w.reason}]").mkString("\n")}
         |
         |Dead instructions (Level 1 — after EXIT):
         |  ${deadInstructions.size} unreachable instructions across ${deadInstructions.map(_.wordName).distinct.size} words
         |""".stripMargin

  // ─── §3  Analysis entry point ─────────────────────────────────────────────

  /**
   * Analyse a set of compiled words for dead code.
   *
   * @param words        compiled Mecrisp words (all loaded)
   * @param entryPoints  known live entry points (e.g. "main", job step names)
   *                     Default: class.main method, and any word whose name
   *                     ends with "-execute" or "-run" (Spring Batch Tasklet convention)
   */
  def analyze(
    words:       Vector[MecrispWordDef],
    entryPoints: Set[String] = Set.empty
  ): DeadCodeReport =

    val graph       = buildCallGraph(words)
    val wordNames   = graph.keySet

    // Auto-detect entry points if none given
    val autoEntries = wordNames.filter { name =>
      name.endsWith("-execute") || name.endsWith("-run") ||
      name.endsWith("-main")    || name.endsWith("-init")
    }
    val entries = entryPoints ++ autoEntries

    val reachable   = reachableFrom(graph, entries)
    val deadWordSet = wordNames -- reachable

    // Level 2: unreachable words
    val deadWords = words
      .filter(w => deadWordSet.contains(w.name))
      .map(w => DeadWord(
        name             = w.name,
        sourceClass      = w.sourceClass,
        sourceMethod     = w.sourceMethod,
        instructionCount = w.body.size,
        reason           = if entries.isEmpty
                           then "no entry points detected"
                           else s"unreachable from ${entries.size} entry point(s)"
      ))

    // Level 1: intra-procedural dead instructions
    val deadInstrs = words.flatMap { w =>
      val exitIdx = w.body.indexWhere(_ == MecrispInstr.Exit)
      if exitIdx < 0 || exitIdx >= w.body.size - 1 then Vector.empty
      else
        w.body.slice(exitIdx + 1, w.body.size).zipWithIndex.map { (instr, offset) =>
          DeadInstruction(
            wordName       = w.name,
            instructionIdx = exitIdx + 1 + offset,
            token          = MecrispInstr.toToken(instr),
            reason         = "after EXIT (unreachable)"
          )
        }
    }

    val totalInstrs = words.map(_.body.size).sum

    DeadCodeReport(
      deadWords        = deadWords,
      deadInstructions = deadInstrs,
      totalWords       = words.size,
      reachableWords   = reachable.size,
      totalInstrs      = totalInstrs,
      deadInstrCount   = deadInstrs.size
    )

  // ─── §4  IO wrapper ───────────────────────────────────────────────────────

  def analyzeIO(
    words:       Vector[MecrispWordDef],
    entryPoints: Set[String] = Set.empty
  ): IO[DeadCodeReport] =
    IO.delay(analyze(words, entryPoints))

  // ─── §5  ClickHouse query helper (run via HTTP) ───────────────────────────
  //
  //  These queries are equivalent to the dead_forth_words VIEW in clickhouse_schema.sql
  //  but can be run on-demand against a live ClickHouse instance.

  /** ClickHouse SQL to find dead words (complement of the VIEW for ad-hoc use). */
  val deadWordsQuery: String =
    """SELECT
      |    word_name,
      |    class_name,
      |    method_name,
      |    instruction_count,
      |    has_dead_code,
      |    compiled_at
      |FROM forth_words
      |WHERE word_name NOT IN (
      |    SELECT DISTINCT arrayJoin(called_words)
      |    FROM forth_words
      |    WHERE length(called_words) > 0
      |)
      |ORDER BY instruction_count DESC
      |FORMAT JSON""".stripMargin

  /** ClickHouse SQL to find most common trigrams in dead code regions. */
  val deadCodeTrigramQuery: String =
    """SELECT
      |    arrayStringConcat(arraySlice(body_tokens, toUInt64(i), 3), ' ') AS trigram,
      |    count() AS freq
      |FROM forth_words
      |WHERE has_dead_code = 1
      |ARRAY JOIN arrayEnumerate(body_tokens) AS i
      |WHERE toUInt64(i) + 2 <= length(body_tokens)
      |GROUP BY trigram
      |ORDER BY freq DESC
      |LIMIT 20
      |FORMAT JSON""".stripMargin

  /** ClickHouse SQL for stack depth anomaly detection. */
  val stackAnomalyQuery: String =
    """SELECT
      |    class_name,
      |    method_name,
      |    max_stack_depth,
      |    instruction_count
      |FROM forth_words
      |WHERE max_stack_depth > 16   -- Mecrisp-Stellaris default stack is 32 cells
      |ORDER BY max_stack_depth DESC
      |FORMAT JSON""".stripMargin
