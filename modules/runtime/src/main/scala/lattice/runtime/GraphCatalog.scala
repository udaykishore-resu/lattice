package lattice.runtime

import zio.*
import zio.json.*
import lattice.core.*

/** Graphs contributed by tenant modules at startup. */
final case class TenantGraphs(graphs: List[Graph])

final case class NodeHit(tenant: String, graph: String, graphVersion: String, node: NodeManifest) derives JsonCodec

/**
 * Read-only view over registered graphs and their manifests. Manifests are computed once at startup and are
 * also exported at build time (see lattice.app.ExportManifests), so topology is queryable without a JVM.
 */
trait GraphCatalog:
  def get(tenant: String, name: String): UIO[Option[Graph]]
  def list: UIO[List[Manifest]]
  def manifest(tenant: String, name: String): UIO[Option[Manifest]]
  def searchNodes(query: String): UIO[List[NodeHit]]

final class InMemoryCatalog(entries: Map[String, (Graph, Manifest)]) extends GraphCatalog:
  private val manifests: List[Manifest] = entries.values.map(_._2).toList.sortBy(_.key)

  def get(tenant: String, name: String): UIO[Option[Graph]] =
    ZIO.succeed(entries.get(GraphId(tenant, name).key).map(_._1))

  def list: UIO[List[Manifest]] = ZIO.succeed(manifests)

  def manifest(tenant: String, name: String): UIO[Option[Manifest]] =
    ZIO.succeed(entries.get(GraphId(tenant, name).key).map(_._2))

  def searchNodes(query: String): UIO[List[NodeHit]] =
    val q = query.trim.toLowerCase
    ZIO.succeed {
      if q.isEmpty then Nil
      else
        manifests.flatMap { m =>
          m.nodes
            .filter(n => n.id.toLowerCase.contains(q) || n.description.toLowerCase.contains(q))
            .map(n => NodeHit(m.tenant, m.name, m.version, n))
        }
    }

object GraphCatalog:
  val live: ZLayer[TenantGraphs, Nothing, GraphCatalog] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[TenantGraphs] { tg =>
        ZIO
          .foreach(tg.graphs)(g => ZIO.fromEither(GraphPlanner.manifest(g)).orDie.map(m => g.id.key -> (g, m)))
          .map(pairs => InMemoryCatalog(pairs.toMap): GraphCatalog)
          .tap(c => c.list.flatMap(ms => ZIO.logInfo(s"catalog loaded graphs=${ms.map(_.key).mkString(",")}")))
      }
    }
