#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly APP_DIR="${HARUDLE_APP_DIR:-/opt/harudle}"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

source "${SCRIPT_DIR}/compose_environment.sh"
source "${SCRIPT_DIR}/diary_key_verification.sh"
source "${SCRIPT_DIR}/diary_writer_approval.sh"
configure_compose "${APP_DIR}"

cd "${APP_DIR}"

echo "Starting the ${DEPLOY_ENV_VALUE} Compose deployment."
if ! docker compose "${COMPOSE_ARGS[@]}" config --quiet >/dev/null 2>&1; then
  echo "Cannot validate the Compose deployment configuration." >&2
  exit 1
fi

verify_diary_writer_approval
verify_diary_source_text_keys
verify_diary_writer_approval

if ! docker compose "${COMPOSE_ARGS[@]}" \
  up --detach --no-build --force-recreate --remove-orphans >/dev/null 2>&1; then
  echo "Cannot start the Compose deployment." >&2
  exit 1
fi

echo "Compose deployment started."
