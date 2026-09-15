#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { LatticeTenantStack } from '../lib/lattice-tenant-stack';

const app = new cdk.App();

const ctx = (key: string): string | undefined => app.node.tryGetContext(key);
const required = (key: string): string => {
  const v = ctx(key);
  if (!v) throw new Error(`missing context: -c ${key}=...`);
  return v;
};

const tenant = ctx('tenant') ?? 'demos';

new LatticeTenantStack(app, `lattice-${tenant}`, {
  tenant,
  apiDomain: required('apiDomain'),          // e.g. lattice-demos.example.com
  certificateArn: required('certificateArn'), // ACM cert for apiDomain (Step Functions HTTP Task requires HTTPS)
  hostedZoneId: ctx('hostedZoneId'),          // optional: create the Route53 A record
  hostedZoneName: ctx('hostedZoneName'),
  desiredCount: Number(ctx('desiredCount') ?? 2),
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION,
  },
  tags: { service: 'lattice', tenant },
});
