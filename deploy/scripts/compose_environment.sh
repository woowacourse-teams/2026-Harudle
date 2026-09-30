#!/usr/bin/env bash

set -Eeuo pipefail

require_image_setting() {
  local env_file="$1" name="$2" expected="$3" value count
  count="$(grep -c "^[[:space:]]*${name}[[:space:]]*=" "${env_file}" || true)"
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

configure_compose() {
  local app_dir="$1"
  local env_file="${app_dir}/.env"
  local deploy_env

  case "${DEPLOYMENT_GROUP_NAME:-}" in
    harudle-dev) deploy_env=dev ;;
    harudle-prod) deploy_env=prod ;;
    *) echo "DEPLOYMENT_GROUP_NAME must be harudle-dev or harudle-prod." >&2; return 1 ;;
  esac

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

  readonly DEPLOY_ENV_VALUE="${deploy_env}"
  readonly COMPOSE_ARGS
}
