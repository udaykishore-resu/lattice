package lattice.tenants.demos

import zio.json.*

final case class Applicant(applicantId: String, requestedAmount: Long, statedIncome: Long) derives JsonCodec

final case class BureauReport(score: Int, delinquencies: Int, inquiries: Int) derives JsonCodec

final case class IncomeVerification(verifiedIncome: Long, confidence: Double) derives JsonCodec

final case class RiskAssessment(approved: Boolean, riskScore: Int, reasons: List[String], requestedAmount: Long)
    derives JsonCodec

final case class Offer(creditLimit: Long, apr: Double) derives JsonCodec

/** Terminal output of credit-decision. `decision` is what the Step Functions Choice state routes on. */
final case class Decision(decision: String, riskScore: Int, reasons: List[String], offer: Option[Offer])
    derives JsonCodec

final case class FulfillmentSeed(applicant: Applicant, decision: Decision) derives JsonCodec

final case class Account(accountId: String, creditLimit: Long, apr: Double) derives JsonCodec

final case class FulfillmentResult(accountId: String, creditLimit: Long, notified: Boolean) derives JsonCodec
