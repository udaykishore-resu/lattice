package lattice.runtime

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import lattice.core.*
import lattice.core.GraphDsl.*

object GraphRuntimeSpec extends ZIOSpecDefault:

  def spec = suite("GraphRuntime")(
    test("runs a graph, skips a false conditional, returns terminal result") {
      val g = graph("t", "basic") {
        val a = fetch[Int]("a")(_ => ZIO.succeed(1))
        val b = compute("b")(a)(x => ZIO.succeed(x + 1))
        val c = computeIf("c")(b)((v: Int) => v > 10)(b)(x => ZIO.succeed(x * 2))
        val d = compute2("d")(b, c)((x, y) => ZIO.succeed(y.getOrElse(x)))
        terminal(d)
      }
      for rec <- GraphRuntime.execute(g, Json.Null, "e1", None, None)
      yield assertTrue(
        rec.status == ExecutionStatus.Succeeded,
        rec.result.map(_.toJson).contains("2"),
        rec.nodes.map(_.id) == List("a", "b", "c", "d"),
        rec.nodes.find(_.id == "c").map(_.status).contains(NodeStatus.Skipped)
      )
    },
    test("a failing node fails the execution and is recorded") {
      val g = graph("t", "failing") {
        val a = fetch[Int]("a")(_ => ZIO.fail(LatticeError.Upstream("bureau", "503")))
        val b = compute("b")(a)(x => ZIO.succeed(x + 1))
        terminal(b)
      }
      for rec <- GraphRuntime.execute(g, Json.Null, "e2", None, None)
      yield assertTrue(
        rec.status == ExecutionStatus.Failed,
        rec.result.isEmpty,
        rec.error.exists(_.contains("bureau")),
        rec.nodes.map(n => n.id -> n.status) == List("a" -> NodeStatus.Failed)
      )
    },
    test("node timeout is enforced by policy") {
      val g = graph("t", "slow") {
        val a = fetch[Int]("slow", policy = Policy(1.second, 0, 10.millis))(_ => ZIO.sleep(10.seconds).as(1))
        terminal(a)
      }
      for
        fiber <- GraphRuntime.execute(g, Json.Null, "e3", None, None).fork
        _     <- TestClock.adjust(2.seconds)
        rec   <- fiber.join
      yield assertTrue(rec.status == ExecutionStatus.Failed, rec.error.exists(_.contains("timed out")))
    },
    test("graph timeout wins over a long level") {
      val g = graph("t", "slowgraph", timeout = 3.seconds) {
        val a = fetch[Int]("slow", policy = Policy(10.seconds, 0, 10.millis))(_ => ZIO.sleep(9.seconds).as(1))
        terminal(a)
      }
      for
        fiber <- GraphRuntime.execute(g, Json.Null, "e4", None, None).fork
        _     <- TestClock.adjust(4.seconds)
        rec   <- fiber.join
      yield assertTrue(rec.status == ExecutionStatus.TimedOut)
    },
    test("retries per policy then succeeds") {
      for
        counter <- Ref.make(0)
        g        = graph("t", "flaky") {
                     val a = fetch[Int]("flaky", policy = Policy(1.second, 2, 10.millis)) { _ =>
                       counter
                         .updateAndGet(_ + 1)
                         .flatMap(n => if n < 3 then ZIO.fail(LatticeError.Upstream("svc", "boom")) else ZIO.succeed(n))
                     }
                     terminal(a)
                   }
        fiber   <- GraphRuntime.execute(g, Json.Null, "e5", None, None).fork
        _       <- TestClock.adjust(1.second).repeatN(4)
        rec     <- fiber.join
        n       <- counter.get
      yield assertTrue(rec.status == ExecutionStatus.Succeeded, n == 3)
    },
    test("siblings in a level run in parallel") {
      val g = graph("t", "par") {
        val a = fetch[Int]("a")(_ => ZIO.sleep(1.second).as(1))
        val b = fetch[Int]("b")(_ => ZIO.sleep(1.second).as(2))
        val c = compute2("c")(a, b)((x, y) => ZIO.succeed(x + y))
        terminal(c)
      }
      for
        fiber <- GraphRuntime.execute(g, Json.Null, "e6", None, None).fork
        _     <- TestClock.adjust(1.second)
        rec   <- fiber.join
      yield assertTrue(rec.status == ExecutionStatus.Succeeded, rec.result.map(_.toJson).contains("3"))
    }
  )
