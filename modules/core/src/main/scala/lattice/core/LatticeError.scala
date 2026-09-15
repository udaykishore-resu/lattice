package lattice.core

import zio.Duration

sealed abstract class LatticeError(val message: String, val underlying: Option[Throwable] = None)
    extends Exception(message, underlying.orNull)

object LatticeError:
  final case class InvalidGraph(reason: String) extends LatticeError(s"invalid graph: $reason")

  final case class BadSeed(reason: String) extends LatticeError(s"bad seed: $reason")

  final case class NodeFailed(node: NodeId, reason: String, cause: Option[Throwable] = None)
      extends LatticeError(s"node '${node.value}' failed: $reason", cause)

  final case class NodeTimeout(node: NodeId, after: Duration)
      extends LatticeError(s"node '${node.value}' timed out after ${after.toMillis}ms")

  final case class GraphTimeout(graph: GraphId, after: Duration)
      extends LatticeError(s"graph '${graph.key}' timed out after ${after.toMillis}ms")

  final case class Upstream(service: String, reason: String, cause: Option[Throwable] = None)
      extends LatticeError(s"upstream '$service' failed: $reason", cause)

  final case class StoreFailure(reason: String, cause: Option[Throwable] = None)
      extends LatticeError(s"store failure: $reason", cause)

  final case class NotFound(what: String) extends LatticeError(s"not found: $what")

  final case class InProgress(executionId: String)
      extends LatticeError(s"execution '$executionId' with this idempotency key is still in progress")
