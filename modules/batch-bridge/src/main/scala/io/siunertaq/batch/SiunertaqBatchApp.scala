package io.siunertaq.batch

import cats.effect.{IO, IOApp}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.jdbc.datasource.embedded.{EmbeddedDatabaseBuilder, EmbeddedDatabaseType}
import org.springframework.jdbc.datasource.DataSourceTransactionManager

import java.nio.file.Paths
import scala.concurrent.duration.*

object SiunertaqBatchApp extends IOApp.Simple:

  // ─── Spring Batch インフラ (in-memory H2) ───────────────────────────────
  private def buildInfra =
    val ds = EmbeddedDatabaseBuilder()
      .setType(EmbeddedDatabaseType.H2)
      .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
      .build()
    val txMgr = DataSourceTransactionManager(ds)
    val factory = JobRepositoryFactoryBean()
    factory.setDataSource(ds)
    factory.setTransactionManager(txMgr)
    factory.afterPropertiesSet()
    (factory.getObject, txMgr)

  // IO.bracket は cats-effect 3.x では静的メソッドとして存在しない。
  // IO[A]#bracket(use)(release) — インスタンスメソッドを使う。
  override def run: IO[Unit] =
    IO(ActorSystem("siunertaq-batch")).bracket(runBatch)(sys => IO(sys.terminate()).void)

  private def runBatch(system: ActorSystem): IO[Unit] =
    for
      // Dhall REPL または dhall-to-json 経由でジョブ定義を読み込む
      dhallPath <- IO(Paths.get(
        sys.env.getOrElse("BATCH_JOB_DHALL", "modules/batch-bridge/src/main/resources/BatchJob.dhall")
      ))
      jobDef <- DhallBatchRegistry.loadBatchJobFromFile(dhallPath)

      (jobRepository, txMgr) = buildInfra

      // JES2役スーパーバイザーを起動
      supervisor = system.actorOf(
        JobSupervisorActor.props(jobRepository, txMgr),
        name = "jes2-supervisor"
      )

      // ジョブ実行
      given Timeout = Timeout(10.minutes)
      result <- IO.fromFuture(IO(supervisor.ask(RunJob(jobDef)).mapTo[JobResult]))

      // 結果出力 (JCL JES2 JLOG相当)
      _ <- IO.println(s"\n=== JOB LOG: ${result.jobName} ===")
      _ <- IO(result.notes.foreach(println))
      _ <- IO.println(s"=== MAX RC: ${result.maxRC} ===")
    yield ()