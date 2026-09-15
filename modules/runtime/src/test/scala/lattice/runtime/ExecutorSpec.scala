package lattice.runtime

import zio.*
import zio.json.ast.Json
import zio.test.*
import lattice.core.*
import lattice.core.GraphDsl.*

object ExecutorSpec extends ZIOSpecDefault:

  private val counting: Ref[Int] => Graph = counter =>
    graph("t", "counted") {
      val a = fetch[Int]("a")(_ => counter.updateAndGet(_ + 1))
      terminal(a)
    }

  def spec = suite("Executor")(
    test("idempotency key replays the stored execution instead of re-running") {
      for
        counter <- Ref.make(0)
        layer    = ZLayer.succeed(TenantGraphs(List(counting(counter)))) >>> GraphCatalog.live
        result  <- (for
                     ex <- ZIO.service[Executor]
                     r1 <- ex.execute("t", "counted", Json.Null, Some("k1"), None)
                     r2 <- ex.execute("t", "counted", Json.Null, Some("k1"), None)
                     r3 <- ex.execute("t", "counted", Json.Null, Some("k2"), None)
                     n  <- counter.get
                   yield (r1, r2, r3, n)).provide(layer, TestStore.layer, Executor.live)
        (r1, r2, r3, n) = result
      yield assertTrue(r1.executionId == r2.executionId, r1.executionId != r3.executionId, n == 2)
    },
    test("unknown graph is NotFound") {
      ZIO
        .serviceWithZIO[Executor](_.execute("t", "missing", Json.Null, None, None))
        .provide(ZLayer.succeed(TenantGraphs(Nil)) >>> GraphCatalog.live, TestStore.layer, Executor.live)
        .exit
        .map { e =>
          val notFound = e match
            case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LatticeError.NotFound])
            case _                   => false
          assertTrue(notFound)
        }
    }
  )

/** Minimal in-memory ExecutionStore for runtime tests (the real one lives in lattice-store). */
object TestStore:
  val layer: ULayer[ExecutionStore] = ZLayer.fromZIO {
    for
      recs  <- Ref.make(Map.empty[(String, String), ExecutionRecord])
      keys  <- Ref.make(Map.empty[(String, String, String), String])
    yield new ExecutionStore:
      def save(r: ExecutionRecord) = recs.update(_ + ((r.tenant, r.executionId) -> r))
      def get(tenant: String, id: String) = recs.get.map(_.get((tenant, id)))
      def listByGraph(tenant: String, graph: String, limit: Int) =
        recs.get.map(
          _.values
            .filter(r => r.tenant == tenant && r.graph == graph)
            .toList
            .sortBy(-_.startedAt)
            .take(limit)
            .map(r => ExecutionSummary(r.executionId, r.graph, r.graphVersion, r.status, r.startedAt, r.durationMs))
        )
      def claimIdempotency(tenant: String, graph: String, key: String, id: String) =
        keys.modify { m =>
          m.get((tenant, graph, key)) match
            case Some(existing) => (Some(existing), m)
            case None           => (None, m + ((tenant, graph, key) -> id))
        }
  }
