#!/usr/bin/env bash
# Builds, pushes and deploys to AWS with Terraform.
#   ./scripts/deploy-aws.sh [image-tag]
# Requires: AWS credentials in the environment, docker, terraform. This creates billable resources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF="$ROOT/infra/terraform"
TAG="${1:-$(date +%Y%m%d%H%M%S)}"
REGION="${AWS_REGION:-us-east-1}"

echo "==> Ensuring ECR repositories exist"
terraform -chdir="$TF" init -input=false >/dev/null
terraform -chdir="$TF" apply -input=false -auto-approve \
  -target='aws_ecr_repository.repo["api"]' -target='aws_ecr_repository.repo["web"]'

API_REPO=$(terraform -chdir="$TF" output -raw ecr_api_repository)
WEB_REPO=$(terraform -chdir="$TF" output -raw ecr_web_repository)
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${API_REPO%%/*}"

echo "==> Building and pushing images ($TAG)"
docker build --platform linux/amd64 -t "$API_REPO:$TAG" "$ROOT/backend"
docker build --platform linux/amd64 -t "$WEB_REPO:$TAG" "$ROOT/frontend"
docker push "$API_REPO:$TAG"
docker push "$WEB_REPO:$TAG"

echo "==> Applying infrastructure"
terraform -chdir="$TF" apply -input=false -var "image_tag=$TAG"

CLUSTER=$(terraform -chdir="$TF" output -raw ecs_cluster)
echo "==> Waiting for services to stabilize"
aws ecs wait services-stable --region "$REGION" --cluster "$CLUSTER" --services api worker web
echo "Deployed: $(terraform -chdir="$TF" output -raw app_url)"
