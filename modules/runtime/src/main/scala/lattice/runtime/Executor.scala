package lattice.runtime

import zio.*
import zio.json.ast.Json
import lattice.core.*

/** Ties catalog lookup, idempotency, execution, and persistence into one call. */
trait Executor:
  def execute(
      tenant: String,
      graph: String,
      seed: Json,
      idempotencyKey: Option[String],
      correlationId: Option[String]
  ): IO[LatticeError, ExecutionRecord]

final class ExecutorLive(catalog: GraphCatalog, store: ExecutionStore) extends Executor:

  def execute(
      tenant: String,
      graph: String,
      seed: Json,
      idempotencyKey: Option[String],
      correlationId: Option[String]
  ): IO[LatticeError, ExecutionRecord] =
    for
      g  <- catalog.get(tenant, graph).someOrFail(LatticeError.NotFound(s"graph $tenant/$graph"))
      id <- Random.nextUUID.map(_.toString)
      prior <- idempotencyKey match
        case Some(key) => store.claimIdempotency(tenant, graph, key, id)
        case None      => ZIO.none
      rec <- prior match
        case Some(existing) =>
          ZIO.logInfo(s"idempotent replay execution=$existing") *>
            store.get(tenant, existing).someOrFail(LatticeError.InProgress(existing))
        case None =>
          GraphRuntime.execute(g, seed, id, idempotencyKey, correlationId).tap(store.save)
    yield rec

object Executor:
  val live: ZLayer[GraphCatalog & ExecutionStore, Nothing, Executor] =
    ZLayer.fromFunction((c: GraphCatalog, s: ExecutionStore) => ExecutorLive(c, s): Executor)
