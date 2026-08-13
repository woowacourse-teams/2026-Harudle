#!/usr/bin/env bash

set -Eeuo pipefail

readonly APP_DIR="/opt/harudle"
readonly COMPOSE_FILE="${APP_DIR}/compose.prod.yaml"

cd "${APP_DIR}"

docker compose \
  --project-name harudle \
  --env-file "${APP_DIR}/.env" \
  --file "${COMPOSE_FILE}" \
  up --detach --no-build --force-recreate --remove-orphans
