package lattice.core

import zio.*
import zio.test.*
import GraphDsl.*

object GraphDslSpec extends ZIOSpecDefault:

  def spec = suite("GraphDsl")(
    test("records kinds, deps and conditional edges") {
      val g = graph("t", "dsl") {
        val a = fetch[Int]("a")(_ => ZIO.succeed(1))
        val b = compute("b")(a)(x => ZIO.succeed(x + 1))
        val c = computeIf("c")(b)((v: Int) => v > 0)(a)(x => ZIO.succeed(x.toString))
        val d = compute2("d")(b, c)((x, y) => ZIO.succeed(s"$x-${y.getOrElse("none")}"))
        terminal(d)
      }
      val kinds = g.order.map(id => g.nodes(id).meta.kind)
      assertTrue(
        g.order.map(_.value) == List("a", "b", "c", "d"),
        kinds == List(NodeKind.Fetch, NodeKind.Compute, NodeKind.Conditional, NodeKind.Compute),
        g.nodes(NodeId("c")).meta.deps.map(_.value) == List("b", "a"),
        g.terminal.value == "d"
      )
    },
    test("duplicate node ids fail at construction") {
      ZIO
        .attempt {
          graph("t", "dup") {
            val a = fetch[Int]("a")(_ => ZIO.succeed(1))
            val b = fetch[Int]("a")(_ => ZIO.succeed(2))
            terminal(b)
          }
        }
        .exit
        .map(e => assertTrue(e.isFailure))
    }
  )
