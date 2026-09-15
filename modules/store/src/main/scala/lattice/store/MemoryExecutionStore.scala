package lattice.store

import zio.*
import lattice.core.*

/** Local/test store. Not for production — state dies with the process. */
final class MemoryExecutionStore(
  records: Ref[Map[(String, String), ExecutionRecord]],
  claims: Ref[Map[(String, String, String), String]]
) extends ExecutionStore:

  def save(record: ExecutionRecord): IO[LatticeError, Unit] =
    records.update(_ + ((record.tenant, record.executionId) -> record))

  def get(tenant: String, executionId: String): IO[LatticeError, Option[ExecutionRecord]] =
    records.get.map(_.get((tenant, executionId)))

  def listByGraph(tenant: String, graph: String, limit: Int): IO[LatticeError, List[ExecutionSummary]] =
    records.get.map(
      _.values
        .filter(r => r.tenant == tenant && r.graph == graph)
        .toList
        .sortBy(r => -r.startedAt)
        .take(limit)
        .map(r => ExecutionSummary(r.executionId, r.graph, r.graphVersion, r.status, r.startedAt, r.durationMs))
    )

  def claimIdempotency(tenant: String, graph: String, key: String, executionId: String): IO[LatticeError, Option[String]] =
    claims.modify { m =>
      m.get((tenant, graph, key)) match
        case Some(existing) => (Some(existing), m)
        case None           => (None, m + ((tenant, graph, key) -> executionId))
    }

object MemoryExecutionStore:
  val live: ULayer[ExecutionStore] =
    ZLayer.fromZIO {
      for
        r <- Ref.make(Map.empty[(String, String), ExecutionRecord])
        c <- Ref.make(Map.empty[(String, String, String), String])
      yield MemoryExecutionStore(r, c): ExecutionStore
    }
