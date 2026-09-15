package lattice.app

import java.nio.file.{Files, Path}
import zio.*
import zio.json.*
import lattice.runtime.GraphCatalog
import lattice.tenants.demos.{Clients, DemoGraphs}

/**
 * Build-time manifest export: `sbt "app/runMain lattice.app.ExportManifests manifests"`.
 * Writes one JSON per graph. CI publishes these so the catalog can be queried with no JVM running.
 */
object ExportManifests extends ZIOAppDefault:
  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args <- getArgs
      out   = Path.of(args.headOption.getOrElse("manifests"))
      ms   <- ZIO.serviceWithZIO[GraphCatalog](_.list).provide(Clients.simulated(Duration.Zero), DemoGraphs.layer, GraphCatalog.live)
      _    <- ZIO.attempt(Files.createDirectories(out))
      _    <- ZIO.foreachDiscard(ms) { m =>
                val file = out.resolve(s"${m.tenant}--${m.name}.json")
                ZIO.attempt(Files.writeString(file, m.toJsonPretty)) *> Console.printLine(s"wrote $file")
              }
    yield ()
