package lattice.store

import java.util.concurrent.{CompletableFuture, CompletionException}
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import lattice.core.*

/**
 * Single-table layout:
 *   pk = TENANT#<tenant>   sk = EXEC#<executionId>            one item per execution (body = full JSON)
 *   pk = TENANT#<tenant>   sk = IDEMP#<graph>#<key>           idempotency claim (conditional put)
 *   gsi1: gsi1pk = GRAPH#<tenant>#<graph>, gsi1sk = <startedAt zero-padded>#<executionId>
 */
final class DynamoExecutionStore(client: DynamoDbAsyncClient, table: String) extends ExecutionStore:
  import DynamoExecutionStore.*

  def save(record: ExecutionRecord): IO[LatticeError, Unit] =
    val item = Map(
      "pk"            -> s(s"TENANT#${record.tenant}"),
      "sk"            -> s(s"EXEC#${record.executionId}"),
      "gsi1pk"        -> s(s"GRAPH#${record.tenant}#${record.graph}"),
      "gsi1sk"        -> s(f"${record.startedAt}%020d#${record.executionId}"),
      "executionId"   -> s(record.executionId),
      "graph"         -> s(record.graph),
      "graphVersion"  -> s(record.graphVersion),
      "status"        -> s(record.status.toString),
      "startedAt"     -> n(record.startedAt),
      "durationMs"    -> n(record.durationMs),
      "correlationId" -> s(record.correlationId.getOrElse("")),
      "body"          -> s(record.toJson)
    )
    io(client.putItem(PutItemRequest.builder().tableName(table).item(item.asJava).build())).unit

  def get(tenant: String, executionId: String): IO[LatticeError, Option[ExecutionRecord]] =
    io(
      client.getItem(
        GetItemRequest
          .builder()
          .tableName(table)
          .key(Map("pk" -> s(s"TENANT#$tenant"), "sk" -> s(s"EXEC#$executionId")).asJava)
          .consistentRead(true)
          .build()
      )
    ).flatMap { resp =>
      if !resp.hasItem || resp.item().isEmpty then ZIO.none
      else
        ZIO
          .fromEither(resp.item().get("body").s().fromJson[ExecutionRecord])
          .mapBoth(e => LatticeError.StoreFailure(s"corrupt record $executionId: $e"), Some(_))
    }

  def listByGraph(tenant: String, graph: String, limit: Int): IO[LatticeError, List[ExecutionSummary]] =
    io(
      client.query(
        QueryRequest
          .builder()
          .tableName(table)
          .indexName("gsi1")
          .keyConditionExpression("gsi1pk = :pk")
          .expressionAttributeValues(Map(":pk" -> s(s"GRAPH#$tenant#$graph")).asJava)
          .scanIndexForward(false)
          .limit(limit)
          .build()
      )
    ).map { resp =>
      resp.items().asScala.toList.map { item =>
        val m = item.asScala
        ExecutionSummary(
          executionId = m("executionId").s(),
          graph = m("graph").s(),
          graphVersion = m("graphVersion").s(),
          status = ExecutionStatus.valueOf(m("status").s()),
          startedAt = m("startedAt").n().toLong,
          durationMs = m("durationMs").n().toLong
        )
      }
    }

  def claimIdempotency(tenant: String, graph: String, key: String, executionId: String): IO[LatticeError, Option[String]] =
    val pk = s(s"TENANT#$tenant")
    val sk = s(s"IDEMP#$graph#$key")
    val put = PutItemRequest
      .builder()
      .tableName(table)
      .item(Map("pk" -> pk, "sk" -> sk, "executionId" -> s(executionId), "createdAt" -> n(java.lang.System.currentTimeMillis())).asJava)
      .conditionExpression("attribute_not_exists(pk)")
      .build()
    ZIO
      .fromCompletableFuture(client.putItem(put))
      .as(Option.empty[String])
      .catchSome {
        case Conditional(_) =>
          io(client.getItem(GetItemRequest.builder().tableName(table).key(Map("pk" -> pk, "sk" -> sk).asJava).consistentRead(true).build()))
            .map(r => Option(r.item()).filter(!_.isEmpty).map(_.get("executionId").s()))
      }
      .mapError {
        case e: LatticeError => e
        case t               => LatticeError.StoreFailure(t.getMessage, Some(t))
      }

  private def io[A](f: => CompletableFuture[A]): IO[LatticeError, A] =
    ZIO.fromCompletableFuture(f).mapError(t => LatticeError.StoreFailure(unwrap(t).getMessage, Some(unwrap(t))))

object DynamoExecutionStore:
  private def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  private def n(v: Long): AttributeValue   = AttributeValue.builder().n(v.toString).build()

  private def unwrap(t: Throwable): Throwable = t match
    case c: CompletionException if c.getCause != null => c.getCause
    case other                                        => other

  private object Conditional:
    def unapply(t: Throwable): Option[ConditionalCheckFailedException] = unwrap(t) match
      case c: ConditionalCheckFailedException => Some(c)
      case _                                  => None

  /**
   * Region and credentials come from the default provider chain. For local development the SDK honours
   * AWS_ENDPOINT_URL_DYNAMODB (e.g. http://localhost:8000 for dynamodb-local).
   */
  def live(table: String): ZLayer[Any, Throwable, ExecutionStore] =
    ZLayer.scoped {
      ZIO
        .fromAutoCloseable(ZIO.attempt(DynamoDbAsyncClient.builder().build()))
        .map(client => DynamoExecutionStore(client, table): ExecutionStore)
    }
