# AWS deployment

Terraform in [`terraform/`](terraform/) deploys a lean, public demo of the assistant in `us-east-1`, using Claude in Amazon Bedrock. [`deploy.sh`](deploy.sh) builds and ships everything in one command.

## What gets deployed

```
Browser ─HTTPS─▶ CloudFront (*.cloudfront.net, no domain needed)
                  ├─ /*      ─▶ S3 bucket, private (React build, read through Origin Access Control)
                  └─ /api/*  ─▶ ALB :80 (accepts CloudFront's IP ranges + a secret header only)
                                 └─▶ ECS Fargate Spot, 1 task (0.5 vCPU, 1 GB)
                                       ├─ orchestrator :8080 ─▶ Claude in Amazon Bedrock (task IAM role, no API key)
                                       └─ mock-apis    :4000 (same task, reached on localhost)
CloudWatch Logs (7 days) · ECR (last 5 images) · AWS Budgets email alerts at 50/80/100%
```

| Decision | Why |
|---|---|
| **One task** | Sessions, the PII vault and pending confirmations are in memory. With two tasks, a Confirm click could reach the task that never saw the cancellation. Terraform refuses `desired_count > 1` until Redis is added. |
| **Public subnets, no NAT gateway** | Saves about $32/month. Security groups still allow no inbound traffic except ALB → task on 8080. |
| **CloudFront in front of the ALB** | HTTPS without buying a domain, and one origin for the UI and API, so no CORS. The `X-Origin-Verify` header stops anyone calling the ALB directly. |
| **Bedrock through the task role** | No secret to store or rotate. Requests are signed with short-lived credentials. |
| **Fargate Spot** | About 70% cheaper. If AWS reclaims the task, ECS starts a new one in a few minutes. Set `use_spot = false` before a demo you can't risk. |
| **`DEBUG_ENDPOINTS=false`, `LLM_BUDGET_USD=10`** | The URL is public: never expose raw LLM inputs, and keep the app's own spend cap low. |

**Estimated cost: about $33-40/month while running, plus Bedrock usage.** ALB about $17, three public IPv4 addresses about $11, Fargate Spot about $5-7, and CloudFront, S3, logs and ECR about $1. `terraform destroy` takes it to $0. Check the AWS pricing pages before relying on these numbers.

## One-time setup

1. **Install the tools** (then open a new terminal):
   ```bash
   winget install Amazon.AWSCLI
   winget install Hashicorp.Terraform
   ```
   Start Docker Desktop too; the deploy builds Linux images.

2. **Configure AWS credentials** for an IAM user or SSO role with admin rights in your own account:
   ```bash
   aws configure
   aws sts get-caller-identity
   ```

3. **Enable Claude in Bedrock.** In the AWS console, open Bedrock in **us-east-1** → Model access, and request access to **Claude Haiku 5.5** and **Claude Sonnet 5.5**. Wait until both show as granted.

4. **Set your alert email:**
   ```bash
   cp infra/aws/terraform/terraform.tfvars.example infra/aws/terraform/terraform.tfvars
   ```
   Edit `alert_email` in `terraform.tfvars`. The file is git-ignored. After the first deploy, AWS emails you to confirm the budget alerts.

5. **Check the Terraform** before it touches AWS:
   ```bash
   terraform -chdir=infra/aws/terraform init
   terraform -chdir=infra/aws/terraform validate
   terraform -chdir=infra/aws/terraform plan
   ```

## Deploy

From the repository root in Git Bash:

```bash
infra/aws/deploy.sh
```

The script runs these steps:
1. Creates the ECR repositories.
2. Builds and pushes both images, tagged with the git commit.
3. Applies the rest of the infrastructure.
4. Uploads the web build to S3 and invalidates CloudFront.
5. Waits for the task to pass its readiness check, which comes after the LLM warm-up.

The first run takes about 10-15 minutes, mostly CloudFront. It prints the app URL at the end. Re-run it after every change.

**Check it:**
```bash
aws logs tail /ecs/store-assistant --follow --region us-east-1
```
Look for `Warm-up finished in … ms`. A `Warm-up failed` with 403 or `AccessDenied` means model access isn't granted yet, or the IAM action needs adjusting (see below).

**Run the evals against it:**
```bash
node evals/run-evals.mjs --base https://<your-distribution>.cloudfront.net
```
The PII leak check will report that it can't read `/api/debug/llm-inputs`, because debug endpoints are off in AWS. Answers, latency and cost are still measured. The leak check stays a local and CI gate.

## Pause and tear down

```bash
terraform -chdir=infra/aws/terraform apply -var desired_count=0
```
This stops compute (about $5-7/month saved). The ALB and IPs keep billing.

```bash
terraform -chdir=infra/aws/terraform destroy
```
This removes everything, including images and the web bucket, and costs $0 afterwards.

## Known gaps (next steps)

1. **Anyone with the URL can chat.** The `X-User-Id` header is a demo stand-in, and the data is mock data. Next step: a Cognito user pool, with Spring Security validating the JWT and taking the user ID from the token.
2. **No rate limiting.** The protection today is the app's `LLM_BUDGET_USD` cap (in memory, resets on restart) and the AWS Budgets alerts. Next step: an AWS WAF rate-based rule on CloudFront (about $6/month).
3. **IAM resource is `*`.** The task role can call `bedrock-mantle:CreateInference` on any model. Narrow it to the two model ARNs; CloudTrail shows their exact format after the first calls.
4. **Production network.** Private subnets with a NAT gateway, plus VPC endpoints for Bedrock, ECR and CloudWatch Logs, so traffic stays inside AWS.
5. **State and scale.** ElastiCache Redis for the cache, vault and sessions, which allows more than one task and autoscaling. RDS Postgres with pgvector for the audit trail and knowledge base.
6. **CI/CD.** GitHub Actions deploys through an OIDC role instead of a laptop, so there are no long-lived AWS keys.
7. **Terraform state.** Move it to S3 with locking when more than one person deploys.

On Bedrock the server-side refusal fallback isn't available; the gateway already skips it when `LLM_PROVIDER=bedrock`.
