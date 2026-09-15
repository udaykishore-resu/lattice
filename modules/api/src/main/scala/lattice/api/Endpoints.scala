package lattice.api

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import zio.json.ast.Json
import lattice.core.*
import lattice.runtime.NodeHit

/** Contract-first endpoint descriptions. Server, OpenAPI, and (if wanted) clients derive from these. */
object Endpoints:

  given Schema[Json]            = Schema.any[Json]
  given Schema[NodeKind]        = Schema.derivedEnumeration[NodeKind].defaultStringBased
  given Schema[NodeStatus]      = Schema.derivedEnumeration[NodeStatus].defaultStringBased
  given Schema[ExecutionStatus] = Schema.derivedEnumeration[ExecutionStatus].defaultStringBased

  private val errors = oneOf[ApiError](
    oneOfVariant(StatusCode.NotFound, jsonBody[ApiError.NotFound]),
    oneOfVariant(StatusCode.BadRequest, jsonBody[ApiError.BadRequest]),
    oneOfVariant(StatusCode.Conflict, jsonBody[ApiError.Conflict]),
    oneOfVariant(StatusCode.GatewayTimeout, jsonBody[ApiError.Timeout]),
    oneOfVariant(StatusCode.InternalServerError, jsonBody[ApiError.Internal])
  )

  private val base = endpoint.errorOut(errors)

  private val tenant = path[String]("tenant")
  private val graph  = path[String]("graph")

  val execute: PublicEndpoint[(String, String, ExecuteRequest), ApiError, ExecuteResponse, Any] =
    base.post
      .in("api" / "graphs" / tenant / graph / "execute")
      .in(jsonBody[ExecuteRequest])
      .out(jsonBody[ExecuteResponse])
      .name("executeGraph")
      .description("Execute a decision graph synchronously and return its result and node-level trace.")

  val getExecution: PublicEndpoint[(String, String), ApiError, ExecutionRecord, Any] =
    base.get
      .in("api" / "executions" / tenant / path[String]("executionId"))
      .out(jsonBody[ExecutionRecord])
      .name("getExecution")

  val listExecutions: PublicEndpoint[(String, String, Int), ApiError, List[ExecutionSummary], Any] =
    base.get
      .in("api" / "graphs" / tenant / graph / "executions")
      .in(query[Int]("limit").default(20))
      .out(jsonBody[List[ExecutionSummary]])
      .name("listExecutions")

  val listGraphs: PublicEndpoint[Unit, ApiError, List[Manifest], Any] =
    base.get.in("api" / "graphs").out(jsonBody[List[Manifest]]).name("listGraphs")

  val getManifest: PublicEndpoint[(String, String), ApiError, Manifest, Any] =
    base.get.in("api" / "graphs" / tenant / graph / "manifest").out(jsonBody[Manifest]).name("getManifest")

  val searchNodes: PublicEndpoint[String, ApiError, List[NodeHit], Any] =
    base.get
      .in("api" / "nodes" / "search")
      .in(query[String]("q"))
      .out(jsonBody[List[NodeHit]])
      .name("searchNodes")
      .description("Cross-graph node search over the catalog — the query the in-JVM registry could never answer.")

  val health: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get.in("health").out(stringBody).name("health")
