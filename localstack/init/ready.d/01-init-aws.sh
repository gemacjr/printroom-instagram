#!/bin/bash
# Provisions the same resources Terraform creates in AWS, inside LocalStack.
# Runs automatically when LocalStack is ready (mounted into /etc/localstack/init/ready.d).
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET="instagram-media"
ACCOUNT="000000000000"
# Talk to the in-container endpoint directly: awslocal derives its endpoint from LOCALSTACK_HOST,
# which is the *host-facing* address (it may use a remapped port like 4567).
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
aws() { command aws --endpoint-url=http://localhost:4566 --region "$REGION" "$@"; }

echo "==> DynamoDB tables"
aws dynamodb create-table --table-name instagram-users \
  --attribute-definitions AttributeName=userId,AttributeType=S \
  --key-schema AttributeName=userId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

aws dynamodb create-table --table-name instagram-usernames \
  --attribute-definitions AttributeName=username,AttributeType=S \
  --key-schema AttributeName=username,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

aws dynamodb create-table --table-name instagram-posts \
  --attribute-definitions AttributeName=postId,AttributeType=S AttributeName=authorId,AttributeType=S AttributeName=publishedAt,AttributeType=N \
  --key-schema AttributeName=postId,KeyType=HASH \
  --global-secondary-indexes '[{"IndexName":"author-published-index","KeySchema":[{"AttributeName":"authorId","KeyType":"HASH"},{"AttributeName":"publishedAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST >/dev/null

aws dynamodb create-table --table-name instagram-follows \
  --attribute-definitions AttributeName=followerId,AttributeType=S AttributeName=followeeId,AttributeType=S \
  --key-schema AttributeName=followerId,KeyType=HASH AttributeName=followeeId,KeyType=RANGE \
  --global-secondary-indexes '[{"IndexName":"followee-index","KeySchema":[{"AttributeName":"followeeId","KeyType":"HASH"},{"AttributeName":"followerId","KeyType":"RANGE"}],"Projection":{"ProjectionType":"KEYS_ONLY"}}]' \
  --billing-mode PAY_PER_REQUEST >/dev/null

aws dynamodb create-table --table-name instagram-media \
  --attribute-definitions AttributeName=mediaId,AttributeType=S \
  --key-schema AttributeName=mediaId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

echo "==> SQS queues (+ DLQs with redrive after 5 receives)"
for q in instagram-media-events instagram-feed-fanout; do
  aws sqs create-queue --queue-name "${q}-dlq" >/dev/null
  DLQ_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:${q}-dlq"
  aws sqs create-queue --queue-name "$q" --attributes "{
    \"VisibilityTimeout\":\"120\",
    \"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
  }" >/dev/null
done

echo "==> S3 bucket + CORS (browser direct upload needs PUT and the ETag header for multipart)"
aws s3api create-bucket --bucket "$BUCKET" >/dev/null
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["GET","PUT","HEAD"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3000}]
}'
aws s3api put-bucket-policy --bucket "$BUCKET" --policy "{
  \"Version\":\"2012-10-17\",
  \"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::${BUCKET}/media/*\"}]
}"

echo "==> S3 ObjectCreated -> SQS (only originals, so derived renditions don't retrigger)"
aws s3api put-bucket-notification-configuration --bucket "$BUCKET" --notification-configuration "{
  \"QueueConfigurations\":[{
    \"QueueArn\":\"arn:aws:sqs:${REGION}:${ACCOUNT}:instagram-media-events\",
    \"Events\":[\"s3:ObjectCreated:*\"],
    \"Filter\":{\"Key\":{\"FilterRules\":[{\"Name\":\"prefix\",\"Value\":\"media/\"},{\"Name\":\"suffix\",\"Value\":\"/original\"}]}}
  }]
}"

echo "==> LocalStack resources ready"
