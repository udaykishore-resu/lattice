import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as events from 'aws-cdk-lib/aws-events';
import * as sfn from 'aws-cdk-lib/aws-stepfunctions';
import * as tasks from 'aws-cdk-lib/aws-stepfunctions-tasks';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Platform } from 'aws-cdk-lib/aws-ecr-assets';

export interface LatticeTenantStackProps extends cdk.StackProps {
  tenant: string;
  apiDomain: string;
  certificateArn: string;
  hostedZoneId?: string;
  hostedZoneName?: string;
  desiredCount?: number;
}

/**
 * One tenant = one stack: its own engine service, executions table, API key, and state machine.
 * Data-plane isolation stays per tenant; the code, contract, and pipeline are shared.
 */
export class LatticeTenantStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: LatticeTenantStackProps) {
    super(scope, id, props);
    const { tenant } = props;
    const desired = props.desiredCount ?? 2;

    // ---- network + compute -------------------------------------------------
    const vpc = new ec2.Vpc(this, 'Vpc', { maxAzs: 2, natGateways: 1 });
    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsightsV2: ecs.ContainerInsights.ENHANCED });

    // ---- persistence: one table, single-table layout ------------------------
    const table = new dynamodb.Table(this, 'Executions', {
      tableName: `lattice-${tenant}-executions`,
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
      pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    table.addGlobalSecondaryIndex({
      indexName: 'gsi1',
      partitionKey: { name: 'gsi1pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'gsi1sk', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.INCLUDE,
      nonKeyAttributes: ['executionId', 'graph', 'graphVersion', 'status', 'startedAt', 'durationMs', 'correlationId'],
    });

    // ---- shared secret: engine validates it, Step Functions presents it -----
    const apiKey = new secretsmanager.Secret(this, 'ApiKey', {
      description: `Lattice API key for tenant ${tenant}`,
      generateSecretString: { excludePunctuation: true, passwordLength: 40 },
    });

    // ---- engine service behind an HTTPS ALB --------------------------------
    const certificate = acm.Certificate.fromCertificateArn(this, 'Cert', props.certificateArn);
    const zone =
      props.hostedZoneId && props.hostedZoneName
        ? route53.HostedZone.fromHostedZoneAttributes(this, 'Zone', {
            hostedZoneId: props.hostedZoneId,
            zoneName: props.hostedZoneName,
          })
        : undefined;

    const service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Engine', {
      cluster,
      cpu: 1024,
      memoryLimitMiB: 2048,
      desiredCount: desired,
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      certificate,
      protocol: elbv2.ApplicationProtocol.HTTPS,
      redirectHTTP: true,
      domainName: zone ? props.apiDomain : undefined,
      domainZone: zone,
      circuitBreaker: { rollback: true },
      healthCheckGracePeriod: cdk.Duration.seconds(60),
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
      taskImageOptions: {
        image: ecs.ContainerImage.fromAsset(path.join(__dirname, '..', '..'), { platform: Platform.LINUX_AMD64 }),
        containerPort: 8080,
        environment: {
          PORT: '8080',
          LATTICE_STORE: 'dynamo',
          LATTICE_TABLE: table.tableName,
          LATTICE_TENANT: tenant,
        },
        secrets: { LATTICE_API_KEY: ecs.Secret.fromSecretsManager(apiKey) },
        logDriver: ecs.LogDrivers.awsLogs({ streamPrefix: 'lattice', logRetention: logs.RetentionDays.ONE_MONTH }),
      },
    });
    service.targetGroup.configureHealthCheck({ path: '/health', interval: cdk.Duration.seconds(15) });
    service.targetGroup.setAttribute('deregistration_delay.timeout_seconds', '10');
    table.grantReadWriteData(service.taskDefinition.taskRole);

    const scaling = service.service.autoScaleTaskCount({ minCapacity: desired, maxCapacity: 10 });
    scaling.scaleOnCpuUtilization('Cpu', { targetUtilizationPercent: 60 });

    // ---- Step Functions → engine over native HTTP Task (no glue Lambda) -----
    const connection = new events.Connection(this, 'EngineConnection', {
      description: `Lattice engine connection for tenant ${tenant}`,
      authorization: events.Authorization.apiKey('x-lattice-key', apiKey.secretValue),
    });
    const apiRoot = `https://${props.apiDomain}`;

    const invokeGraph = (id: string, graph: string, seed: unknown, resultPath: string) => {
      const task = new tasks.HttpInvoke(this, id, {
        apiRoot,
        connection,
        method: sfn.TaskInput.fromText('POST'),
        apiEndpoint: sfn.TaskInput.fromText(`api/graphs/${tenant}/${graph}/execute`),
        headers: sfn.TaskInput.fromObject({ 'Content-Type': 'application/json' }),
        body: sfn.TaskInput.fromObject({
          seed,
          // Correlated at write time: the graph execution stores the workflow execution id.
          correlationId: sfn.JsonPath.stringAt('$$.Execution.Id'),
          // Step Functions retries are safe: the engine replays instead of re-executing.
          idempotencyKey: sfn.JsonPath.format(`{}#${graph}`, sfn.JsonPath.stringAt('$$.Execution.Name')),
        }),
        resultPath,
      });
      task.addRetry({ errors: ['States.TaskFailed'], interval: cdk.Duration.seconds(2), maxAttempts: 3, backoffRate: 2 });
      return task;
    };

    const credit = invokeGraph('CreditDecision', 'credit-decision', sfn.JsonPath.objectAt('$.seed'), '$.credit');
    const fulfillment = invokeGraph(
      'Fulfillment',
      'fulfillment',
      { applicant: sfn.JsonPath.objectAt('$.seed'), decision: sfn.JsonPath.objectAt('$.credit.ResponseBody.result') },
      '$.fulfillment',
    );

    const definition = credit.next(
      new sfn.Choice(this, 'RouteOnDecision')
        .when(sfn.Condition.stringEquals('$.credit.ResponseBody.result.decision', 'APPROVED'), fulfillment.next(new sfn.Succeed(this, 'Approved')))
        .otherwise(new sfn.Succeed(this, 'Declined')),
    );

    const stateMachine = new sfn.StateMachine(this, 'AcquisitionWorkflow', {
      stateMachineName: `lattice-${tenant}-acquisition`,
      definitionBody: sfn.DefinitionBody.fromChainable(definition),
      timeout: cdk.Duration.minutes(5),
      tracingEnabled: true,
      logs: {
        destination: new logs.LogGroup(this, 'WorkflowLogs', { retention: logs.RetentionDays.ONE_MONTH }),
        level: sfn.LogLevel.ALL,
      },
    });

    // ---- outputs -----------------------------------------------------------
    new cdk.CfnOutput(this, 'ApiUrl', { value: apiRoot });
    new cdk.CfnOutput(this, 'LoadBalancerDns', { value: service.loadBalancer.loadBalancerDnsName });
    new cdk.CfnOutput(this, 'ExecutionsTable', { value: table.tableName });
    new cdk.CfnOutput(this, 'ApiKeySecretArn', { value: apiKey.secretArn });
    new cdk.CfnOutput(this, 'StateMachineArn', { value: stateMachine.stateMachineArn });
  }
}
