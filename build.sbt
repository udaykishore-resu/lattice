import com.typesafe.sbt.packager.docker.*

ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.lattice"
ThisBuild / version      := "0.1.0"
ThisBuild / semanticdbEnabled := true

val zioV        = "2.1.14"
val zioJsonV    = "0.7.3"
val zioHttpV    = "3.0.1"
val zioLoggingV = "2.4.0"
val tapirV      = "1.11.10"
val awsV        = "2.29.30"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:imports", "-Wvalue-discard"),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioV % Test,
    "dev.zio" %% "zio-test-sbt" % zioV % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  Test / fork := true
)

// Pure graph model, DSL, planner, manifest. Depends on nothing but ZIO + zio-json.
lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "lattice-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"      % zioV,
      "dev.zio" %% "zio-json" % zioJsonV
    )
  )

// Interpreter: levelized parallel execution, policies, catalog, executor.
lazy val runtime = project
  .in(file("modules/runtime"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "lattice-runtime")

// ExecutionStore implementations: in-memory and DynamoDB.
lazy val store = project
  .in(file("modules/store"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "lattice-store",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "dynamodb" % awsV
    )
  )

// Contract-first HTTP API (tapir → zio-http), OpenAPI, auth.
lazy val api = project
  .in(file("modules/api"))
  .dependsOn(core, runtime, store)
  .settings(commonSettings)
  .settings(
    name := "lattice-api",
    libraryDependencies ++= Seq(
      "dev.zio"                     %% "zio-http"                 % zioHttpV,
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"    % tapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio"           % tapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle"  % tapirV
    )
  )

// Tenant graphs: plain Scala, no AWS, no HTTP.
lazy val tenantDemos = project
  .in(file("tenants/demos"))
  .dependsOn(core, runtime % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "lattice-tenant-demos")

// Deployable service.
lazy val app = project
  .in(file("app"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(api, tenantDemos)
  .settings(commonSettings)
  .settings(
    name := "lattice-app",
    Compile / mainClass  := Some("lattice.app.Main"),
    executableScriptName := "lattice",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-logging" % zioLoggingV
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, runtime, store, api, tenantDemos, app)
  .settings(name := "lattice", publish / skip := true)
