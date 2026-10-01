# Printroom: an Instagram-style app

A full-stack implementation of the [Hello Interview Instagram system design](https://www.hellointerview.com/learn/system-design/problem-breakdowns/instagram):
Spring Boot API and workers, a Next.js web app, DynamoDB, S3, SQS, and Redis.
It runs locally on **LocalStack** and deploys to **AWS** with Terraform.

- Photo and video posts, uploaded **directly to S3** with presigned URLs (multipart for large videos)
- Follow and unfollow
- A chronological feed using **hybrid fan-out**: fan-out on write for most authors, and posts from
  celebrity authors merged in at read time

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the design, data model, and trade-offs.

## Layout

```
backend/     Spring Boot 3.5 (Java 21) API + SQS workers, JUnit/Mockito tests
frontend/    Next.js 16 (App Router) web app, Vitest tests
localstack/  Init script that creates tables, bucket, queues, and S3→SQS notifications
postman/     Collection (59 requests, 122 assertions), environments, image fixture
infra/       Terraform for AWS (VPC, DynamoDB, S3+CloudFront, SQS, ElastiCache, ECS Fargate, ALB)
scripts/     seed.sh (demo data), deploy-aws.sh
```

## Run locally

Requires Docker.

```bash
make up          # LocalStack, Redis, api, worker, web
make seed        # demo users. Log in as demo / password123
open http://localhost:3000
```

If ports 4566 or 6380 are taken (for example, by another LocalStack):
`make up LOCALSTACK_PORT=4567` (and `REDIS_PORT=...`).

- API: http://localhost:8080. Swagger UI: http://localhost:8080/swagger-ui.html
- Locally, `FEED_CELEBRITY_THRESHOLD=3`, so any user with 3+ followers takes the fan-out-on-read path.

For hot reload, run the backend and frontend on the host against the compose LocalStack and Redis:

```bash
docker compose up -d localstack redis
cd backend && AWS_ENDPOINT=http://localhost:4566 AWS_PUBLIC_ENDPOINT=http://localhost:4566 REDIS_PORT=6380 mvn spring-boot:run
cd frontend && npm run dev     # proxies /api to http://localhost:8080
```

## Tests

```bash
make test        # backend unit tests (86) + frontend unit tests (27), typecheck, lint
make postman     # end-to-end contract tests against the running stack
```

The Postman collection registers fresh users on every run, so it can be re-run. It exercises auth,
follows, a real presigned S3 upload, async processing (polling until READY), fan-out on write, the
celebrity merge on read, cursor pagination, unfollow, and deletes.
Import `postman/Printroom.postman_collection.json` plus an environment into the Postman app, or run it with
newman. Edit `postman/generate_collection.py` and regenerate the JSON; don't edit the JSON by hand.

## Deploy to AWS

```bash
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars   # set certificate_arn/app_origin
./scripts/deploy-aws.sh                                                          # builds, pushes to ECR, terraform apply
```

This creates billable resources (NAT gateway, ElastiCache, Fargate, ALB, CloudFront). Configure a
remote state backend in `infra/terraform/versions.tf` before sharing the stack. To run the Postman suite
against production, set `baseUrl` in `postman/Prod.postman_environment.json`.
