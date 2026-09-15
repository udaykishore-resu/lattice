package lattice.core

import zio.*
import zio.json.ast.Json
import zio.test.*

object GraphPlannerSpec extends ZIOSpecDefault:

  private def node(id: String, deps: String*): Node =
    Node(
      NodeMeta(NodeId(id), NodeKind.Compute, deps.toList.map(NodeId(_)), "", Policy.default),
      _ => ZIO.succeed(NodeOutcome.Produced(()))
    )

  private def graph(terminal: String)(ns: Node*): Graph =
    Graph(
      GraphId("t", "g"),
      "1",
      "",
      ns.map(n => n.meta.id -> n).toMap,
      ns.toList.map(_.meta.id),
      NodeId(terminal),
      10.seconds,
      _ => Json.Null
    )

  private def ids(xs: String*): List[NodeId] = xs.toList.map(NodeId(_))

  def spec = suite("GraphPlanner")(
    test("levelizes a diamond deterministically") {
      val g = graph("d")(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c"))
      assertTrue(GraphPlanner.levels(g) == Right(List(ids("a"), ids("b", "c"), ids("d"))))
    },
    test("independent roots share a level") {
      val g = graph("c")(node("a"), node("b"), node("c", "a", "b"))
      assertTrue(GraphPlanner.levels(g) == Right(List(ids("a", "b"), ids("c"))))
    },
    test("detects a cycle") {
      val g = graph("a")(node("a", "c"), node("b", "a"), node("c", "b"))
      assertTrue(GraphPlanner.levels(g).left.exists(_.message.contains("cycle")))
    },
    test("rejects unknown dependency") {
      val g = graph("a")(node("a", "ghost"))
      assertTrue(GraphPlanner.validate(g).left.exists(_.message.contains("unknown node 'ghost'")))
    },
    test("rejects undefined terminal") {
      val g = graph("nope")(node("a"))
      assertTrue(GraphPlanner.validate(g).left.exists(_.message.contains("terminal")))
    },
    test("manifest preserves declaration order and levels") {
      val g = graph("d")(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c"))
      val m = GraphPlanner.manifest(g).toOption.get
      assertTrue(
        m.nodes.map(_.id) == List("a", "b", "c", "d"),
        m.levels == List(List("a"), List("b", "c"), List("d")),
        m.terminal == "d"
      )
    }
  )
