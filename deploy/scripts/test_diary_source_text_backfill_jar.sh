#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jar_file="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
test_dir="$(mktemp -d)"
compose_project="harudle-diary-image-smoke-$$"
container_id=
cleanup() {
  if [[ -f "${test_dir}/compose-image-id.yaml" ]]; then
    docker compose --project-name "${compose_project}" --env-file /dev/null \
      --file "${test_dir}/compose-image-id.yaml" down --remove-orphans >/dev/null 2>&1 || true
  fi
  if [[ -n "${container_id}" ]]; then
    docker rm --force "${container_id}" >/dev/null 2>&1 || true
  fi
  rm -rf -- "${test_dir}"
}
trap cleanup EXIT

docker compose version >/dev/null 2>&1 || {
  echo 'Packaged migration tests require Docker Compose; the image-ID smoke cannot be skipped.' >&2
  exit 1
}

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

# Reuse the database image already present locally. No app image, production Compose file,
# application environment, or key mount participates in this actual Compose compatibility check.
image_id="$(docker inspect --format '{{.Image}}' "${container_id}")"
[[ "${image_id}" =~ ^sha256:[a-f0-9]{64}$ ]]
cat > "${test_dir}/compose-image-id.yaml" <<YAML
services:
  backend:
    image: "${image_id}"
    pull_policy: never
    depends_on: [dependency]
    ports: ["127.0.0.1::5432"]
    logging:
      driver: json-file
  dependency:
    image: "${image_id}"
    pull_policy: never
    entrypoint: [sh, -c]
    command: ["sleep 60"]
YAML
smoke_id="$(docker compose --project-name "${compose_project}" --env-file /dev/null \
  --file "${test_dir}/compose-image-id.yaml" run --detach --rm --no-deps --pull never -T \
  --entrypoint sh backend -c 'printf "HARUDLE_IMAGE_ID_SMOKE\n"; sleep 60')"
docker inspect "${smoke_id}" | jq -e --arg image "${image_id}" '
  length == 1 and (.[0] | .Image == $image and .Config.Image == $image and
    .HostConfig.AutoRemove == true and ((.HostConfig.PortBindings // {}) | length) == 0)
' >/dev/null
[[ -z "$(docker ps --all --filter "label=com.docker.compose.project=${compose_project}" \
  --filter label=com.docker.compose.service=dependency --format '{{.ID}}')" ]]
smoke_ready=false
for attempt in {1..10}; do
  if [[ "$(docker logs "${smoke_id}" 2>/dev/null)" == HARUDLE_IMAGE_ID_SMOKE ]]; then
    smoke_ready=true
    break
  fi
  sleep 0.2
done
[[ "${smoke_ready}" == true ]] || { echo 'Compose image-ID sentinel did not execute.' >&2; exit 1; }
docker stop --time 1 "${smoke_id}" >/dev/null
smoke_removed=false
for attempt in {1..10}; do
  if ! docker container inspect "${smoke_id}" >/dev/null 2>&1; then
    smoke_removed=true
    break
  fi
  sleep 0.2
done
[[ "${smoke_removed}" == true ]] || { echo 'Compose did not remove its one-off container.' >&2; exit 1; }
echo 'PASS: actual Compose runs the exact loaded sha256 image ID without dependencies or published ports and removes it.'

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
INSERT INTO flyway_schema_history VALUES ('1', 'SQL', true), ('38', 'SQL', true);
INSERT INTO diaries VALUES
  ('10000000-0000-4000-8000-000000000001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '2026-10-20', 'orphan fixture', 0),
  ('20000000-0000-4000-8000-000000000002', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '2026-10-20', 'packaged migration fixture', 0),
  ('50000000-0000-4000-8000-000000000005', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '2026-10-20', 'pending migration fixture', 0);
INSERT INTO diary_generations VALUES
  ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000002', 'SUCCEEDED',
   '40000000-0000-4000-8000-000000000004',
   '0f422a577acaf0bdb71c0604c7156d2eb4cd75817aecae72e6aee077e1e7fe20'),
  ('60000000-0000-4000-8000-000000000006', '50000000-0000-4000-8000-000000000005', 'PENDING',
   '70000000-0000-4000-8000-000000000007',
   '744dab8ccca8db62d32d8e3a68de75a04b0c047a2b8efc7b9fedc40e6ae39aae');
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
  jq -se --arg command "$1" 'length == 1 and (.[0] | type == "object" and .command == $command and
    ((.legacyRows | type == "number") or
     ((keys | sort) == ["command", "errorCode"] and (.errorCode | type == "string"))))' \
    "${test_dir}/result" >/dev/null
  if [[ -s "${test_dir}/error" ]]; then
    test "$(cat "${test_dir}/error")" = 'Diary source text migration failed.'
  fi
}

run_check 0 status
jq -e '.legacyRows == 3 and .finalized == false' "${test_dir}/result" >/dev/null
run_check 1 ready
jq -e '. == {command:"ready",errorCode:"DATABASE_OPERATION_FAILED"}' "${test_dir}/result" >/dev/null
run_check 1 verify
jq -e '.checked == 3 and .failed == 3' "${test_dir}/result" >/dev/null
run_check 2 migrate --batch-size 1 --max-batches 1
jq -e '.migrated == 1 and .orphanMigrated == 1 and .legacyRows == 2' "${test_dir}/result" >/dev/null
run_check 0 migrate --batch-size 100 --max-batches 1
jq -e '.migrated == 2 and .legacyRows == 0' "${test_dir}/result" >/dev/null
run_check 0 verify
jq -e '.checked == 3 and .failed == 0 and .finalized == false' "${test_dir}/result" >/dev/null
run_check 0 finalize
jq -e '.checked == 3 and .failed == 0 and .finalized == true' "${test_dir}/result" >/dev/null
run_check 0 ready
run_check 0 migrate
jq -e '.migrated == 0 and .finalized == true' "${test_dir}/result" >/dev/null
run_check 0 finalize
run_check 1 migrate --batch-size 1001
jq -e '. == {command:"migrate",errorCode:"CONFIGURATION_INVALID"}' "${test_dir}/result" >/dev/null
if docker exec "${container_id}" psql -X -v ON_ERROR_STOP=1 -U harudle -d harudle_migration \
  -c "UPDATE diaries SET source_text_version = 0;" >/dev/null 2>&1; then
  echo 'Finalized database accepted a legacy version.' >&2
  exit 1
fi
# Exercise the actual Python fixture command with a real JVM. Only Docker's transport is replaced;
# dev stdout GC logs must not corrupt the array consumed by ec2.py.
python3 - "${script_dir}/../.." "${jar_file}" "${test_dir}" <<'PY'
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from unittest.mock import patch

root, jar, test_directory = map(Path, sys.argv[1:])
sys.path.insert(0, str(root / 'backend/load-test'))
import ec2

container = 'a' * 64
fixture = [{'diaryId': '00000000-0000-0000-0000-000000000001',
            'userId': '00000000-0000-0000-0000-000000000002',
            'diaryDate': '2026-10-26', 'idempotencyKey': '00000000-0000-0000-0000-000000000003'}]
environment = {
    'PATH': os.environ['PATH'], 'DEPLOY_ENV': 'dev', 'DB_NAME': 'harudle_dev',
    'S3_BUCKET': 'techcourse-project-2026', 'S3_GENERATED_PREFIX': 'harudle/generated/diary-images/dev',
    'DIARY_SOURCE_TEXT_KEY_BASE64': 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
    'DIARY_SOURCE_TEXT_ACTIVE_KEY_ID': 'primary', 'DIARY_SOURCE_TEXT_RELEASE_MODE': 'writer',
    'DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED': 'true', 'SPRING_PROFILES_ACTIVE': 'prod',
    'DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE': str(root / 'backend/src/test/resources/crypto/diary-key-checks-public-dev.json'),
    'SPRING_CONFIG_IMPORT': f'optional:file:{test_directory}/absent.properties',
    'DB_PASSWORD': 'public-unused-fixture-password',
    'JAVA_OPTS': ('-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError '
                  '-Xlog:gc*,safepoint:stdout:time,uptime,level,tags '
                  '-Dloader.main=unexpected.Main -Xmx256m'),
}
real_run = subprocess.run


def local_jvm(command, **options):
    assert command[:6] == ['docker', 'exec', '-i', container, 'sh', '-c']
    shell_command = command[6].replace('/app/app.jar', shlex.quote(str(jar)))
    return real_run(['sh', '-c', shell_command], env=environment, **options)


with patch.object(ec2.subprocess, 'run', side_effect=local_jvm):
    protected = ec2.encrypt_seed(container, fixture)
assert len(protected) == 1
row = protected[0]
assert set(row) == {'diaryId', 'sourceText', 'requestFingerprint'}
assert row['diaryId'] == fixture[0]['diaryId']
assert re.fullmatch(r'primary:[A-Za-z0-9+/]+={0,2}', row['sourceText'])
assert re.fullmatch(r'[0-9a-f]{64}', row['requestFingerprint'])
assert '부하 테스트 준비 데이터' not in json.dumps(protected, ensure_ascii=False)
print('PASS: packaged fixture encryption preserves dev JVM settings and emits a clean JSON array.')
PY
echo 'PASS: packaged migration resumes, verifies HMACs, finalizes, and gates readiness against PostgreSQL.'
