package org.typelevel.mzv

import scala.beans.BeanProperty
import cats.effect.{IO, IOApp}
import org.springframework.context.annotation.{Bean, Configuration, AnnotationConfigApplicationContext}
import org.apache.pekko.actor.{ActorSystem, Actor, ActorLogging, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future

// ===================================================================
// §1. Geometric & Group-Theoretic Domain Model (Harada-Norton & Petersen)
// ===================================================================

/**
 * A trait representing the 10 vertices of the Petersen graph (diameter 2).
 * Corresponds to the Z/5Z phase unifying 5⁶ (binary Golay code) and 3⁶ (ternary Golay code)
 * of the HN (Harada-Norton) group, and the sector structures of even and odd depths.
 */
sealed trait Vertex {
  def phase: Int
}

/** Even-depth sectors (d0, d2, d4). Has clean duality. */
case class Outer(phase: Int) extends Vertex {
  require(phase >= 0 && phase < 5, "Phase must be in ℤ/5ℤ (0-4)")
}

/** Odd-depth sectors (d1, d3, d5). Obstruction sectors for Fischer obstructions and odd zeta problems. */
case class Inner(phase: Int) extends Vertex {
  require(phase >= 0 && phase < 5, "Phase must be in ℤ/5ℤ (0-4)")
}

/**
 * An enterprise Java Bean modeling the triple depth (Triple) of MZVs (multiple zeta values).
 * Annotated with @BeanProperty for interoperability with traditional Spring / Java frameworks.
 * * Depth 3 (Triple) corresponds to the weight 'd3' (orbit size 1463) of the HN carabiner model.
 */
case class MZVTriple(
  @BeanProperty s1: Int,
  @BeanProperty s2: Int,
  @BeanProperty s3: Int
) {
  /** ζ(s1, s2, s3) converges for s1 > 1. s1 = 1 is the pole of divergence (an obstruction at the boundary). */
  def isConvergent: Boolean = s1 > 1
  def mzvWeight: Int = s1 + s2 + s3
}

// ===================================================================
// §1.5 Purely Imaginary Part Key Pool (Pekko Actor Model)
// ===================================================================

/** Asynchronous message to pop unresolved ZKP keys (???) stocked in the purely imaginary part. */
case class PopImaginaryKey(triple: MZVTriple, source: Vertex, target: Vertex)

/**
 * An actor dedicated to the purely imaginary part.
 * Handles the evaluation (popping) of "???" at the boundary of the Petersen graph concurrently
 * in an asynchronous space separated from the mainstream of the real part (Cats IO).
 */
class ImaginaryPopperActor extends Actor with ActorLogging {
  def receive: Receive = {
    case PopImaginaryKey(triple, source, target) =>
      if (!triple.isConvergent) {
        log.warning(s"  [IMAGINARY POP] $source ──> $target: Collided with the pole of divergence. Expanding purely imaginary key '???' and reducing as an exception.")
        // Explode the imaginary part into '???' in the actor space, returning it to the real part as a Future failure
        sender() ! akka.actor.Status.Failure(new NotImplementedError("??? at Imaginary Boundary"))
      } else {
        val isSectorJump = source.getClass != target.getClass
        val result = if (isSectorJump) {
          MZVTriple(triple.s1, triple.s2 + 1, triple.s3 - 1)
        } else {
          MZVTriple(triple.s1, triple.s2 - 1, triple.s3 + 1)
        }
        log.info(s"  [IMAGINARY POP] $source ──> $target: Safely popped the imaginary key, generating the merged value for the real part: $result")
        sender() ! result
      }
  }
}

object ImaginaryPopperActor {
  def props: Props = Props(new ImaginaryPopperActor)
}


// ===================================================================
// §2. Monadic Stack Transition Machine (Cats Effect IO & Furusho Duality)
// ===================================================================

class PetersenFluidMachine(actorSystem: ActorSystem) {

  // Timeout setting for querying (asking) actors
  implicit val timeout: Timeout = Timeout(3.seconds)
  
  // Creation of worker actors to pop purely imaginary part keys (which can actually be distributed across multiple instances via a router)
  val imaginaryPopper = actorSystem.actorOf(ImaginaryPopperActor.props, "imaginary-popper")

  /**
   * Connection relationships of the Petersen graph (3-regular graph).
   * - Outer (0-4) forms a pentagonal cycle, each connecting to the corresponding Inner vertex.
   * - Inner (0-4) forms a star polygon {5/2} under Z/5Z phase rotation.
   */
  def getNeighbors(vertex: Vertex): Set[Vertex] = vertex match {
    case Outer(i) => Set(Outer((i + 1) % 5), Outer((i + 4) % 5), Inner(i))
    case Inner(i) => Set(Inner((i + 2) % 5), Inner((i + 3) % 5), Outer(i))
  }

  /**
   * A reduction step simulating Dr. Furusho's pentagon coherence (pentagon relation)
   * and the double algebraic structure of shuffle/stuffle.
   * * If hitting the pole of divergence (s1 = 1), the ZKP (Zero-Knowledge Proof) like placeholder
   * '???' (Nothing) is triggered as a monadic short-circuit in Cats Effect IO.
   */
  def applyPentagonRelation(triple: MZVTriple, source: Vertex, target: Vertex): IO[MZVTriple] = {
    // Asynchronously requests "Pop" to Pekko's purely imaginary space from Cats IO's execution thread (ask pattern)
    // Represents the separation boundary between real and imaginary parts (dimensional transition of Log-Link in IUT)
    val futureResult: Future[MZVTriple] = (imaginaryPopper ? PopImaginaryKey(triple, source, target)).mapTo[MZVTriple]
    IO.fromFuture(IO(futureResult))
  }

  /**
   * Tail-recursive stack popper running safely on Cats Effect IO runtime.
   * Due to the invariant that the Petersen graph has a "diameter of 2", any path between
   * two nodes is resolved in at most two IO flatMaps (ultra-fast path resolution $O(1)$).
   */
  def resolveStack(
    initialTriple: MZVTriple,
    start: Vertex,
    goal: Vertex
  ): IO[MZVTriple] = {
    
    def traverse(current: Vertex, currentTriple: MZVTriple, path: List[Vertex]): IO[MZVTriple] = {
      if (current == goal) {
        IO.println(s"  [COHERENCE MET] Goal reached. Path: ${path.reverse.mkString(" -> ")}") *>
        IO.pure(currentTriple)
      } else {
        val nextStepOpt = getNeighbors(current).find(v => v == goal || getNeighbors(v).contains(goal))
        nextStepOpt match {
          case Some(nextStep) =>
            for {
              _ <- IO.println(s"  [IUT Log-Link] Shifting Z/5Z phase: $current ──> $nextStep...")
              nextTriple <- applyPentagonRelation(currentTriple, current, nextStep)
              result     <- traverse(nextStep, nextTriple, nextStep :: path)
            } yield result
            
          case None =>
            IO.delay(???) // Theoretically unreachable deadlock due to the diameter-2 topology
        }
      }
    }

    traverse(start, initialTriple, List(start))
  }
}


// ===================================================================
// §3. Spring Framework @Configuration & Pekko Integration
// ===================================================================

/**
 * Configuration class for Spring IoC container.
 * Defines Pekko ActorSystem and the proprietary mathematical reducer engine as beans.
 */
@Configuration
class MZVEnterpriseConfiguration {

  /**
   * Provides Pekko ActorSystem as a Spring Bean.
   * Manages the asynchronous thread pool and lifecycle at the infrastructure layer.
   */
  @Bean(destroyMethod = "terminate")
  def pekkoActorSystem(): ActorSystem = {
    ActorSystem("HaradaNortonPekkoSystem")
  }

  /**
   * Manages the Petersen graph fluid topology engine as a singleton bean.
   * Passes the Pekko ActorSystem via constructor injection.
   */
  @Bean
  def petersenFluidMachine(actorSystem: ActorSystem): PetersenFluidMachine = {
    new PetersenFluidMachine(actorSystem)
  }
}

// ===================================================================
// §4. Main Execution Entry Point (Cats Effect IOApp & Spring Registry)
// ===================================================================

object Main extends IOApp.Simple {

  /**
   * Pure function that initializes the Spring container within the asynchronous IO context.
   */
  val initializeSpringContext: IO[AnnotationConfigApplicationContext] = IO.delay {
    val context = new AnnotationConfigApplicationContext()
    context.register(classOf[MZVEnterpriseConfiguration])
    context.refresh()
    context
  }

  def run: IO[Unit] = for {
    _ <- IO.println("=========================================================================")
    _ <- IO.println("  LAUNCHING HIGH-HYBRID ENTERPRISE MZV REVOLVER SYSTEM                   ")
    _ <- IO.println("  [Spring Boot Context] ✕ [Apache Pekko ActSystem] ✕ [Cats Effect IO]  ")
    _ <- IO.println("=========================================================================")

    // Step 1: Starting the Spring container
    _ <- IO.println("\n[SYSTEM] Starting Spring IoC application context...")
    ctx <- initializeSpringContext
    _ <- IO.println("[SYSTEM] Loaded Spring Beans.")

    // Step 2: Dynamically looking up Beans from Spring
    _ <- IO.println("[SYSTEM] Extracting Beans via Dependency Injection (DI)...")
    actorSystem <- IO.delay(ctx.getBean(classOf[ActorSystem]))
    machine     <- IO.delay(ctx.getBean(classOf[PetersenFluidMachine]))
    
    _ <- IO.println(s"[PEKKO] ActorSystem [${actorSystem.name}] successfully integrated.")
    _ <- IO.println(s"[ENGINE] PetersenFluidMachine successfully loaded from the DI container.")

    _ <- IO.println("\n-------------------------------------------------------------------------")

    // [Test Case 1] Convergent triple: ζ(3, 2, 1) -- Clean dual transformation of weight 6
    _ <- IO.println("\n>>> Test Case 1: Convergent shuffle/stuffle fan (Outer -> Inner jump)")
    initialTriple1 = MZVTriple(3, 2, 1) 
    startNode = Outer(0)  // Even sector, phase 0
    goalNode  = Inner(3)  // Odd obstruction sector (HN carabiner d3), phase 3
    
    _ <- IO.println(s"  Initial state: Sending \u03b6(${initialTriple1.s1}, ${initialTriple1.s2}, ${initialTriple1.s3}) from $startNode to $goalNode...")
    result1 <- machine.resolveStack(initialTriple1, startNode, goalNode)
    _ <- IO.println(s"  Reduced state (Java Bean standard): [s1: ${result1.getS1}, s2: ${result1.getS2}, s3: ${result1.getS3}]")

    _ <- IO.println("\n-------------------------------------------------------------------------")

    // [Test Case 2] Divergent triple: ζ(1, 2, 3) -- Monadic exception detection due to boundary divergence
    _ <- IO.println("\n>>> Test Case 2: Singularity at odd depth (Fischer f9 obstruction / pole of divergence)")
    initialTriple2 = MZVTriple(1, 2, 3) // s1 = 1 is divergent!
    _ <- IO.println(s"  Initial state: \u03b6(${initialTriple2.s1}, ${initialTriple2.s2}, ${initialTriple2.s3}) (Colliding with the singular boundary of Chen integral, expecting exceptional short-circuit)...")
    
    attemptResult <- machine.resolveStack(initialTriple2, startNode, goalNode).attempt
    _ <- attemptResult match {
      case Left(_: NotImplementedError) =>
        IO.println("  [PROOF DETECTED] Purely mathematical singularity (???) caught safely by the IO monad! Protecting proof space without crashing the JVM.")
      case Left(e) =>
        IO.println(s"  [UNEXPECTED ERROR] Unexpected error occurred: $e")
      case Right(res) =>
        IO.println(s"  [FAIL] Unexpectedly converged: $res")
    }

    // Closing Spring container (Pekko ActorSystem is also automatically shut down via destroyMethod)
    _ <- IO.println("\n[SYSTEM] Destroying Spring context and cleaning up ActorSystem...")
    _ <- IO.delay(ctx.close())
    _ <- IO.println("[SYSTEM] All resources released.")
    _ <- IO.println("=========================================================================")
  } yield ()
}
