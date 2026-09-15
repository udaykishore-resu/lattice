package lattice.tenants.demos

import zio.*
import lattice.core.*
import lattice.core.GraphDsl.*
import lattice.core.LatticeError.BadSeed
import lattice.runtime.TenantGraphs

/** The demos tenant's graphs. Plain Scala values — no Spring, no reflection, no registry to refresh. */
object DemoGraphs:

  def creditDecision(bureau: BureauClient, income: IncomeClient): Graph =
    graph(
      "demos",
      "credit-decision",
      version = "1.2.0",
      description = "Card acquisition credit decision",
      timeout = 20.seconds
    ) {
      val applicant = fetch("applicant", "Parse applicant from seed") { seed =>
        ZIO.fromEither(seed.as[Applicant]).mapError(BadSeed(_))
      }
      val report = compute("bureau-report", "Pull credit bureau file", Policy(3.seconds, 2, 100.millis))(applicant) {
        a =>
          bureau.pull(a.applicantId)
      }
      val verified =
        compute("income-verification", "Verify stated income", Policy(3.seconds, 1, 200.millis))(applicant) { a =>
          income.verify(a.applicantId, a.statedIncome)
        }
      val risk = compute3("risk-assessment", "Apply risk rules")(applicant, report, verified) { (a, r, i) =>
        ZIO.succeed(RiskRules.assess(a, r, i))
      }
      val offer = computeIf("offer", "Build an offer when approved")(risk)((r: RiskAssessment) => r.approved)(risk) {
        r =>
          ZIO.succeed(OfferEngine.build(r))
      }
      val decision = compute2("decision", "Assemble the decision")(risk, offer) { (r, o) =>
        ZIO.succeed(Decision(if r.approved then "APPROVED" else "DECLINED", r.riskScore, r.reasons, o))
      }
      terminal(decision)
    }

  val fulfillment: Graph =
    graph("demos", "fulfillment", version = "1.0.0", description = "Provision the account after approval") {
      val input = fetch("input", "Parse applicant + decision") { seed =>
        ZIO.fromEither(seed.as[FulfillmentSeed]).mapError(BadSeed(_))
      }
      val account = compute("provision-account", "Open the account")(input) { s =>
        s.decision.offer match
          case Some(o) => Random.nextUUID.map(id => Account(id.toString, o.creditLimit, o.apr))
          case None    => ZIO.fail(BadSeed("cannot fulfill a declined decision"))
      }
      val notified = compute2("notify-customer", "Send the welcome notification")(input, account) { (s, acct) =>
        ZIO.logInfo(s"notify applicant=${s.applicant.applicantId} account=${acct.accountId}").as(true)
      }
      val result = compute2("fulfillment-result", "Terminal output")(account, notified) { (acct, ok) =>
        ZIO.succeed(FulfillmentResult(acct.accountId, acct.creditLimit, ok))
      }
      terminal(result)
    }

  /** Graphs for this tenant, with their clients resolved from the environment. */
  val layer: ZLayer[BureauClient & IncomeClient, Nothing, TenantGraphs] =
    ZLayer.fromFunction((b: BureauClient, i: IncomeClient) => TenantGraphs(List(creditDecision(b, i), fulfillment)))
