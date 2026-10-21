#!/usr/bin/env bash
set -euo pipefail

jar_file="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
test_dir="$(mktemp -d)"
container_id=
cleanup() {
  if [[ -n "${container_id}" ]]; then
    docker rm --force "${container_id}" >/dev/null 2>&1 || true
  fi
  rm -rf -- "${test_dir}"
}
trap cleanup EXIT

# An isolated disposable database and public test keys; never use configured application credentials.
container_id="$(docker run --detach --rm --publish 127.0.0.1::5432 \
  --env POSTGRES_USER=harudle --env POSTGRES_PASSWORD=public-migration-test \
  --env POSTGRES_DB=harudle_migration postgres:18-alpine)"
export DB_HOST=127.0.0.1 DB_USERNAME=harudle DB_PASSWORD=public-migration-test DB_NAME=harudle_migration
published="$(docker port "${container_id}" 5432/tcp)"
export DB_PORT="${published##*:}"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/harudle_migration"
export SPRING_DATASOURCE_USERNAME=harudle SPRING_DATASOURCE_PASSWORD=public-migration-test
ready=false
for attempt in {1..30}; do
  if docker exec "${container_id}" pg_isready -h 127.0.0.1 -U harudle -d harudle_migration >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "${ready}" == true ]] || { echo 'Packaged migration test database was not ready.' >&2; exit 1; }

docker exec -i "${container_id}" psql -X -v ON_ERROR_STOP=1 -U harudle -d harudle_migration \
  >/dev/null <<'SQL'
CREATE TABLE diaries (
  id UUID PRIMARY KEY, user_id UUID NOT NULL, diary_date DATE NOT NULL,
  source_text TEXT NOT NULL, source_text_version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE diary_generations (
  id UUID PRIMARY KEY, diary_id UUID UNIQUE NOT NULL REFERENCES diaries(id),
  status TEXT NOT NULL, idempotency_key UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL
);
-- Minimal CLI harness history; the integration suite separately applies the real Flyway migrations.
CREATE TABLE flyway_schema_history (version VARCHAR(50), type VARCHAR(20), success BOOLEAN);
INSERT INTO flyway_schema_history VALUES ('1', 'SQL', true), ('28', 'SQL', true);
INSERT INTO diaries VALUES
  ('10000000-0000-4000-8000-000000000001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '2026-10-20', 'orphan fixture', 0),
  ('20000000-0000-4000-8000-000000000002', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '2026-10-20', 'packaged migration fixture', 0);
INSERT INTO diary_generations VALUES
  ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000002', 'SUCCEEDED',
   '40000000-0000-4000-8000-000000000004',
   '0f422a577acaf0bdb71c0604c7156d2eb4cd75817aecae72e6aee077e1e7fe20');
SQL

export DIARY_SOURCE_TEXT_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
export DIARY_SOURCE_TEXT_ACTIVE_KEY_ID=primary DIARY_SOURCE_TEXT_RELEASE_MODE=writer
export DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED=true SPRING_PROFILES_ACTIVE=prod DEPLOY_ENV=dev
export DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE="${test_dir}/checks.json"
export HARUDLE_DIARY_SOURCE_TEXT_ENCRYPTION_VERIFICATION_FILE="${test_dir}/checks.json"
export SPRING_CONFIG_IMPORT="optional:file:${test_dir}/absent.properties"
unset SPRING_APPLICATION_JSON LOADER_PATH LOADER_HOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain \
  java -Xmx128m -cp "${jar_file}" org.springframework.boot.loader.launch.PropertiesLauncher initialize
export LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain

run_check() {
  local expected="$1" actual=0
  shift
  java -Xmx128m -cp "${jar_file}" org.springframework.boot.loader.launch.PropertiesLauncher "$@" \
    >"${test_dir}/result" 2>"${test_dir}/error" || actual=$?
  if [[ "${actual}" != "${expected}" ]]; then
    echo 'Packaged migration command returned an unexpected result.' >&2
    exit 1
  fi
  if [[ -s "${test_dir}/result" ]]; then
    jq -e 'type == "object" and (.legacyRows | type == "number")' "${test_dir}/result" >/dev/null
  fi
  if [[ -s "${test_dir}/error" ]]; then
    test "$(cat "${test_dir}/error")" = 'Diary source text migration failed.'
  fi
}

run_check 0 status
jq -e '.legacyRows == 2 and .finalized == false' "${test_dir}/result" >/dev/null
run_check 1 ready
run_check 1 verify
jq -e '.checked == 2 and .failed == 2' "${test_dir}/result" >/dev/null
run_check 2 migrate --batch-size 1 --max-batches 1
jq -e '.migrated == 1 and .orphanMigrated == 1 and .legacyRows == 1' "${test_dir}/result" >/dev/null
run_check 0 migrate --batch-size 100 --max-batches 1
jq -e '.migrated == 1 and .legacyRows == 0' "${test_dir}/result" >/dev/null
run_check 0 verify
jq -e '.checked == 2 and .failed == 0 and .finalized == false' "${test_dir}/result" >/dev/null
run_check 0 finalize
jq -e '.checked == 2 and .failed == 0 and .finalized == true' "${test_dir}/result" >/dev/null
run_check 0 ready
run_check 0 migrate
jq -e '.migrated == 0 and .finalized == true' "${test_dir}/result" >/dev/null
run_check 0 finalize
run_check 1 migrate --batch-size 1001
if docker exec "${container_id}" psql -X -v ON_ERROR_STOP=1 -U harudle -d harudle_migration \
  -c "UPDATE diaries SET source_text_version = 0;" >/dev/null 2>&1; then
  echo 'Finalized database accepted a legacy version.' >&2
  exit 1
fi
echo 'PASS: packaged migration resumes, verifies HMACs, finalizes, and gates readiness against PostgreSQL.'
