#!/usr/bin/env bash
# Builds and deploys the whole stack: images to ECR, infrastructure with Terraform, web UI to S3.
# Run from Git Bash, macOS or Linux:  infra/aws/deploy.sh
# Needs: aws CLI (configured), terraform, docker (running), node/npm. Safe to re-run.
set -euo pipefail

# Git Bash on Windows rewrites arguments that start with "/" into Windows paths; keep them as-is.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TF="$ROOT/infra/aws/terraform"
cd "$ROOT"

TAG="$(git rev-parse --short HEAD)"
if [ -n "$(git status --porcelain)" ]; then
  TAG="$TAG-dirty-$(date +%s)" # uncommitted changes still get a fresh image
fi

step() { printf '\n==> %s\n' "$*"; }

# Terraform's AWS provider doesn't read `aws login` / SSO sessions from the CLI cache, so hand it
# the CLI's current temporary credentials through the environment (this process only).
if [ -z "${AWS_ACCESS_KEY_ID:-}" ]; then
  creds="$(aws configure export-credentials --format env-no-export 2>/dev/null || true)"
  if [ -n "$creds" ]; then
    eval "$(printf '%s\n' "$creds" | sed 's/^/export /')"
  fi
fi
aws sts get-caller-identity --query Arn --output text

step "Terraform init"
terraform -chdir="$TF" init -input=false >/dev/null

step "Create the ECR repositories (first run only; no-op afterwards)"
terraform -chdir="$TF" apply -input=false -auto-approve \
  -target='aws_ecr_repository.app' -target='aws_ecr_lifecycle_policy.app'

REGION="$(terraform -chdir="$TF" output -raw region)"
REGISTRY="$(terraform -chdir="$TF" output -raw registry)"

step "Log in to ECR"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

for app in orchestrator mock-apis; do
  step "Build and push $app:$TAG"
  docker build --platform linux/amd64 -t "$REGISTRY/store-assistant/$app:$TAG" "$ROOT/$app"
  docker push "$REGISTRY/store-assistant/$app:$TAG"
done

step "Apply infrastructure with image tag $TAG"
terraform -chdir="$TF" apply -input=false -auto-approve -var "image_tag=$TAG"

BUCKET="$(terraform -chdir="$TF" output -raw web_bucket)"
DISTRIBUTION="$(terraform -chdir="$TF" output -raw distribution_id)"
CLUSTER="$(terraform -chdir="$TF" output -raw cluster)"
SERVICE="$(terraform -chdir="$TF" output -raw service)"
URL="$(terraform -chdir="$TF" output -raw app_url)"

step "Build and upload the web UI"
(cd "$ROOT/web" && npm ci --silent && npm run build --silent)
aws s3 sync "$ROOT/web/dist" "s3://$BUCKET" --delete --region "$REGION"
aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION" --paths "/*" >/dev/null

step "Wait for the ECS service to become stable (JVM start + LLM warm-up, a few minutes)"
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE" --region "$REGION"

step "Done"
echo "App:  $URL"
echo "Logs: aws logs tail $(terraform -chdir="$TF" output -raw log_group) --follow --region $REGION"
