#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

STACK_NAME="${1:-odin-prod}"
REGION="${AWS_REGION:-eu-west-1}"

echo "============================================"
echo "  Odin Full Deployment"
echo "  Stack: $STACK_NAME  Region: $REGION"
echo "============================================"

# Step 1: Build Lambda native binary
echo ""
echo "=== Step 1: Build Lambda ==="
./scripts/build-lambda.sh

# Step 2: Build frontend
echo ""
echo "=== Step 2: Build frontend ==="
npx shadow-cljs release app
npx tailwindcss build ./src/client/tw/style.css -o ./public/css/main.css 2>/dev/null || true

# Step 3: SAM deploy (reads parameters from samconfig.toml)
echo ""
echo "=== Step 3: SAM deploy ==="
sam deploy

# Step 4: Deploy frontend to S3
echo ""
echo "=== Step 4: Deploy frontend ==="
./scripts/deploy-frontend.sh "$STACK_NAME"

# Done
echo ""
echo "============================================"
echo "  Deployment complete!"
echo ""
CF_DOMAIN=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='CloudFrontDomainName'].OutputValue" \
  --output text)
echo "  CloudFront: https://$CF_DOMAIN"
echo "============================================"
