package lattice.tenants.demos

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import lattice.core.*
import lattice.runtime.*

object DemoGraphsSpec extends ZIOSpecDefault:

  private val bureau = SimulatedBureau(Duration.Zero)
  private val income = SimulatedIncome(Duration.Zero)
  private val credit = DemoGraphs.creditDecision(bureau, income)

  private def seed(a: Applicant): Json = a.toJsonAST.toOption.get

  def spec = suite("DemoGraphs")(
    test("approves a strong applicant and builds an offer") {
      for
        rec <- GraphRuntime.execute(credit, seed(Applicant("good-1", 5000, 90000)), "x1", None, None)
        dec = rec.result.flatMap(_.as[Decision].toOption)
      yield assertTrue(
        rec.status == ExecutionStatus.Succeeded,
        dec.map(_.decision).contains("APPROVED"),
        dec.flatMap(_.offer).map(_.creditLimit).contains(5000L),
        rec.nodes.find(_.id == "offer").map(_.status).contains(NodeStatus.Succeeded)
      )
    },
    test("declines a weak applicant and skips the offer node") {
      for
        rec <- GraphRuntime.execute(credit, seed(Applicant("bad-1", 20000, 30000)), "x2", None, None)
        dec = rec.result.flatMap(_.as[Decision].toOption)
      yield assertTrue(
        dec.map(_.decision).contains("DECLINED"),
        dec.exists(_.reasons.nonEmpty),
        dec.exists(_.offer.isEmpty),
        rec.nodes.find(_.id == "offer").map(_.status).contains(NodeStatus.Skipped)
      )
    },
    test("bureau and income run in the same level") {
      val m = GraphPlanner.manifest(credit).toOption.get
      assertTrue(m.levels(1).toSet == Set("bureau-report", "income-verification"))
    },
    test("fulfillment refuses a declined decision") {
      val s =
        FulfillmentSeed(Applicant("bad-1", 1, 1), Decision("DECLINED", 300, List("x"), None)).toJsonAST.toOption.get
      for rec <- GraphRuntime.execute(DemoGraphs.fulfillment, s, "x3", None, None)
      yield assertTrue(rec.status == ExecutionStatus.Failed, rec.error.exists(_.contains("declined")))
    },
    test("catalog cross-graph node search sees both graphs") {
      (for
        c    <- ZIO.service[GraphCatalog]
        hits <- c.searchNodes("account")
        all  <- c.list
      yield assertTrue(
        all.map(_.name).toSet == Set("credit-decision", "fulfillment"),
        hits.exists(_.node.id == "provision-account")
      ))
        .provide(Clients.simulated(Duration.Zero), DemoGraphs.layer, GraphCatalog.live)
    }
  )
