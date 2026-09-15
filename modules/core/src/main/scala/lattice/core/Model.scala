package lattice.core

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Stable identifier of a node inside a graph. */
opaque type NodeId = String

object NodeId:
  def apply(value: String): NodeId         = value
  extension (id: NodeId) def value: String = id
  given JsonCodec[NodeId]                  = summon[JsonCodec[String]]

/** Typed handle to a node's output. Only the DSL creates these, which is what keeps `Inputs#apply` sound. */
final case class NodeRef[A](id: NodeId)

enum NodeKind derives JsonCodec:
  case Fetch, Compute, Conditional

/** Per-node resilience policy. Retries wrap the timeout, so a timed-out attempt is retried. */
final case class Policy(timeout: Duration, retries: Int, backoff: Duration)

object Policy:
  val default: Policy = Policy(timeout = 5.seconds, retries = 0, backoff = 200.millis)

final case class NodeMeta(id: NodeId, kind: NodeKind, deps: List[NodeId], description: String, policy: Policy)

enum NodeOutcome:
  case Produced(value: Any)
  case Skipped

/** Resolved upstream values for one node execution. */
final class Inputs(val seed: Json, private val values: Map[NodeId, Any]):
  def apply[A](ref: NodeRef[A]): A =
    values.get(ref.id) match
      case Some(v) => v.asInstanceOf[A]
      case None =>
        throw new IllegalStateException(s"input '${ref.id.value}' not available; planner ordering violated")

final case class Node(meta: NodeMeta, run: Inputs => IO[LatticeError, NodeOutcome])

final case class GraphId(tenant: String, name: String):
  def key: String = s"$tenant/$name"

/** A decision graph as a value. `nodes` is the DAG, `order` is declaration order (used for deterministic levelization),
  * `terminal` is the node whose output is the graph's result.
  */
final case class Graph(
    id: GraphId,
    version: String,
    description: String,
    nodes: Map[NodeId, Node],
    order: List[NodeId],
    terminal: NodeId,
    timeout: Duration,
    encodeResult: Any => Json
)
