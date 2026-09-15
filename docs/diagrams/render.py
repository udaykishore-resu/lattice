#!/usr/bin/env python3
"""Architecture diagrams as code (mingrammer/diagrams). Run: python3 docs/diagrams/render.py"""
from pathlib import Path
from diagrams import Cluster, Diagram, Edge
from diagrams.aws.analytics import KinesisDataFirehose
from diagrams.aws.compute import ECR, Fargate
from diagrams.aws.database import Dynamodb
from diagrams.aws.integration import Eventbridge, StepFunctions
from diagrams.aws.management import Cloudwatch
from diagrams.aws.network import ALB, Route53
from diagrams.aws.security import CertificateManager, SecretsManager
from diagrams.aws.storage import S3
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.client import Users
from diagrams.programming.language import Scala

OUT = Path(__file__).parent
GRAPH = {"fontsize": "20", "fontname": "Helvetica-Bold", "pad": "0.6", "nodesep": "0.7", "ranksep": "1.0", "splines": "spline"}
NODE = {"fontsize": "12", "fontname": "Helvetica"}
EDGE = {"fontsize": "11", "fontname": "Helvetica"}

# ---------------------------------------------------------------------------
with Diagram("Lattice — platform architecture (per tenant)", filename=str(OUT / "lattice-platform"), show=False,
             direction="LR", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
    client = Users("Client / upstream\nsystem")

    with Cluster("Control plane — shared across tenants"):
        ci = GithubActions("CI: test · manifests\n· image build")
        ecr = ECR("Image registry")
        manifests = S3("Graph manifests\n(build-time JSON)")
        ci >> ecr
        ci >> manifests

    with Cluster("Tenant stack (CDK)  ·  data plane isolated per tenant"):
        dns = Route53("Route 53")
        cert = CertificateManager("ACM cert")
        alb = ALB("ALB (HTTPS)")

        with Cluster("ECS Fargate — Lattice engine (Scala 3 + ZIO)"):
            engine = Fargate("lattice-app\n2–10 tasks, CPU autoscale")
            scala = Scala("graph-core · runtime\napi · store")
            engine - Edge(style="dotted") - scala

        table = Dynamodb("Executions table\n1 item / execution + idempotency")
        secret = SecretsManager("API key")
        logs = Cloudwatch("Logs · metrics\n· X-Ray")

        with Cluster("Orchestration"):
            sfn = StepFunctions("Acquisition workflow\n(Choice / Task)")
            conn = Eventbridge("Connection\n(API-key auth)")

        dns >> alb
        cert - Edge(style="dashed") - alb
        alb >> Edge(label="/api/graphs/{tenant}/{graph}/execute") >> engine
        engine >> Edge(label="PutItem / Query gsi1") >> table
        secret >> Edge(style="dashed", label="LATTICE_API_KEY") >> engine
        engine >> Edge(style="dashed") >> logs
        sfn >> Edge(label="HTTP Task\n(no glue Lambda)") >> conn >> Edge(label="POST + x-lattice-key") >> alb
        secret >> Edge(style="dashed") >> conn
        sfn >> Edge(style="dashed") >> logs

    client >> Edge(label="StartExecution") >> sfn
    client >> Edge(label="direct graph call") >> dns
    ecr >> Edge(style="dashed", label="image") >> engine

# ---------------------------------------------------------------------------
with Diagram("Lattice — workflow execution flow", filename=str(OUT / "lattice-execution-flow"), show=False,
             direction="LR", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
    client = Users("Client")
    sfn = StepFunctions("Step Functions\nacquisition workflow")
    alb = ALB("ALB")
    table = Dynamodb("Executions table")

    with Cluster("Lattice engine — credit-decision graph (levelized, parallel)"):
        with Cluster("L0"):
            applicant = Scala("applicant\n(fetch)")
        with Cluster("L1 — parallel"):
            bureau = Scala("bureau-report\nretry 2 · 3s")
            income = Scala("income-verification\nretry 1 · 3s")
        with Cluster("L2"):
            risk = Scala("risk-assessment")
        with Cluster("L3"):
            offer = Scala("offer\n(conditional)")
        with Cluster("L4"):
            decision = Scala("decision\n(terminal)")
        applicant >> bureau
        applicant >> income
        bureau >> risk
        income >> risk
        risk >> Edge(label="if approved") >> offer >> decision
        risk >> decision

    with Cluster("Lattice engine — fulfillment graph"):
        provision = Scala("provision-account")
        notify = Scala("notify-customer")
        provision >> notify

    client >> Edge(label="1. StartExecution\n{seed}") >> sfn
    sfn >> Edge(label="2. POST execute\ncorrelationId = $$.Execution.Id\nidempotencyKey = name#graph") >> alb >> applicant
    decision >> Edge(label="3. ExecuteResponse\n{executionId, result, nodes}") >> table
    decision >> Edge(label="4. $.credit.ResponseBody", style="dashed") >> sfn
    sfn >> Edge(label="5. Choice: result.decision == APPROVED\n→ POST fulfillment") >> provision
    notify >> Edge(label="6. Succeed", style="dashed") >> sfn

# ---------------------------------------------------------------------------
with Diagram("Lattice — module dependencies", filename=str(OUT / "lattice-modules"), show=False,
             direction="BT", graph_attr=GRAPH, node_attr=NODE, edge_attr=EDGE):
    core = Scala("lattice-core\nNode · Graph · DSL\nPlanner · Manifest\n(depends only on ZIO)")
    runtime = Scala("lattice-runtime\nGraphRuntime · Catalog\nExecutor")
    store = Scala("lattice-store\nMemory · DynamoDB")
    api = Scala("lattice-api\ntapir endpoints\nzio-http · OpenAPI")
    demos = Scala("tenants/demos\ncredit-decision\nfulfillment")
    app = Scala("lattice-app\nMain · ExportManifests")
    ddb = Dynamodb("DynamoDB")
    core >> runtime
    core >> store
    runtime >> api
    store >> api
    core >> demos
    runtime >> demos
    api >> app
    demos >> app
    store >> Edge(style="dashed") >> ddb

print("rendered:", sorted(p.name for p in OUT.glob("*.png")))
