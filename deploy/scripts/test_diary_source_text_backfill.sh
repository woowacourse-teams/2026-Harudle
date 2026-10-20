#!/usr/bin/env bash

set -Eeuo pipefail
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TEST_DIR}"' EXIT
export HARUDLE_APP_DIR="${TEST_DIR}/app" DIARY_BACKFILL_TEST_DIR="${TEST_DIR}"
readonly IMAGE_ID="sha256:$(printf 'a%.0s' {1..64})"
export DIARY_BACKFILL_TEST_IMAGE="${IMAGE_ID}"
mkdir -p "${HARUDLE_APP_DIR}" "${TEST_DIR}/bin"
test_count=0

cat > "${TEST_DIR}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$1" == image ]]; then
  echo inspect-image >> "${DIARY_BACKFILL_TEST_DIR}/calls"
  [[ "${DIARY_BACKFILL_TEST_IMAGE_FAILURE:-0}" == 0 ]] || exit 1
  printf '%s\n' "${DIARY_BACKFILL_TEST_IMAGE}"
  exit 0
fi
[[ "$1" == compose ]]
shift
arguments=("$@")
last_file=
for ((index = 0; index < ${#arguments[@]}; index++)); do
  case "${arguments[index]}" in
    --file) last_file="${arguments[index + 1]}" ;;
    config|run) operation="${arguments[index]}"; break ;;
  esac
done
case "${operation}" in
  config)
    echo render >> "${DIARY_BACKFILL_TEST_DIR}/calls"
    echo 'diary-backfill-test-secret' >&2
    printf '{"services":{"backend":{"environment":{"DEPLOY_ENV":"dev","S3_BUCKET":"techcourse-project-2026","S3_GENERATED_PREFIX":"harudle/generated/diary-images/dev","S3_REFERENCE_PREFIX":"harudle/references/generation/dev","DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED":"true","DIARY_SOURCE_TEXT_RELEASE_MODE":"writer","DIARY_SOURCE_TEXT_KEY_BASE64":"diary-backfill-test-secret"}}}}\n'
    ;;
  run)
    echo run >> "${DIARY_BACKFILL_TEST_DIR}/calls"
    printf '%s\n' "$@" > "${DIARY_BACKFILL_TEST_DIR}/run.args"
    printf '%s\n' "${last_file}" > "${DIARY_BACKFILL_TEST_DIR}/override.path"
    cp "${last_file}" "${DIARY_BACKFILL_TEST_DIR}/override.contents"
    for ((index = 0; index < ${#arguments[@]}; index++)); do
      if [[ "${arguments[index]}" == --env ]]; then
        export "${arguments[index + 1]}"
      elif [[ "${arguments[index]}" == backend ]]; then
        sh "${arguments[@]:index + 1}"
        exit "$?"
      fi
    done
    exit 1
    ;;
  *) echo 'Unexpected Docker operation.' >&2; exit 1 ;;
esac
SH
cat > "${TEST_DIR}/bin/java" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$@" > "${DIARY_BACKFILL_TEST_DIR}/java.args"
printf '%s\n' "${LOADER_MAIN:-}" > "${DIARY_BACKFILL_TEST_DIR}/java.loader-main"
echo 'diary-backfill-test-secret' >&2
printf '%s\n' "${DIARY_BACKFILL_TEST_RESPONSE}"
exit "${DIARY_BACKFILL_TEST_EXIT:-0}"
SH
chmod +x "${TEST_DIR}/bin/docker" "${TEST_DIR}/bin/java"
export PATH="${TEST_DIR}/bin:${PATH}"

prepare_case() {
  : > "${TEST_DIR}/calls"
  rm -f "${TEST_DIR}/run.args" "${TEST_DIR}/override.path"
  export DIARY_BACKFILL_TEST_EXIT=0 DIARY_BACKFILL_TEST_IMAGE_FAILURE=0
  export JAVA_OPTS='-Dbackfill.parity=true -Dbackfill.secret=diary-backfill-test-secret'
  export LOADER_MAIN=untrusted.test.Main
  cat > "${HARUDLE_APP_DIR}/.env" <<'ENV'
DEPLOY_ENV=dev
S3_BUCKET=techcourse-project-2026
S3_GENERATED_PREFIX=harudle/generated/diary-images/dev
S3_REFERENCE_PREFIX=harudle/references/generation/dev
DIARY_SOURCE_TEXT_KEY_BASE64=diary-backfill-test-secret
ENV
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.prod.yaml"
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.dev.yaml"
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.diary-writer.yaml"
  printf 'writer\n' > "${HARUDLE_APP_DIR}/diary-source-text-release-mode"
}

response() {
  local command="$1" legacy="${2:-0}" failed="${3:-0}"
  local base
  base="$(jq -nc --arg command "${command}" --argjson legacy "${legacy}" \
    '{command:$command,legacyRows:$legacy,encryptedRows:12,unsupportedRows:0,processingLegacyRows:0,orphanRows:1,finalized:false}')"
  case "${command}" in
    migrate)
      export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c --argjson failed "${failed}" \
        '. + {attempted:2,migrated:1,skippedProcessing:0,failed:$failed,orphanMigrated:1}' <<<"${base}")"
      ;;
    verify|finalize)
      export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c --argjson failed "${failed}" \
        '. + {checked:12,failed:$failed}' <<<"${base}")"
      ;;
    *) export DIARY_BACKFILL_TEST_RESPONSE="${base}" ;;
  esac
}

assert_private_output() {
  if grep -Fq 'diary-backfill-test-secret' "${TEST_DIR}/output"; then
    echo 'FAIL: Raw configuration or candidate diagnostics escaped the runner.' >&2
    exit 1
  fi
}

run_expected() {
  local expected="$1" command="$2" actual=0
  shift 2
  bash -x "${SCRIPT_DIR}/run_diary_source_text_backfill.sh" dev \
    "--candidate-image=${IMAGE_ID}" "${command}" "$@" > "${TEST_DIR}/output" 2>&1 || actual=$?
  if [[ "${actual}" != "${expected}" ]]; then
    echo "FAIL: Expected exit ${expected}, received ${actual}." >&2
    return 1
  fi
  assert_private_output
  if [[ -f "${TEST_DIR}/override.path" ]]; then
    [[ ! -f "$(cat "${TEST_DIR}/override.path")" ]]
  fi
}

record_pass() {
  test_count=$((test_count + 1))
  printf 'PASS: %s\n' "$1"
}

for command in status migrate verify finalize ready; do
  prepare_case
  response "${command}"
  command_options=()
  case "${command}" in
    migrate|verify|finalize) command_options=(--batch-size 100) ;;
  esac
  run_expected 0 "${command}" ${command_options[@]+"${command_options[@]}"}
  printf 'inspect-image\nrender\nrun\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  grep -Fxq -- "    image: \"${IMAGE_ID}\"" "${TEST_DIR}/override.contents"
  grep -Fq 'pull_policy: never' "${TEST_DIR}/override.contents"
  grep -Fq 'logging: !override' "${TEST_DIR}/override.contents"
  grep -Fq 'driver: none' "${TEST_DIR}/override.contents"
  grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/run.args"
  grep -Fxq -- "${HARUDLE_APP_DIR}/compose.dev.yaml" "${TEST_DIR}/run.args"
  grep -Fxq -- '--no-deps' "${TEST_DIR}/run.args"
  grep -Fxq -- '--pull' "${TEST_DIR}/run.args"
  grep -Fxq -- 'never' "${TEST_DIR}/run.args"
  grep -Fxq -- 'com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain' \
    "${TEST_DIR}/java.loader-main"
  grep -Fxq -- '-Dbackfill.parity=true' "${TEST_DIR}/java.args"
  if [[ "${command}" != status && "${command}" != ready ]]; then
    grep -Fxq -- '--batch-size' "${TEST_DIR}/java.args"
    grep -Fxq -- '100' "${TEST_DIR}/java.args"
  fi
  if grep -Eq 'volumes:|awslogs|options:' "${TEST_DIR}/override.contents" ||
    grep -Eq -- '--service-ports|--publish|diary-backfill-test-secret' "${TEST_DIR}/run.args"; then
    echo 'FAIL: The runner changed mounts, published ports, or passed a secret as an argument.' >&2
    exit 1
  fi
  # bash -x reaches set +x before reading configuration. The sole public result is a typed JSON object.
  grep '^{' "${TEST_DIR}/output" | jq -e --arg command "${command}" '.command == $command' >/dev/null
  record_pass "${command}: explicit candidate, Compose parity, private diagnostics, and typed result"
done

prepare_case
response migrate 3
export DIARY_BACKFILL_TEST_EXIT=2
run_expected 2 migrate --batch-size 1 --max-batches 10000
grep -Fq 'migration remains pending' "${TEST_DIR}/output"
grep '^{' "${TEST_DIR}/output" | jq -e '.legacyRows == 3 and .failed == 0' >/dev/null
record_pass 'bounded migrate preserves pending exit 2 and safe counts'

prepare_case
response migrate 3 1
export DIARY_BACKFILL_TEST_EXIT=1
run_expected 1 migrate
grep '^{' "${TEST_DIR}/output" | jq -e '.failed == 1' >/dev/null
record_pass 'a failed batch exposes only validated counts and exits unsuccessfully'

for malformed in diagnostic extra-field multiple-objects wrong-command string-count negative-count fractional-count invalid-finalized; do
  prepare_case
  response status
  case "${malformed}" in
    diagnostic) export DIARY_BACKFILL_TEST_RESPONSE='diary-backfill-test-secret' ;;
    extra-field) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '. + {secret:"diary-backfill-test-secret"}' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
    multiple-objects) export DIARY_BACKFILL_TEST_RESPONSE="${DIARY_BACKFILL_TEST_RESPONSE}"$'\n'"${DIARY_BACKFILL_TEST_RESPONSE}" ;;
    wrong-command) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '.command="ready"' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
    string-count) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '.legacyRows="diary-backfill-test-secret"' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
    negative-count) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '.legacyRows=-1' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
    fractional-count) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '.legacyRows=0.5' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
    invalid-finalized) export DIARY_BACKFILL_TEST_RESPONSE="$(jq -c '.finalized="diary-backfill-test-secret"' <<<"${DIARY_BACKFILL_TEST_RESPONSE}")" ;;
  esac
  run_expected 1 status
  [[ "$(grep -c '^{' "${TEST_DIR}/output" || true)" == 0 ]]
  record_pass "${malformed}: untrusted output is rejected without exposing it"
done

prepare_case
response ready
run_expected 1 ready --batch-size 100
[[ ! -s "${TEST_DIR}/calls" ]]
record_pass 'ready rejects fetch options before Docker, matching the packaged CLI'

prepare_case
response status
export DIARY_BACKFILL_TEST_IMAGE_FAILURE=1
run_expected 1 status
[[ ! -f "${TEST_DIR}/run.args" ]]
record_pass 'an unavailable candidate never starts maintenance'

for options in '--batch-size 1' '--batch-size 0' '--batch-size 1001' '--batch-size 1 --batch-size 2' '--max-batches 1' '--untrusted diary-backfill-test-secret'; do
  prepare_case
  response status
  read -r -a option_arguments <<<"${options}"
  run_expected 1 status "${option_arguments[@]}"
  [[ ! -s "${TEST_DIR}/calls" ]]
  record_pass 'invalid options fail before Docker or configuration access'
done

prepare_case
response status
if bash "${SCRIPT_DIR}/run_diary_source_text_backfill.sh" dev --candidate-image=harudle-backend:local \
  status > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: A mutable image tag was accepted.' >&2
  exit 1
fi
[[ ! -s "${TEST_DIR}/calls" ]]
assert_private_output
record_pass 'mutable tags cannot select the serving image by accident'

printf 'Diary source text runner: %s tests passed.\n' "${test_count}"
