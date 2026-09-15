package lattice.tenants.demos

import zio.*
import lattice.core.LatticeError

trait BureauClient:
  def pull(applicantId: String): IO[LatticeError, BureauReport]

trait IncomeClient:
  def verify(applicantId: String, statedIncome: Long): IO[LatticeError, IncomeVerification]

/** Deterministic simulations standing in for real integrations. Replace these layers with HTTP-backed implementations;
  * the graph does not change. Applicant ids starting with "bad-" produce a thin, delinquent file; ids starting with
  * "slow-" add latency so timeout/retry policies can be exercised end to end.
  */
final class SimulatedBureau(latency: Duration) extends BureauClient:
  def pull(applicantId: String): IO[LatticeError, BureauReport] =
    val report =
      if applicantId.startsWith("bad-") then BureauReport(score = 540, delinquencies = 3, inquiries = 7)
      else BureauReport(score = 742, delinquencies = 0, inquiries = 1)
    val delay = if applicantId.startsWith("slow-") then latency.plus(4.seconds) else latency
    ZIO.sleep(delay).as(report)

final class SimulatedIncome(latency: Duration) extends IncomeClient:
  def verify(applicantId: String, statedIncome: Long): IO[LatticeError, IncomeVerification] =
    val verified =
      if applicantId.startsWith("bad-") then IncomeVerification((statedIncome * 0.55).toLong, 0.4)
      else IncomeVerification((statedIncome * 0.97).toLong, 0.92)
    ZIO.sleep(latency).as(verified)

object Clients:
  def simulated(latency: Duration): ULayer[BureauClient & IncomeClient] =
    ZLayer.succeed(SimulatedBureau(latency): BureauClient) ++ ZLayer.succeed(SimulatedIncome(latency): IncomeClient)
