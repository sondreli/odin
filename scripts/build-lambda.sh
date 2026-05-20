#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "=== Building Lambda uberjar (odin.lambda, no Jetty) ==="
clj -T:build uber :main-ns odin.lambda

echo "=== Building GraalVM native image for Linux x86_64 ==="
docker run --rm \
  -v "$PROJECT_DIR":/project \
  -w /project \
  ghcr.io/graalvm/native-image-community:25-ol9 \
  native-image \
    -jar target/odin.jar \
    -o target/odin \
    --no-fallback \
    --features=clj_easy.graal_build_time.InitClojureClasses \
    --initialize-at-build-time=com.fasterxml.jackson,org.bouncycastle,org.slf4j \
    --initialize-at-run-time=org.bouncycastle.jcajce.provider.drbg \
    -H:+ReportExceptionStackTraces \
    -H:ReflectionConfigurationFiles=resources/META-INF/native-image/reflect-config.json \
    -H:ResourceConfigurationFiles=resources/META-INF/native-image/resource-config.json \
    --enable-url-protocols=https \
    -J-Xmx4g

echo "=== Packaging for Lambda ==="
mkdir -p target/lambda
cp target/odin target/lambda/bootstrap
chmod +x target/lambda/bootstrap

echo "=== Lambda binary ready: target/lambda/bootstrap ==="
ls -lh target/lambda/bootstrap
