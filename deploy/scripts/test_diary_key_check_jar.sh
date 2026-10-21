#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jar_file="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "${test_dir}"' EXIT
# Public test keys and fixed canary only. No database or application context is started.
# An unreachable endpoint proves verify/register do not acquire a database dependency.
export DIARY_SOURCE_TEXT_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
export DIARY_SOURCE_TEXT_ACTIVE_KEY_ID=primary
export DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE="${test_dir}/checks.json"
export DEPLOY_ENV=dev SPRING_PROFILES_ACTIVE=prod
export DIARY_SOURCE_TEXT_RELEASE_MODE=reader DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED=false
export SPRING_CONFIG_IMPORT="optional:file:${test_dir}/absent.properties"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1"
export SPRING_DATASOURCE_USERNAME=unavailable SPRING_DATASOURCE_PASSWORD=public-test-password
unset SPRING_APPLICATION_JSON LOADER_PATH LOADER_HOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
export LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain

run_check() {
  java -Xmx128m -cp "${jar_file}" org.springframework.boot.loader.launch.PropertiesLauncher "$@" \
    >"${test_dir}/output" 2>&1
}

expect_failure() {
  if run_check "$@"; then
    echo "Packaged key check accepted invalid evidence or configuration." >&2
    exit 1
  fi
  test "$(cat "${test_dir}/output")" = 'Diary encryption key operation failed.'
}

expect_failure verify
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
expect_failure initialize
test ! -e "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
cp "${script_dir}/../../backend/src/test/resources/crypto/diary-key-checks-public-dev.json" \
  "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"
cp "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}" "${test_dir}/original.json"
run_check verify
expect_failure verify-data
expect_failure initialize
cmp "${test_dir}/original.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"

DIARY_SOURCE_TEXT_KEY_BASE64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk= expect_failure verify
DIARY_SOURCE_TEXT_KEY_BASE64=invalid expect_failure verify
DEPLOY_ENV=prod expect_failure verify
SPRING_APPLICATION_JSON='{"harudle":{"diary":{"source-text-encryption":{"keys":{"primary":"invalid"}}}}}' \
  expect_failure verify
SPRING_APPLICATION_JSON='{"harudle":{"diary":{"source-text-encryption":{"new-writes-enabled":true}}}}' \
  expect_failure verify
DIARY_SOURCE_TEXT_RELEASE_MODE=writer expect_failure verify
DIARY_SOURCE_TEXT_RELEASE_MODE=writer DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED=true run_check verify
cmp "${test_dir}/original.json" "${DIARY_SOURCE_TEXT_KEY_VERIFICATION_FILE}"

# Imported profile settings participate in the same binder as the serving application.
printf 'harudle.diary.source-text-encryption.keys.primary=invalid\n' > "${test_dir}/bad.properties"
SPRING_CONFIG_IMPORT="file:${test_dir}/bad.properties" expect_failure verify
run_check verify
# System properties can override normal environment variables, but cannot reopen a reader revision.
if java -Xmx128m -Dharudle.diary.source-text-encryption.new-writes-enabled=true -cp "${jar_file}" \
  org.springframework.boot.loader.launch.PropertiesLauncher verify >"${test_dir}/output" 2>&1; then
  echo "System properties reopened a reader revision." >&2
  exit 1
fi
test "$(cat "${test_dir}/output")" = 'Diary encryption key operation failed.'
expect_failure unsupported
export SPRING_APPLICATION_JSON='{"harudle":{"diary":{"source-text-encryption":{"active-key-id":"next","keys":{"next":"YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk="}}}}}'
expect_failure verify
run_check register
run_check verify
echo "PASS: packaged verify/register are database independent; initialization and data checks fail closed."
