package lattice.app

import zio.*

final case class AppConfig(port: Int, store: String, table: String, apiKey: Option[String], simulatedLatency: Duration)

object AppConfig:
  val load: IO[Throwable, AppConfig] =
    for
      port   <- System.env("PORT").map(_.flatMap(_.toIntOption).getOrElse(8080))
      store  <- System.env("LATTICE_STORE").map(_.getOrElse("memory"))
      table  <- System.env("LATTICE_TABLE").map(_.getOrElse("lattice-executions"))
      apiKey <- System.env("LATTICE_API_KEY").map(_.filter(_.nonEmpty))
      latency <- System
        .env("LATTICE_SIM_LATENCY_MS")
        .map(_.flatMap(_.toLongOption).map(Duration.fromMillis).getOrElse(150.millis))
    yield AppConfig(port, store, table, apiKey, latency)
