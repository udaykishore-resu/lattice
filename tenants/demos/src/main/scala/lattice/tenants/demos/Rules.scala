package lattice.tenants.demos

import scala.collection.mutable.ListBuffer

object RiskRules:
  def assess(a: Applicant, r: BureauReport, i: IncomeVerification): RiskAssessment =
    val reasons = ListBuffer.empty[String]
    val ratio   = a.requestedAmount.toDouble / math.max(i.verifiedIncome, 1L)
    if r.score < 620 then reasons += "bureau score below 620"
    if r.delinquencies > 1 then reasons += "multiple delinquencies"
    if r.inquiries > 5 then reasons += "excessive recent inquiries"
    if ratio > 0.5 then reasons += "requested amount exceeds 50% of verified income"
    if i.confidence < 0.6 then reasons += "low income verification confidence"
    val riskScore = ((1000 - r.score) / 4) + (r.delinquencies * 30) + (ratio * 100).toInt
    RiskAssessment(approved = reasons.isEmpty, riskScore = riskScore, reasons = reasons.toList, requestedAmount = a.requestedAmount)

object OfferEngine:
  def build(r: RiskAssessment): Offer =
    val apr = math.round((12.99 + r.riskScore / 20.0) * 100) / 100.0
    Offer(creditLimit = r.requestedAmount, apr = apr)
