package lattice.runtime

import java.util.concurrent.TimeUnit
import zio.*
import zio.json.ast.Json
import lattice.core.*

/** Interprets a Graph value: levelizes it, runs each level in parallel, applies per-node policies, and returns a single
  * ExecutionRecord. Never fails — failure is a status on the record, so the caller always has something to persist and
  * return.
  */
object GraphRuntime:

  def execute(
      graph: Graph,
      seed: Json,
      executionId: String,
      idempotencyKey: Option[String],
      correlationId: Option[String]
  ): UIO[ExecutionRecord] =
    ZIO.logAnnotate("executionId", executionId) {
      ZIO.logAnnotate("graph", graph.id.key) {
        for
          levels  <- ZIO.fromEither(GraphPlanner.levels(graph)).orDie
          started <- Clock.currentTime(TimeUnit.MILLISECONDS)
          values  <- Ref.make(Map.empty[NodeId, Any])
          records <- Ref.make(List.empty[NodeRecord])
          outcome <- ZIO
            .foreachDiscard(levels) { level =>
              ZIO.foreachParDiscard(level)(id => runNode(graph.nodes(id), seed, values, records))
            }
            .timeoutFail(LatticeError.GraphTimeout(graph.id, graph.timeout))(graph.timeout)
            .either
          ended <- Clock.currentTime(TimeUnit.MILLISECONDS)
          vals  <- values.get
          recs  <- records.get
          status = outcome match
            case Right(_)                           => ExecutionStatus.Succeeded
            case Left(_: LatticeError.GraphTimeout) => ExecutionStatus.TimedOut
            case Left(_)                            => ExecutionStatus.Failed
          record = ExecutionRecord(
            executionId = executionId,
            tenant = graph.id.tenant,
            graph = graph.id.name,
            graphVersion = graph.version,
            status = status,
            startedAt = started,
            durationMs = ended - started,
            seed = seed,
            result = outcome.toOption.map(_ => graph.encodeResult(vals(graph.terminal))),
            error = outcome.left.toOption.map(_.message),
            nodes = recs.reverse,
            idempotencyKey = idempotencyKey,
            correlationId = correlationId
          )
          _ <- ZIO.logInfo(s"execution finished status=$status durationMs=${record.durationMs} nodes=${recs.size}")
        yield record
      }
    }

  private def runNode(
      node: Node,
      seed: Json,
      values: Ref[Map[NodeId, Any]],
      records: Ref[List[NodeRecord]]
  ): IO[LatticeError, Unit] =
    val meta   = node.meta
    val policy = meta.policy
    for
      started <- Clock.currentTime(TimeUnit.MILLISECONDS)
      inputs  <- values.get.map(m => Inputs(seed, m))
      result <- node
        .run(inputs)
        .catchAllDefect(t => ZIO.fail(LatticeError.NodeFailed(meta.id, s"defect: ${t.getMessage}", Some(t))))
        .timeoutFail(LatticeError.NodeTimeout(meta.id, policy.timeout))(policy.timeout)
        .retry(Schedule.exponential(policy.backoff) && Schedule.recurs(policy.retries))
        .either
      ended <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- result match
        case Right(NodeOutcome.Produced(v)) =>
          values.update(_ + (meta.id -> v)) *>
            records.update(
              NodeRecord(meta.id.value, meta.kind, NodeStatus.Succeeded, started, ended - started, None) :: _
            )
        case Right(NodeOutcome.Skipped) =>
          values.update(_ + (meta.id -> None)) *>
            records.update(
              NodeRecord(meta.id.value, meta.kind, NodeStatus.Skipped, started, ended - started, None) :: _
            )
        case Left(err) =>
          records.update(
            NodeRecord(meta.id.value, meta.kind, NodeStatus.Failed, started, ended - started, Some(err.message)) :: _
          ) *> ZIO.logWarning(s"node '${meta.id.value}' failed: ${err.message}") *> ZIO.fail(err)
    yield ()
