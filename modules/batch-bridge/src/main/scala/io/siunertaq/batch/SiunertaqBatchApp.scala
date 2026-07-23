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

  // ─── Spring Batch Infrastructure ───────────────────────────────────────────────
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

  // IO.bracket is not available as a static method in cats-effect 3.x.
  // Use the instance method IO[A]#bracket(use)(release) instead.
  // ActorSystem.terminate() returns Future[Terminated] (Pekko classic API) -
  // it must be chained via IO.fromFuture, the same way supervisor.ask(...) is
  // below, or the release action completes as soon as termination is merely
  // *requested*, not once the actor system has actually finished shutting down.
  override def run: IO[Unit] =
    IO(ActorSystem("siunertaq-batch")).bracket(runBatch)(sys => IO.fromFuture(IO(sys.terminate())).void)

  private def runBatch(system: ActorSystem): IO[Unit] =
    for
      // Load job definition via Dhall REPL or dhall-to-json
      dhallPath <- IO(Paths.get(
        sys.env.getOrElse("BATCH_JOB_DHALL", "modules/dhall-bridge/src/main/resources/BatchJob.dhall")
      ))
      jobDef <- DhallBatchRegistry.loadBatchJobFromFile(dhallPath)

      (jobRepository, txMgr) = buildInfra

      // Start JES2-like supervisor
      supervisor = system.actorOf(
        JobSupervisorActor.props(jobRepository, txMgr),
        name = "jes2-supervisor"
      )

      // Execute job
      given Timeout = Timeout(10.minutes)
      result <- IO.fromFuture(IO(supervisor.ask(RunJob(jobDef)).mapTo[JobResult]))

      // Output results (equivalent to JCL JES2 JLOG)
      _ <- IO.println(s"\n=== JOB LOG: ${result.jobName} ===")
      _ <- IO(result.notes.foreach(println))
      _ <- IO.println(s"=== MAX RC: ${result.maxRC} ===")
    yield ()