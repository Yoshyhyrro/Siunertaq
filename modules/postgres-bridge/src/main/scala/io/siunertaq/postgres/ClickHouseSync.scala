package io.siunertaq.postgres

import cats.effect.{IO, Ref}
import cats.effect.unsafe.IORuntime
import io.circe.Json
import io.circe.syntax.*
import org.apache.pekko.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy, Timers}

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

// ─── ClickHouseSync ──────────────────────────────────────────────────────────
//
//  Batch-push pipeline: Scala → ClickHouse HTTP interface
//
//  Architecture:
//
//    ClassASTBridge.compileClass()          mzv_triple_log (PostgreSQL)
//          │                                       │
//          │ CompilationResult.rows                │ (polled by SyncTick)
//          ▼                                       ▼
//    ClickHouseSyncActor ←──────── BytecodeBuffer / MZVBuffer (Ref[IO, ...])
//          │
//          │ HTTP POST (JSONEachRow format)
//          ▼
//    ClickHouse HTTP endpoint :8123
//          ├── bytecode_instructions
//          ├── forth_words
//          └── mzv_triple_stream
//
//  Batching strategy:
//    - BytecodeBuffer flushes when: size ≥ BATCH_SIZE OR age ≥ FLUSH_INTERVAL
//    - MZVBuffer polls PostgreSQL every POLL_INTERVAL; flushes on non-empty
//
//  Retry strategy (mirrors ForthRegistrarActor):
//    - OneForOneStrategy: Restart on IOException (transient network)
//    - Escalate on unexpected errors
//
//  ClickHouse HTTP API:
//    POST http://host:8123/?query=INSERT+INTO+table+FORMAT+JSONEachRow
//    Body: one JSON object per line (JSONEachRow)

// ── Protocol ────────────────────────────────────────────────────────────────

object ClickHouseSyncProtocol:
  /** Push compiled words and bytecode rows from a ClassASTBridge result. */
  final case class PushCompilation(result: ClassASTBridge.CompilationResult)

  /** Push a single MZV traversal event (from ImaginaryPopperActor or PetersenFluidMachine). */
  final case class PushMZVTriple(
    s1: Int, s2: Int, s3: Int,
    isConvergent:   Boolean,
    srcSector:      String,
    srcPhase:       Int,
    tgtSector:      String,
    tgtPhase:       Int,
    wasRegularized: Boolean,
    originalS1:     Option[Int],
    compiledStep:   Option[String]
  )

  /** Flush all buffered rows immediately (used in graceful shutdown). */
  case object FlushNow

  /** Internal timer tick (do not send externally). */
  private[postgres] case object SyncTick

  /** Internal poll tick for mzv_triple_log. */
  private[postgres] case object PollTick

// ── Config ──────────────────────────────────────────────────────────────────

final case class ClickHouseConfig(
  host:          String  = "localhost",
  port:          Int     = 8123,
  database:      String  = "siunertaq",
  user:          String  = "default",
  password:      String  = "",
  batchSize:     Int     = 50_000,    // rows before forced flush
  flushInterval: FiniteDuration = 5.seconds,
  pollInterval:  FiniteDuration = 2.seconds
):
  def endpoint(table: String, format: String = "JSONEachRow"): String =
    val auth = if password.nonEmpty then s"&password=$password" else ""
    s"http://$host:$port/?user=$user$auth&database=$database" +
    s"&query=${java.net.URLEncoder.encode(s"INSERT INTO $table FORMAT $format", "UTF-8")}"

  def fromEnv: ClickHouseConfig = copy(
    host     = sys.env.getOrElse("CLICKHOUSE_HOST", host),
    port     = sys.env.get("CLICKHOUSE_PORT").flatMap(_.toIntOption).getOrElse(port),
    database = sys.env.getOrElse("CLICKHOUSE_DB",   database),
    user     = sys.env.getOrElse("CLICKHOUSE_USER", user),
    password = sys.env.getOrElse("CLICKHOUSE_PASS", password)
  )

// ── Actor ────────────────────────────────────────────────────────────────────

final class ClickHouseSyncActor(
  cfg:       ClickHouseConfig,
  ioRuntime: IORuntime
) extends Actor with ActorLogging with Timers:

  import ClickHouseSyncProtocol.*

  // ── HTTP client (Java 11+ built-in, no extra dep) ─────────────────────────
  private val http = HttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  // ── In-memory buffers (mutable; actor is single-threaded) ─────────────────
  private val bytecodeBuf = scala.collection.mutable.ArrayBuffer[MecrispCompiler.BytecodeRow]()
  private val wordBuf     = scala.collection.mutable.ArrayBuffer[MecrispWordDef]()
  private val mzvBuf      = scala.collection.mutable.ArrayBuffer[Json]()

  // ── Timer setup ────────────────────────────────────────────────────────────
  override def preStart(): Unit =
    timers.startTimerWithFixedDelay("flush", SyncTick, cfg.flushInterval)
    timers.startTimerWithFixedDelay("poll",  PollTick, cfg.pollInterval)
    log.info("[CH] ClickHouseSyncActor started → {}:{}/{}", cfg.host, cfg.port, cfg.database)

  // ── Supervision ────────────────────────────────────────────────────────────
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1.minute):
      case _: java.io.IOException => SupervisorStrategy.Restart
      case _                      => SupervisorStrategy.Escalate

  // ── Message handling ───────────────────────────────────────────────────────
  override def receive: Receive =

    case PushCompilation(result) =>
      bytecodeBuf ++= result.rows
      wordBuf     ++= result.words
      log.debug("[CH] buffered {} bytecode rows, {} words", result.rows.size, result.words.size)
      if bytecodeBuf.size >= cfg.batchSize then flushAll()

    case PushMZVTriple(s1, s2, s3, conv, srcS, srcP, tgtS, tgtP, wasReg, origS1, step) =>
      val row = Json.obj(
        "s1"             -> s1.asJson,
        "s2"             -> s2.asJson,
        "s3"             -> s3.asJson,
        "is_convergent"  -> (if conv then 1 else 0).asJson,
        "src_sector"     -> srcS.asJson,
        "src_phase"      -> srcP.asJson,
        "tgt_sector"     -> tgtS.asJson,
        "tgt_phase"      -> tgtP.asJson,
        "was_regularized"-> (if wasReg then 1 else 0).asJson,
        "original_s1"    -> origS1.asJson,
        "compiled_step"  -> step.asJson,
        "logged_at"      -> java.time.Instant.now().toString.asJson
      )
      mzvBuf += row

    case SyncTick  => flushAll()
    case PollTick  => flushMZV()
    case FlushNow  => flushAll()

  // ── Flush helpers ──────────────────────────────────────────────────────────

  private def flushAll(): Unit =
    flushBytecode()
    flushWords()
    flushMZV()

  private def flushBytecode(): Unit =
    if bytecodeBuf.isEmpty then return
    val rows    = bytecodeBuf.toVector
    bytecodeBuf.clear()
    val payload = rows.map(_.toJson.noSpaces).mkString("\n")
    post(cfg.endpoint("bytecode_instructions"), payload) match
      case Right(_) =>
        log.info("[CH] ✓ bytecode_instructions: {} rows", rows.size)
      case Left(err) =>
        log.error("[CH] ✗ bytecode_instructions: {}", err)
        bytecodeBuf.prependAll(rows)   // retry next tick

  private def flushWords(): Unit =
    if wordBuf.isEmpty then return
    val words   = wordBuf.toVector
    wordBuf.clear()
    val payload = words.map { w =>
      Json.obj(
        "word_name"          -> w.name.asJson,
        "class_name"         -> w.sourceClass.asJson,
        "method_name"        -> w.sourceMethod.asJson,
        "method_descriptor"  -> w.sourceDesc.asJson,
        "stack_effect"       -> w.stackEffect.asJson,
        "body_tokens"        -> w.bodyTokens.asJson,
        "called_words"       -> w.calledWords.toSeq.sorted.asJson,
        "max_stack_depth"    -> w.maxStackDepth.asJson,
        "has_dead_code"      -> (if w.hasDeadCode then 1 else 0).asJson,
        "is_leaf_word"       -> (if w.directCallees.isEmpty then 1 else 0).asJson,
        "compiled_at"        -> java.time.Instant.now().toString.asJson
      ).noSpaces
    }.mkString("\n")
    post(cfg.endpoint("forth_words"), payload) match
      case Right(_) =>
        log.info("[CH] ✓ forth_words: {} words", words.size)
      case Left(err) =>
        log.error("[CH] ✗ forth_words: {}", err)
        wordBuf.prependAll(words)

  private def flushMZV(): Unit =
    if mzvBuf.isEmpty then return
    val rows    = mzvBuf.toVector
    mzvBuf.clear()
    val payload = rows.map(_.noSpaces).mkString("\n")
    post(cfg.endpoint("mzv_triple_stream"), payload) match
      case Right(_) =>
        log.info("[CH] ✓ mzv_triple_stream: {} rows", rows.size)
      case Left(err) =>
        log.error("[CH] ✗ mzv_triple_stream: {}", err)
        mzvBuf.prependAll(rows)

  // ── HTTP POST (ClickHouse JSONEachRow) ────────────────────────────────────

  private def post(url: String, body: String): Either[String, Unit] =
    try
      val req = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/octet-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .timeout(java.time.Duration.ofSeconds(30))
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then Right(())
      else Left(s"HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
    catch
      case e: java.io.IOException => Left(s"IO error: ${e.getMessage}")
      case e: Exception           => Left(s"Unexpected: ${e.getMessage}")

// ── Companion ────────────────────────────────────────────────────────────────

object ClickHouseSyncActor:
  def props(cfg: ClickHouseConfig, rt: IORuntime): Props =
    Props(new ClickHouseSyncActor(cfg, rt))

  def defaultConfig: ClickHouseConfig = ClickHouseConfig().fromEnv
