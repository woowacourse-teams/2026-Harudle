#!/usr/bin/env bash

set -Eeuo pipefail

configure_compose() {
  local app_dir="$1"
  local env_file="${app_dir}/.env"
  local deploy_env

  deploy_env="$({
    sed -n \
      's/^[[:space:]]*DEPLOY_ENV[[:space:]]*=[[:space:]]*//p' \
      "${env_file}" || true
  } | tail -n 1 | tr -d '\r')"

  deploy_env="${deploy_env:-prod}"
  deploy_env="${deploy_env%\"}"
  deploy_env="${deploy_env#\"}"
  deploy_env="${deploy_env%\'}"
  deploy_env="${deploy_env#\'}"

  COMPOSE_ARGS=(
    --project-name harudle
    --env-file "${env_file}"
    --file "${app_dir}/compose.prod.yaml"
  )

  case "${deploy_env}" in
    prod) ;;
    dev)
      if [[ ! -s "${app_dir}/compose.dev.yaml" ]]; then
        echo "Missing ${app_dir}/compose.dev.yaml for the dev deployment." >&2
        return 1
      fi

      COMPOSE_ARGS+=(--file "${app_dir}/compose.dev.yaml")
      ;;
    *)
      echo "Unsupported DEPLOY_ENV: ${deploy_env}. Expected dev or prod." >&2
      return 1
      ;;
  esac

  readonly DEPLOY_ENV_VALUE="${deploy_env}"
  readonly COMPOSE_ARGS
}
