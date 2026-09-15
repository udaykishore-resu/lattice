package lattice.api

import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*
import lattice.core.*
import lattice.runtime.{Executor, GraphCatalog}

object Routes:

  type Env = Executor & GraphCatalog & ExecutionStore

  private val api: List[ZServerEndpoint[Env, Any]] = List(
    Endpoints.execute.zServerLogic[Env] { case (tenant, graph, req) =>
      ZIO
        .serviceWithZIO[Executor](_.execute(tenant, graph, req.seed, req.idempotencyKey, req.correlationId))
        .mapBoth(ApiError.from, ExecuteResponse.from)
    },
    Endpoints.getExecution.zServerLogic[Env] { case (tenant, id) =>
      ZIO
        .serviceWithZIO[ExecutionStore](_.get(tenant, id))
        .mapError(ApiError.from)
        .someOrFail(ApiError.NotFound(s"execution $id"))
    },
    Endpoints.listExecutions.zServerLogic[Env] { case (tenant, graph, limit) =>
      ZIO.serviceWithZIO[ExecutionStore](_.listByGraph(tenant, graph, limit.max(1).min(100))).mapError(ApiError.from)
    },
    Endpoints.listGraphs.zServerLogic[Env](_ => ZIO.serviceWithZIO[GraphCatalog](_.list)),
    Endpoints.getManifest.zServerLogic[Env] { case (tenant, graph) =>
      ZIO.serviceWithZIO[GraphCatalog](_.manifest(tenant, graph)).someOrFail(ApiError.NotFound(s"graph $tenant/$graph"))
    },
    Endpoints.searchNodes.zServerLogic[Env](q => ZIO.serviceWithZIO[GraphCatalog](_.searchNodes(q)))
  )

  private val docs: List[ZServerEndpoint[Env, Any]] =
    SwaggerInterpreter().fromServerEndpoints[[A] =>> RIO[Env, A]](api, "Lattice", "0.1.0")

  private val healthEndpoint: ZServerEndpoint[Env, Any] =
    Endpoints.health.zServerLogic[Env](_ => ZIO.succeed("ok"))

  /** Optional API-key guard. When `apiKey` is None the API is open (local dev). */
  def routes(apiKey: Option[String]): Routes[Env, Response] =
    val protectedRoutes = ZioHttpInterpreter().toHttp(api ++ docs)
    val healthRoutes    = ZioHttpInterpreter().toHttp(List(healthEndpoint))
    val guarded = apiKey match
      case Some(key) =>
        protectedRoutes @@ HandlerAspect.customAuth(req => req.headers.get("x-lattice-key").contains(key))
      case None => protectedRoutes
    healthRoutes ++ guarded
