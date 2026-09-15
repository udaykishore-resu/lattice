package lattice.store

import zio.*
import zio.json.ast.Json
import zio.test.*
import lattice.core.*

object MemoryExecutionStoreSpec extends ZIOSpecDefault:

  private def rec(id: String, graph: String, at: Long) =
    ExecutionRecord(id, "t", graph, "1", ExecutionStatus.Succeeded, at, 5, Json.Null, None, None, Nil, None, None)

  def spec = suite("MemoryExecutionStore")(
    test("save/get roundtrip and listByGraph ordering") {
      (for
        store <- ZIO.service[ExecutionStore]
        _     <- store.save(rec("a", "g", 1))
        _     <- store.save(rec("b", "g", 3))
        _     <- store.save(rec("c", "other", 2))
        got   <- store.get("t", "a")
        list  <- store.listByGraph("t", "g", 10)
      yield assertTrue(got.map(_.executionId).contains("a"), list.map(_.executionId) == List("b", "a")))
        .provide(MemoryExecutionStore.live)
    },
    test("idempotency claim is first-writer-wins") {
      (for
        store <- ZIO.service[ExecutionStore]
        c1    <- store.claimIdempotency("t", "g", "k", "e1")
        c2    <- store.claimIdempotency("t", "g", "k", "e2")
        c3    <- store.claimIdempotency("t", "g", "k2", "e3")
      yield assertTrue(c1.isEmpty, c2.contains("e1"), c3.isEmpty)).provide(MemoryExecutionStore.live)
    }
  )
