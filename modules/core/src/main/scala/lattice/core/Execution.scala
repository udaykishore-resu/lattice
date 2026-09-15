package lattice.core

import zio.*
import zio.json.*
import zio.json.ast.Json

enum NodeStatus derives JsonCodec:
  case Succeeded, Skipped, Failed

enum ExecutionStatus derives JsonCodec:
  case Succeeded, Failed, TimedOut

final case class NodeRecord(
  id: String,
  kind: NodeKind,
  status: NodeStatus,
  startedAt: Long,
  durationMs: Long,
  error: Option[String]
) derives JsonCodec

/** One record per graph execution. Node-level detail is inline; there is no per-node write path. */
final case class ExecutionRecord(
  executionId: String,
  tenant: String,
  graph: String,
  graphVersion: String,
  status: ExecutionStatus,
  startedAt: Long,
  durationMs: Long,
  seed: Json,
  result: Option[Json],
  error: Option[String],
  nodes: List[NodeRecord],
  idempotencyKey: Option[String],
  correlationId: Option[String]
) derives JsonCodec

final case class ExecutionSummary(
  executionId: String,
  graph: String,
  graphVersion: String,
  status: ExecutionStatus,
  startedAt: Long,
  durationMs: Long
) derives JsonCodec

trait ExecutionStore:
  def save(record: ExecutionRecord): IO[LatticeError, Unit]
  def get(tenant: String, executionId: String): IO[LatticeError, Option[ExecutionRecord]]
  def listByGraph(tenant: String, graph: String, limit: Int): IO[LatticeError, List[ExecutionSummary]]

  /**
   * Atomically claim an idempotency key for `executionId`. Returns `None` if this call won the claim, or
   * `Some(existingExecutionId)` if the key was already claimed.
   */
  def claimIdempotency(tenant: String, graph: String, key: String, executionId: String): IO[LatticeError, Option[String]]
