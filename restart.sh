#!/usr/bin/env bash
set -e

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/java/jdk-25.0.4+7}"
PORT="${PORT:-8081}"

echo "Stopping app on port $PORT..."
fuser -k "${PORT}/tcp" 2>/dev/null || true
sleep 2

echo "Starting app on port $PORT..."
setsid ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}" \
  > /tmp/opencode/app.log 2>&1 < /dev/null &
disown

echo "Launched. Streaming live logs (Ctrl+C to detach, app keeps running)..."
tail -f /tmp/opencode/app.log
