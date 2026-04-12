#!/usr/bin/env bash
set -euo pipefail

echo "[hook] running pre-attempt-completion checks"

if [ -f ./mvnw ]; then
  ./mvnw -q -DskipTests compile
fi

echo "[hook] success"
