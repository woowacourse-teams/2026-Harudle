#!/usr/bin/env bash

set -Eeuo pipefail

readonly APP_DIR="/opt/harudle"
readonly IMAGE_DIR="${APP_DIR}/images"

case "$(uname -m)" in
  aarch64|arm64) ;;
  *)
    echo "The deployment target must be an ARM64 EC2 instance." >&2
    exit 1
    ;;
esac

command -v docker >/dev/null 2>&1 || {
  echo "Docker is not installed." >&2
  exit 1
}

docker compose version >/dev/null 2>&1 || {
  echo "Docker Compose v2 is not installed." >&2
  exit 1
}

command -v jq >/dev/null 2>&1 || {
  echo "jq is not installed. Install jq before deployment." >&2
  exit 1
}

install -d -m 0755 "${APP_DIR}" "${IMAGE_DIR}" "${APP_DIR}/monitoring"

if [[ ! -s "${APP_DIR}/.env" ]]; then
  echo "Create ${APP_DIR}/.env before the first deployment." >&2
  exit 1
fi

# Prevent collisions with files that may have been copied manually before
# CodeDeploy started managing this directory. The environment-specific .env is retained.
rm -f \
  "${APP_DIR}/compose.dev.yaml" \
  "${APP_DIR}/compose.prod.yaml" \
  "${APP_DIR}/monitoring/prometheus.yaml" \
  "${APP_DIR}/monitoring/cloudwatch-agent.dev.json" \
  "${APP_DIR}/monitoring/cloudwatch-agent.prod.json" \
  "${IMAGE_DIR}/backend-image.tar.gz" \
  "${IMAGE_DIR}/frontend-image.tar.gz" \
  "${IMAGE_DIR}/image-checksums.sha256"
