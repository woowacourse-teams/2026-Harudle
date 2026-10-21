#!/usr/bin/env bash
set -Eeuo pipefail
set +x

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "${script_dir}/../.." && pwd)"
jar_file="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
test_dir="$(mktemp -d)"
container_name="harudle-key-initialization-${RANDOM}-$$"
cleanup() {
  docker rm --force "${container_name}" >/dev/null 2>&1 || true
  rm -rf -- "${test_dir}"
}
trap cleanup EXIT

# All credentials, source text and canaries below are public, disposable test data.
# This script only connects to the PostgreSQL container it creates on a random localhost port.
export DIARY_SOURCE_TEXT_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
export DIARY_SOURCE_TEXT_ACTIVE_KEY_ID=primary
export DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE="${test_dir}/checks.json"
export DEPLOY_ENV=dev SPRING_PROFILES_ACTIVE=prod
export DIARY_SOURCE_TEXT_RELEASE_MODE=reader DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED=false
export SPRING_CONFIG_IMPORT="optional:file:${test_dir}/absent.properties"
export SPRING_DATASOURCE_USERNAME=harudle_key_test SPRING_DATASOURCE_PASSWORD=public-test-password
unset SPRING_APPLICATION_JSON LOADER_PATH LOADER_HOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
export LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain

docker run --detach --name "${container_name}" \
  --env POSTGRES_USER=harudle_key_test --env POSTGRES_PASSWORD=public-test-password \
  --env POSTGRES_DB=harudle_key_test --publish 127.0.0.1::5432 postgres:18-alpine \
  >"${test_dir}/docker.output" 2>&1
ready=false
for attempt in {1..60}; do
  if docker exec "${container_name}" pg_isready -U harudle_key_test -d harudle_key_test >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "${ready}" == true ]] || { echo 'Temporary PostgreSQL did not become ready.' >&2; exit 1; }
port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "${container_name}")"
[[ "${port}" =~ ^[0-9]+$ ]]
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${port}/harudle_key_test?connectTimeout=2"

sql() {
  if ! docker exec --interactive "${container_name}" psql -X -U harudle_key_test -d harudle_key_test \
    --set=ON_ERROR_STOP=1 "$@" >"${test_dir}/sql.output" 2>"${test_dir}/sql.error"; then
    echo 'Temporary PostgreSQL fixture failed.' >&2
    exit 1
  fi
}
run_check() {
  java -Xmx128m -cp "${jar_file}" org.springframework.boot.loader.launch.PropertiesLauncher "$@" \
    >"${test_dir}/output" 2>"${test_dir}/error"
}
expect_failure() {
  if run_check "$@"; then
    echo 'Packaged key operation unexpectedly succeeded.' >&2
    exit 1
  fi
  test ! -s "${test_dir}/output"
  test "$(cat "${test_dir}/error")" = 'Diary encryption key operation failed.'
}
assert_counts() {
  jq -e --argjson checked "$1" --argjson failed "$2" \
    'keys == ["checked", "failed"] and .checked == $checked and .failed == $failed' \
    "${test_dir}/output" >/dev/null
  test "$(wc -l < "${test_dir}/output" | tr -d ' ')" = 1
}

# An arbitrary empty database has no trustworthy schema provenance.
expect_failure initialize
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"

# Apply the repository's real legacy DDL. The small history fixture is only the CLI harness;
# the JUnit PostgreSQL integration suite separately runs actual Flyway V1, V23 and V28 migrations.
sql < "${repo_dir}/backend/src/main/resources/db/migration/V1__create_initial_schema.sql"
sql <<'SQL'
CREATE TABLE public.flyway_schema_history (
  installed_rank integer NOT NULL PRIMARY KEY,
  version varchar(50), description varchar(200) NOT NULL, type varchar(20) NOT NULL,
  script varchar(1000) NOT NULL, checksum integer, installed_by varchar(100) NOT NULL,
  installed_on timestamp NOT NULL DEFAULT now(), execution_time integer NOT NULL, success boolean NOT NULL
);
INSERT INTO public.flyway_schema_history
  (installed_rank, version, description, type, script, installed_by, execution_time, success)
VALUES (1, '1', 'public CLI legacy fixture', 'SQL', 'V1__create_initial_schema.sql', current_user, 0, true);
SQL
run_check initialize
test -f "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
cp "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}" "${test_dir}/trusted-backup.json"
run_check verify
expect_failure initialize
cmp "${test_dir}/trusted-backup.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"

sql < "${repo_dir}/backend/src/main/resources/db/migration/V28__encrypt_diary_source_text.sql"
sql <<'SQL'
INSERT INTO public.flyway_schema_history
  (installed_rank, version, description, type, script, installed_by, execution_time, success)
VALUES (2, '28', 'public CLI v1 fixture', 'SQL', 'V28__encrypt_diary_source_text.sql', current_user, 0, true);
SQL
# V28 empty and v0-only schemas are also valid first-initialization targets.
rm "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
run_check initialize
sql <<'SQL'
INSERT INTO users (id, name) VALUES ('00000000-0000-0000-0000-000000000002', 'Public test user');
INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
        CURRENT_DATE, 'Public legacy diary', 0);
SQL
rm "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
run_check initialize
cp "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}" "${test_dir}/trusted-backup.json"

# Use the golden canary as an ordinary source row with its authenticated IDs.
# It has no generation and is soft-deleted, so either filter would incorrectly skip it.
ciphertext="$(jq -r '.checks.primary.ciphertext' \
  "${repo_dir}/backend/src/test/resources/crypto/diary-key-checks-public-dev.json")"
sql --set=ciphertext="${ciphertext}" <<'SQL'
INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version, deleted_at)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
        CURRENT_DATE, :'ciphertext', 1, CURRENT_TIMESTAMP);
SQL
run_check verify-data
assert_counts 1 0
test ! -s "${test_dir}/error"

# Missing evidence must be restored, even when the operator supplies the historically correct key.
rm "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
expect_failure initialize
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
DIARY_SOURCE_TEXT_KEY_BASE64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk= expect_failure initialize
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
cp "${test_dir}/trusted-backup.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
run_check verify
run_check verify-data
assert_counts 1 0
cmp "${test_dir}/trusted-backup.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"

sql <<'SQL'
UPDATE diaries SET source_text = 'primary:damaged-public-test-payload'
WHERE id = '00000000-0000-0000-0000-000000000001';
SQL
if run_check verify-data; then
  echo 'Stored source authentication unexpectedly accepted a damaged row.' >&2
  exit 1
fi
assert_counts 1 1
test "$(cat "${test_dir}/error")" = 'Diary encryption key operation failed.'
sql --set=ciphertext="${ciphertext}" <<'SQL'
UPDATE diaries SET source_text = :'ciphertext' WHERE id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_version;
UPDATE diaries SET source_text_version = 2 WHERE id = '00000000-0000-0000-0000-000000000003';
SQL
expect_failure verify-data
sql <<'SQL'
UPDATE diaries SET source_text_version = 0 WHERE id = '00000000-0000-0000-0000-000000000003';
CREATE USER key_inspection_denied PASSWORD 'public-denied-password';
SQL
# A permission error must not be mistaken for a proven absence of encrypted rows.
rm "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
SPRING_DATASOURCE_USERNAME=key_inspection_denied SPRING_DATASOURCE_PASSWORD=public-denied-password \
  expect_failure initialize
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
cp "${test_dir}/trusted-backup.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
SPRING_DATASOURCE_USERNAME=key_inspection_denied SPRING_DATASOURCE_PASSWORD=public-denied-password \
  expect_failure verify-data
run_check verify-data
assert_counts 1 0
cmp "${test_dir}/trusted-backup.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
echo 'PASS: packaged initialization checks PostgreSQL and backup recovery authenticates actual stored v1 rows.'
