#!/usr/bin/env bash

set -Eeuo pipefail

require_image_setting() {
  local env_file="$1" name="$2" expected="$3" value count declaration_count

  count="$(grep -c "^[[:space:]]*${name}[[:space:]]*=" "${env_file}" || true)"
  declaration_count="$(grep -Ec "^[[:space:]]*(export[[:space:]]+)?${name}([[:space:]:=]|$)" "${env_file}" || true)"
  if [[ "${declaration_count}" != "${count}" ]]; then
    echo "Unsupported image environment declaration: ${name}. Use NAME=value once in ${env_file}." >&2
    return 1
  fi

  value="$(sed -n "s/^[[:space:]]*${name}[[:space:]]*=[[:space:]]*//p" "${env_file}" | tr -d '\r')"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"

  if [[ "${count}" != 1 || "${value}" != "${expected}" ]]; then
    echo "Image environment mismatch: ${name}. Check ${env_file}." >&2
    return 1
  fi
}

require_resolved_image_setting() {
  local compose_config="$1" name="$2" expected="$3"

  if ! jq -e --arg name "${name}" --arg expected "${expected}" \
    '.services.backend.environment[$name] == $expected' \
    <<<"${compose_config}" >/dev/null 2>&1; then
    echo "Resolved image environment mismatch: ${name}. Check the Compose backend environment." >&2
    return 1
  fi
}

configure_compose() {
  local app_dir="$1"
  local env_file="${app_dir}/.env"
  local deploy_env compose_config

  case "${DEPLOYMENT_GROUP_NAME:-}" in
    harudle-dev) deploy_env=dev ;;
    harudle-prod) deploy_env=prod ;;
    *) echo "DEPLOYMENT_GROUP_NAME must be harudle-dev or harudle-prod." >&2; return 1 ;;
  esac

  command -v jq >/dev/null 2>&1 || {
    echo "jq is not installed. Install jq before checking the deployment environment." >&2
    return 1
  }

  if [[ ! -s "${env_file}" ]]; then
    echo "Create ${env_file} before deployment." >&2
    return 1
  fi

  require_image_setting "${env_file}" DEPLOY_ENV "${deploy_env}" || return 1
  require_image_setting "${env_file}" S3_BUCKET techcourse-project-2026 || return 1
  require_image_setting "${env_file}" S3_GENERATED_PREFIX "harudle/generated/diary-images/${deploy_env}" || return 1
  require_image_setting "${env_file}" S3_REFERENCE_PREFIX "harudle/references/generation/${deploy_env}" || return 1

  COMPOSE_ARGS=(
    --project-name harudle
    --env-file "${env_file}"
    --file "${app_dir}/compose.prod.yaml"
  )
  if [[ "${deploy_env}" == dev ]]; then
    if [[ ! -s "${app_dir}/compose.dev.yaml" ]]; then
      echo "Missing ${app_dir}/compose.dev.yaml for the dev deployment." >&2
      return 1
    fi
    COMPOSE_ARGS+=(--file "${app_dir}/compose.dev.yaml")
  fi

  # Keep the rendered configuration and parser errors private: both may contain secrets.
  if ! compose_config="$(docker compose "${COMPOSE_ARGS[@]}" config --format json 2>/dev/null)"; then
    echo "Cannot resolve the Docker Compose configuration. Check the Compose files and .env." >&2
    return 1
  fi

  require_resolved_image_setting "${compose_config}" DEPLOY_ENV "${deploy_env}" || return 1
  require_resolved_image_setting "${compose_config}" S3_BUCKET techcourse-project-2026 || return 1
  require_resolved_image_setting "${compose_config}" S3_GENERATED_PREFIX "harudle/generated/diary-images/${deploy_env}" || return 1
  require_resolved_image_setting "${compose_config}" S3_REFERENCE_PREFIX "harudle/references/generation/${deploy_env}" || return 1

  readonly DEPLOY_ENV_VALUE="${deploy_env}"
  readonly COMPOSE_ARGS
}
