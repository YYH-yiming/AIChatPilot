#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-local}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env.$PROFILE"
FALLBACK_ENV="$REPO_ROOT/.env.$PROFILE.example"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
elif [[ -f "$FALLBACK_ENV" ]]; then
  set -a
  source "$FALLBACK_ENV"
  set +a
fi

export SPRING_PROFILES_ACTIVE="$PROFILE"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

cd "$REPO_ROOT"
mvn -pl aichatpilot-agent -am -DskipTests install
mvn -pl aichatpilot-agent spring-boot:run
