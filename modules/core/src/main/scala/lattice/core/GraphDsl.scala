package lattice.core

import scala.collection.mutable
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Builds a Graph value. Nodes are declared in order; a node can only reference NodeRefs that already exist, which
  * makes cycles unrepresentable in the DSL.
  */
final class GraphBuilder private[core] (graphId: GraphId, version: String, description: String, timeout: Duration):
  private val nodes = mutable.LinkedHashMap.empty[NodeId, Node]

  private def register[A](node: Node): NodeRef[A] =
    if nodes.contains(node.meta.id) then
      throw LatticeError.InvalidGraph(s"duplicate node id '${node.meta.id.value}' in ${graphId.key}")
    nodes.update(node.meta.id, node)
    NodeRef[A](node.meta.id)

  /** A root node: reads only the execution seed. Typically an external call keyed by seed data. */
  def fetch[A](id: String, description: String = "", policy: Policy = Policy.default)(
      f: Json => IO[LatticeError, A]
  ): NodeRef[A] =
    register[A](
      Node(
        NodeMeta(NodeId(id), NodeKind.Fetch, Nil, description, policy),
        in => f(in.seed).map(NodeOutcome.Produced(_))
      )
    )

  def compute[A, B](id: String, description: String = "", policy: Policy = Policy.default)(a: NodeRef[A])(
      f: A => IO[LatticeError, B]
  ): NodeRef[B] =
    register[B](
      Node(
        NodeMeta(NodeId(id), NodeKind.Compute, List(a.id), description, policy),
        in => f(in(a)).map(NodeOutcome.Produced(_))
      )
    )

  def compute2[A, B, C](id: String, description: String = "", policy: Policy = Policy.default)(
      a: NodeRef[A],
      b: NodeRef[B]
  )(f: (A, B) => IO[LatticeError, C]): NodeRef[C] =
    register[C](
      Node(
        NodeMeta(NodeId(id), NodeKind.Compute, List(a.id, b.id).distinct, description, policy),
        in => f(in(a), in(b)).map(NodeOutcome.Produced(_))
      )
    )

  def compute3[A, B, C, D](id: String, description: String = "", policy: Policy = Policy.default)(
      a: NodeRef[A],
      b: NodeRef[B],
      c: NodeRef[C]
  )(f: (A, B, C) => IO[LatticeError, D]): NodeRef[D] =
    register[D](
      Node(
        NodeMeta(NodeId(id), NodeKind.Compute, List(a.id, b.id, c.id).distinct, description, policy),
        in => f(in(a), in(b), in(c)).map(NodeOutcome.Produced(_))
      )
    )

  /** Conditional node: runs `f` only when `predicate(when)` holds, otherwise records Skipped and yields None. The
    * condition edge is visible to the planner and the manifest — it is not hidden in a Supplier.
    */
  def computeIf[C, A, B](id: String, description: String = "", policy: Policy = Policy.default)(when: NodeRef[C])(
      predicate: C => Boolean
  )(a: NodeRef[A])(f: A => IO[LatticeError, B]): NodeRef[Option[B]] =
    register[Option[B]](
      Node(
        NodeMeta(NodeId(id), NodeKind.Conditional, List(when.id, a.id).distinct, description, policy),
        in =>
          if predicate(in(when)) then f(in(a)).map(b => NodeOutcome.Produced(Some(b)))
          else ZIO.succeed(NodeOutcome.Skipped)
      )
    )

  def terminal[A](ref: NodeRef[A])(using enc: JsonEncoder[A]): Graph =
    Graph(
      id = graphId,
      version = version,
      description = description,
      nodes = nodes.toMap,
      order = nodes.keys.toList,
      terminal = ref.id,
      timeout = timeout,
      encodeResult = v => enc.toJsonAST(v.asInstanceOf[A]).getOrElse(Json.Null)
    )

object GraphDsl:

  /** Build and validate a graph. Invalid graphs fail fast at construction (i.e. at service startup). */
  def graph(
      tenant: String,
      name: String,
      version: String = "1.0.0",
      description: String = "",
      timeout: Duration = 30.seconds
  )(
      build: GraphBuilder ?=> Graph
  ): Graph =
    val builder = new GraphBuilder(GraphId(tenant, name), version, description, timeout)
    val g       = build(using builder)
    GraphPlanner.validate(g) match
      case Left(err) => throw err
      case Right(_)  => g

  def fetch[A](id: String, description: String = "", policy: Policy = Policy.default)(f: Json => IO[LatticeError, A])(
      using b: GraphBuilder
  ): NodeRef[A] = b.fetch(id, description, policy)(f)

  def compute[A, B](id: String, description: String = "", policy: Policy = Policy.default)(a: NodeRef[A])(
      f: A => IO[LatticeError, B]
  )(using b: GraphBuilder): NodeRef[B] = b.compute(id, description, policy)(a)(f)

  def compute2[A, B, C](id: String, description: String = "", policy: Policy = Policy.default)(
      a: NodeRef[A],
      bb: NodeRef[B]
  )(
      f: (A, B) => IO[LatticeError, C]
  )(using b: GraphBuilder): NodeRef[C] = b.compute2(id, description, policy)(a, bb)(f)

  def compute3[A, B, C, D](id: String, description: String = "", policy: Policy = Policy.default)(
      a: NodeRef[A],
      bb: NodeRef[B],
      c: NodeRef[C]
  )(f: (A, B, C) => IO[LatticeError, D])(using b: GraphBuilder): NodeRef[D] =
    b.compute3(id, description, policy)(a, bb, c)(f)

  def computeIf[C, A, B](id: String, description: String = "", policy: Policy = Policy.default)(when: NodeRef[C])(
      predicate: C => Boolean
  )(a: NodeRef[A])(f: A => IO[LatticeError, B])(using b: GraphBuilder): NodeRef[Option[B]] =
    b.computeIf(id, description, policy)(when)(predicate)(a)(f)

  def terminal[A](ref: NodeRef[A])(using b: GraphBuilder, enc: JsonEncoder[A]): Graph = b.terminal(ref)
