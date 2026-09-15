# infra

One CDK stack per tenant. Requires an ACM certificate for the API hostname because
Step Functions HTTP Tasks only call HTTPS endpoints.

```bash
npm ci
npx cdk bootstrap                # once per account/region
npx cdk deploy \
  -c tenant=demos \
  -c apiDomain=lattice-demos.example.com \
  -c certificateArn=arn:aws:acm:us-east-1:123456789012:certificate/... \
  -c hostedZoneId=Z0123456789 -c hostedZoneName=example.com   # optional: creates the A record
```

Start a workflow:

```bash
aws stepfunctions start-execution \
  --state-machine-arn "$(aws cloudformation describe-stacks --stack-name lattice-demos \
      --query "Stacks[0].Outputs[?OutputKey=='StateMachineArn'].OutputValue" --output text)" \
  --input '{"seed":{"applicantId":"good-42","requestedAmount":5000,"statedIncome":90000}}'
```
