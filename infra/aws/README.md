# AWS deployment target

Planned for week 9 of the roadmap. Terraform modules go in this folder.

```
Route 53 → CloudFront (web, S3 origin)
        → ALB (HTTPS, WAF) → ECS Fargate: orchestrator (2+ tasks, autoscale on CPU and request count)
                                         ↘ ECS Fargate: tool adapters / mock APIs (or your real APIs via PrivateLink)
Amazon Cognito ─ user pool; ALB or Spring Security validates the JWT, replacing the X-User-Id demo header
Amazon Bedrock ─ Claude via VPC interface endpoint, so prompts never cross the public internet
ElastiCache Redis ─ response cache, PII vault (encrypted), sessions, rate limits
RDS Postgres + pgvector ─ audit trail, outbox, policy knowledge base
Secrets Manager ─ ANTHROPIC_API_KEY (if using the Claude API instead of Bedrock)
CloudWatch + X-Ray (ADOT collector) ─ logs, metrics, traces, budget alarms at 50/80/100%
ECR ─ images built by GitHub Actions (OIDC role, no long-lived AWS keys)
```

## Switching the orchestrator to Bedrock

```
LLM_PROVIDER=bedrock
AWS_REGION=us-east-1
FAST_MODEL=anthropic.claude-haiku-5-5
SMART_MODEL=anthropic.claude-sonnet-5-5
```

The ECS task role needs Bedrock invoke permissions for those two models. On Bedrock, the server-side refusal fallback isn't sent (it's a Claude API feature); the gateway already skips it when `provider=bedrock`.

## Order of work

1. ECR + ECS Fargate + ALB with the mock APIs (no data stores yet)
2. Cognito + JWT validation in the orchestrator
3. Bedrock VPC endpoint + IAM, switch provider
4. ElastiCache for cache, vault and sessions
5. RDS Postgres for audit + pgvector knowledge base
6. CloudWatch dashboards and budget alarms, then the k6 run against the deployed stack
