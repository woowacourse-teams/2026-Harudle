#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly APP_DIR="${HARUDLE_APP_DIR:-/opt/harudle}"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SQL_FILE="${SCRIPT_DIR}/backfill_user_public_profiles.sql"
readonly EXPECTED_ENV="${1:-}"
readonly BATCH_SIZE="${2:-100}"

if [[ "$#" -gt 2 || ! "${EXPECTED_ENV}" =~ ^(dev|prod)$ ]]; then
  echo "Usage: bash run_profile_backfill.sh <dev|prod> [batch_size=100]" >&2
  exit 1
fi

if [[ ! "${BATCH_SIZE}" =~ ^[1-9][0-9]{0,4}$ ]] || (( BATCH_SIZE > 10000 )); then
  echo "batch_size must be between 1 and 10000." >&2
  exit 1
fi

for command in docker jq; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "${command} is not installed." >&2
    exit 1
  }
done

if [[ ! -s "${SQL_FILE}" || ! -s "${APP_DIR}/.env" ]]; then
  echo "Missing maintenance SQL or ${APP_DIR}/.env. Deploy the maintenance files first." >&2
  exit 1
fi

if ! backend_id="$(docker compose --project-name harudle --env-file "${APP_DIR}/.env" \
  --file "${APP_DIR}/compose.prod.yaml" ps --status running --quiet backend 2>/dev/null)" \
  || [[ -z "${backend_id}" || "${backend_id}" == *$'\n'* ]]; then
  echo "Exactly one running Harudle backend is required." >&2
  exit 1
fi

if ! backend_env="$(docker inspect --format '{{json .Config.Env}}' "${backend_id}" 2>/dev/null \
  | jq -ce 'map(split("=") | {(.[0]): (.[1:] | join("="))}) | add')"; then
  echo "Cannot read the running backend environment." >&2
  exit 1
fi

if ! jq -e --arg expected "${EXPECTED_ENV}" \
  '.DEPLOY_ENV == $expected and (.DB_HOST | type == "string" and length > 0)
    and (.DB_PASSWORD | type == "string" and length > 0)' \
  <<<"${backend_env}" >/dev/null; then
  echo "Backend environment mismatch or missing DB_HOST/DB_PASSWORD. Check the deployment." >&2
  exit 1
fi

export PGHOST="$(jq -r '.DB_HOST' <<<"${backend_env}")"
export PGPORT="$(jq -r '.DB_PORT // "5432"' <<<"${backend_env}")"
export PGDATABASE="$(jq -r '.DB_NAME // "harudle"' <<<"${backend_env}")"
export PGUSER="$(jq -r '.DB_USERNAME // "harudle"' <<<"${backend_env}")"
export PGPASSWORD="$(jq -r '.DB_PASSWORD' <<<"${backend_env}")"
export PGOPTIONS='-c lock_timeout=2s'
export PGCONNECT_TIMEOUT=10
export PGSSLMODE="${PGSSLMODE:-require}"
unset backend_env

echo "Starting ${EXPECTED_ENV} public profile backfill (batch size: ${BATCH_SIZE})."
docker run --rm --network "container:${backend_id}" \
  --env PGHOST --env PGPORT --env PGDATABASE --env PGUSER --env PGPASSWORD \
  --env PGOPTIONS --env PGCONNECT_TIMEOUT --env PGSSLMODE \
  --mount "type=bind,source=${SQL_FILE},target=/maintenance/backfill.sql,readonly" \
  postgres:18-alpine psql -X --no-password --set ON_ERROR_STOP=1 \
  --file /maintenance/backfill.sql \
  --command "CALL pg_temp.backfill_user_public_profiles(${BATCH_SIZE});"
echo "Public profile backfill completed."
