package lattice.app

import zio.*
import zio.http.Server
import zio.logging.consoleJsonLogger
import lattice.api.Routes
import lattice.core.ExecutionStore
import lattice.runtime.{Executor, GraphCatalog}
import lattice.store.{DynamoExecutionStore, MemoryExecutionStore}
import lattice.tenants.demos.{Clients, DemoGraphs}

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> consoleJsonLogger()

  private def storeLayer(cfg: AppConfig): ZLayer[Any, Throwable, ExecutionStore] =
    cfg.store match
      case "dynamo" => DynamoExecutionStore.live(cfg.table)
      case _        => MemoryExecutionStore.live

  def run: ZIO[Any, Throwable, Unit] =
    for
      cfg <- AppConfig.load
      _   <- ZIO.logInfo(s"lattice starting port=${cfg.port} store=${cfg.store} table=${cfg.table} auth=${cfg.apiKey.isDefined}")
      _   <- Server
               .serve(Routes.routes(cfg.apiKey))
               .provide(
                 Server.defaultWithPort(cfg.port),
                 storeLayer(cfg),
                 Executor.live,
                 GraphCatalog.live,
                 DemoGraphs.layer,
                 Clients.simulated(cfg.simulatedLatency)
               )
    yield ()
