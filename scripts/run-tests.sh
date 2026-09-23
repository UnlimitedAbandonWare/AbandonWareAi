#!/usr/bin/env bash
set -euo pipefail

if [ -f "./gradlew" ]; then
  echo "[Gradle] Running tests..."
  ./gradlew --no-daemon clean test
elif [ -f "./mvnw" ]; then
  echo "[Maven] Running tests..."
  ./mvnw -q -DskipTests=false test
else
  echo "No Gradle or Maven wrapper found. Please run your build tool manually."
fi
