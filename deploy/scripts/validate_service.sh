#!/usr/bin/env bash

set -Eeuo pipefail

readonly APP_DIR="/opt/harudle"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly MAX_ATTEMPTS=60
readonly RETRY_INTERVAL_SECONDS=5

source "${SCRIPT_DIR}/compose_environment.sh"
configure_compose "${APP_DIR}"

cd "${APP_DIR}"

compose() {
  docker compose "${COMPOSE_ARGS[@]}" "$@"
}

container_health() {
  local container_id="$1"

  docker inspect \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "${container_id}"
}

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  backend_id="$(compose ps --quiet backend)"
  frontend_id="$(compose ps --quiet frontend)"

  if [[ -n "${backend_id}" && -n "${frontend_id}" ]]; then
    backend_health="$(container_health "${backend_id}")"
    frontend_health="$(container_health "${frontend_id}")"

    if [[ "${backend_health}" == "healthy" && "${frontend_health}" == "healthy" ]]; then
      frontend_address="$(compose port frontend 80 | tail -n 1)"

      if curl --fail --silent --show-error "http://${frontend_address}/health" >/dev/null; then
        metrics_address="$(compose port backend 8081 | tail -n 1)"
        if [[ "${metrics_address}" == 127.0.0.1:* ]] &&
          metrics_response="$(curl --fail --silent --show-error --max-time 10 \
            "http://${metrics_address}/actuator/prometheus")" &&
          grep -q '^# HELP jvm_memory_used_bytes' <<<"${metrics_response}"; then
          compose ps
          docker image prune --force >/dev/null
          exit 0
        fi
      fi
    fi
  fi

  echo "Waiting for containers to become healthy (${attempt}/${MAX_ATTEMPTS})..."
  sleep "${RETRY_INTERVAL_SECONDS}"
done

compose ps >&2
compose logs --no-color --tail 200 backend frontend >&2
exit 1
