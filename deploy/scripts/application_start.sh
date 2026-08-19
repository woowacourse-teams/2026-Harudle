#!/usr/bin/env bash

set -Eeuo pipefail

readonly APP_DIR="/opt/harudle"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

source "${SCRIPT_DIR}/compose_environment.sh"
configure_compose "${APP_DIR}"

cd "${APP_DIR}"

echo "Starting the ${DEPLOY_ENV_VALUE} Compose deployment."
docker compose "${COMPOSE_ARGS[@]}" config --quiet
docker compose "${COMPOSE_ARGS[@]}" \
  up --detach --no-build --force-recreate --remove-orphans
