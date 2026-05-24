#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-local}"
ENV_FILE="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "$ENV_FILE" ]]; then
  ENV_FILE="$REPO_ROOT/.env.${PROFILE}.example"
elif [[ "$ENV_FILE" != /* ]]; then
  ENV_FILE="$REPO_ROOT/$ENV_FILE"
fi

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

unset SERVER_PORT || true
unset GATEWAY_PORT || true
export SPRING_PROFILES_ACTIVE="$PROFILE"

cd "$REPO_ROOT"
mvn -pl aichatpilot-chat -am -DskipTests install
mvn -pl aichatpilot-chat spring-boot:run
