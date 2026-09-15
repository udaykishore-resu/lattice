package lattice.core

import scala.annotation.tailrec
import scala.collection.mutable
import zio.json.*
import LatticeError.InvalidGraph

final case class NodeManifest(
  id: String,
  kind: NodeKind,
  deps: List[String],
  description: String,
  timeoutMs: Long,
  retries: Int,
  backoffMs: Long
) derives JsonCodec

/** Build-time, runtime-free description of a graph. This is what the catalog stores and searches. */
final case class Manifest(
  tenant: String,
  name: String,
  version: String,
  description: String,
  terminal: String,
  timeoutMs: Long,
  nodes: List[NodeManifest],
  levels: List[List[String]]
) derives JsonCodec:
  def key: String = s"$tenant/$name"

/** Pure analysis over a Graph value: validation, levelization (Kahn), manifest. No execution. */
object GraphPlanner:

  def validate(g: Graph): Either[InvalidGraph, Unit] =
    for
      _ <- Either.cond(g.nodes.nonEmpty, (), InvalidGraph("graph has no nodes"))
      _ <- Either.cond(g.order.toSet == g.nodes.keySet, (), InvalidGraph("declaration order does not match node set"))
      _ <- Either.cond(g.nodes.contains(g.terminal), (), InvalidGraph(s"terminal node '${g.terminal.value}' is not defined"))
      _ <- levels(g)
    yield ()

  /** Nodes grouped into execution levels; every node in a level depends only on earlier levels. */
  def levels(g: Graph): Either[InvalidGraph, List[List[NodeId]]] =
    unknownDependency(g) match
      case Some((n, d)) => Left(InvalidGraph(s"node '${n.value}' depends on unknown node '${d.value}'"))
      case None         => kahn(g)

  def manifest(g: Graph): Either[InvalidGraph, Manifest] =
    for
      _  <- validate(g)
      lv <- levels(g)
    yield Manifest(
      tenant = g.id.tenant,
      name = g.id.name,
      version = g.version,
      description = g.description,
      terminal = g.terminal.value,
      timeoutMs = g.timeout.toMillis,
      nodes = g.order.map { id =>
        val m = g.nodes(id).meta
        NodeManifest(
          m.id.value,
          m.kind,
          m.deps.map(_.value),
          m.description,
          m.policy.timeout.toMillis,
          m.policy.retries,
          m.policy.backoff.toMillis
        )
      },
      levels = lv.map(_.map(_.value))
    )

  private def unknownDependency(g: Graph): Option[(NodeId, NodeId)] =
    g.order.iterator
      .flatMap(id => g.nodes(id).meta.deps.filterNot(g.nodes.contains).map(d => (id, d)))
      .nextOption()

  private def kahn(g: Graph): Either[InvalidGraph, List[List[NodeId]]] =
    val position: Map[NodeId, Int] = g.order.zipWithIndex.toMap
    val indegree = mutable.Map.from(g.order.map(id => id -> g.nodes(id).meta.deps.distinct.size))
    val dependents: Map[NodeId, List[NodeId]] =
      g.order.flatMap(id => g.nodes(id).meta.deps.distinct.map(d => d -> id)).groupMap(_._1)(_._2)

    @tailrec
    def loop(frontier: List[NodeId], acc: List[List[NodeId]], seen: Int): Either[InvalidGraph, List[List[NodeId]]] =
      if frontier.isEmpty then
        if seen == g.order.size then Right(acc.reverse)
        else
          val stuck = indegree.collect { case (id, d) if d > 0 => id.value }.toList.sorted
          Left(InvalidGraph(s"cycle detected among nodes: ${stuck.mkString(", ")}"))
      else
        val next = frontier.flatMap(id => dependents.getOrElse(id, Nil)).filter { d =>
          indegree.update(d, indegree(d) - 1)
          indegree(d) == 0
        }
        loop(next.sortBy(position), frontier :: acc, seen + frontier.size)

    loop(g.order.filter(id => indegree(id) == 0), Nil, 0)
