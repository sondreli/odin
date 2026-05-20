#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

STACK_NAME="${1:-odin-prod}"
REGION="${AWS_REGION:-eu-west-1}"

echo "=== Building ClojureScript release ==="
npx shadow-cljs release app

echo "=== Building Tailwind CSS ==="
npx tailwindcss build ./src/client/tw/style.css -o ./public/css/main.css 2>/dev/null || echo "Tailwind build skipped (no config found)"

echo "=== Getting S3 bucket from CloudFormation ==="
BUCKET=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='FrontendBucketName'].OutputValue" \
  --output text)

echo "=== Deploying to S3: $BUCKET ==="
# Static assets with long cache
aws s3 sync public/ "s3://$BUCKET/" \
  --delete \
  --cache-control "public, max-age=31536000" \
  --exclude "*.html"

# HTML with no-cache
aws s3 cp public/index.html "s3://$BUCKET/index.html" \
  --cache-control "no-cache, no-store, must-revalidate"

echo "=== Invalidating CloudFront cache ==="
DIST_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDistributionId'].OutputValue" \
  --output text)

aws cloudfront create-invalidation \
  --distribution-id "$DIST_ID" \
  --paths "/*" \
  --query "Invalidation.Id" \
  --output text

echo "=== Frontend deployed ==="
