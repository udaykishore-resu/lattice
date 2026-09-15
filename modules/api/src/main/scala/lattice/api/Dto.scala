package lattice.api

import zio.json.*
import zio.json.ast.Json
import lattice.core.*

final case class ExecuteRequest(
    seed: Json,
    idempotencyKey: Option[String] = None,
    correlationId: Option[String] = None
) derives JsonCodec

final case class ExecuteResponse(
    executionId: String,
    graph: String,
    graphVersion: String,
    status: ExecutionStatus,
    result: Option[Json],
    error: Option[String],
    durationMs: Long,
    nodes: List[NodeRecord],
    correlationId: Option[String]
) derives JsonCodec

object ExecuteResponse:
  def from(r: ExecutionRecord): ExecuteResponse =
    ExecuteResponse(
      r.executionId,
      r.graph,
      r.graphVersion,
      r.status,
      r.result,
      r.error,
      r.durationMs,
      r.nodes,
      r.correlationId
    )

sealed trait ApiError:
  def message: String

object ApiError:
  final case class NotFound(message: String)                      extends ApiError derives JsonCodec
  final case class BadRequest(message: String)                    extends ApiError derives JsonCodec
  final case class Conflict(message: String, executionId: String) extends ApiError derives JsonCodec
  final case class Timeout(message: String)                       extends ApiError derives JsonCodec
  final case class Internal(message: String)                      extends ApiError derives JsonCodec

  def from(e: LatticeError): ApiError = e match
    case LatticeError.NotFound(_)     => NotFound(e.message)
    case LatticeError.InProgress(id)  => Conflict(e.message, id)
    case _: LatticeError.BadSeed      => BadRequest(e.message)
    case _: LatticeError.InvalidGraph => BadRequest(e.message)
    case _: LatticeError.GraphTimeout => Timeout(e.message)
    case _: LatticeError.NodeTimeout  => Timeout(e.message)
    case _                            => Internal(e.message)
