#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== Building uberjar (JDK: $(java -version 2>&1 | head -1)) ==="
clj -T:build uber

echo "=== Building native image ==="
native-image \
  -jar target/odin.jar \
  -o target/odin \
  --no-fallback \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time=odin.Main,com.fasterxml.jackson,org.bouncycastle,org.eclipse.jetty,org.slf4j \
  --initialize-at-run-time=org.bouncycastle.jcajce.provider.drbg \
  -H:+ReportExceptionStackTraces \
  -H:ReflectionConfigurationFiles=resources/META-INF/native-image/reflect-config.json \
  -H:ResourceConfigurationFiles=resources/META-INF/native-image/resource-config.json \
  --enable-url-protocols=https \
  -J-Xmx4g

echo "=== Done: target/odin ==="
