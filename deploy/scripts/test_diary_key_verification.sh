#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
export HARUDLE_APP_DIR="${TEST_DIR}/app"
export DIARY_KEY_TEST_DIR="${TEST_DIR}"
mkdir -p "${HARUDLE_APP_DIR}" "${TEST_DIR}/bin"
test_count=0

cat > "${TEST_DIR}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$1" == compose ]]
shift
arguments=("$@")
command_name=
last_file=
for ((index = 0; index < ${#arguments[@]}; index++)); do
  case "${arguments[index]}" in
    --file) last_file="${arguments[index + 1]}" ;;
    config|run|up) command_name="${arguments[index]}"; break ;;
  esac
done
case "${command_name}" in
  config)
    if [[ " $* " == *' --format json '* ]]; then
      echo render >> "${DIARY_KEY_TEST_DIR}/calls"
      if [[ "${DIARY_KEY_TEST_RENDER_FAILURE:-0}" != 0 ]]; then
        echo 'diary-key-test-secret' >&2
        exit 1
      fi
      writes=false
      mode=reader
      if [[ "${last_file}" == *compose.diary-writer.yaml ]]; then
        writes=true
        mode=writer
      fi
      printf '{"services":{"backend":{"environment":{"DEPLOY_ENV":"%s","S3_BUCKET":"techcourse-project-2026","S3_GENERATED_PREFIX":"harudle/generated/diary-images/%s","S3_REFERENCE_PREFIX":"harudle/references/generation/%s","DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED":"%s","DIARY_SOURCE_TEXT_RELEASE_MODE":"%s","DIARY_SOURCE_TEXT_KEY_BASE64":"diary-key-test-secret"}}}}\n' \
        "${DIARY_KEY_TEST_ENV}" "${DIARY_KEY_TEST_ENV}" "${DIARY_KEY_TEST_ENV}" "${writes}" "${mode}"
    else
      echo quiet >> "${DIARY_KEY_TEST_DIR}/calls"
      echo 'diary-key-test-secret' >&2
      exit "${DIARY_KEY_TEST_QUIET_FAILURE:-0}"
    fi
    ;;
  run)
    echo verify >> "${DIARY_KEY_TEST_DIR}/calls"
    printf '%s\n' "$@" > "${DIARY_KEY_TEST_DIR}/verify.args"
    printf '%s\n' "${last_file}" > "${DIARY_KEY_TEST_DIR}/override.path"
    cp "${last_file}" "${DIARY_KEY_TEST_DIR}/override.contents"
    echo 'diary-key-test-secret'
    echo 'diary-key-test-secret' >&2
    if [[ "${DIARY_KEY_TEST_VERIFY_FAILURE:-0}" == 0 ]]; then
      for ((index = 0; index < ${#arguments[@]}; index++)); do
        if [[ "${arguments[index]}" == --env ]]; then
          export "${arguments[index + 1]}"
        fi
      done
      sh -c "${arguments[${#arguments[@]} - 1]}"
    fi
    exit "${DIARY_KEY_TEST_VERIFY_FAILURE:-0}"
    ;;
  up)
    echo up >> "${DIARY_KEY_TEST_DIR}/calls"
    printf '%s\n' "$@" > "${DIARY_KEY_TEST_DIR}/up.args"
    echo 'diary-key-test-secret'
    echo 'diary-key-test-secret' >&2
    exit "${DIARY_KEY_TEST_UP_FAILURE:-0}"
    ;;
  *) echo 'Unexpected Docker operation.' >&2; exit 1 ;;
esac
SH
cat > "${TEST_DIR}/bin/java" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$@" > "${DIARY_KEY_TEST_DIR}/java.args"
printf '%s\n' "${LOADER_MAIN:-}" > "${DIARY_KEY_TEST_DIR}/java.loader-main"
SH
chmod +x "${TEST_DIR}/bin/docker" "${TEST_DIR}/bin/java"
export PATH="${TEST_DIR}/bin:${PATH}"

prepare_case() {
  local environment="$1" release_mode="${2:-reader}"
  export DIARY_KEY_TEST_ENV="${environment}"
  export DEPLOYMENT_GROUP_NAME="harudle-${environment}"
  export DIARY_KEY_TEST_RENDER_FAILURE=0 DIARY_KEY_TEST_QUIET_FAILURE=0
  export DIARY_KEY_TEST_VERIFY_FAILURE=0 DIARY_KEY_TEST_UP_FAILURE=0
  export JAVA_OPTS='-Dpreflight.parity=true -Dpreflight.test.secret=diary-key-test-secret'
  export LOADER_MAIN=untrusted.test.Main
  : > "${TEST_DIR}/calls"
  rm -f "${TEST_DIR}/verify.args" "${TEST_DIR}/up.args" "${TEST_DIR}/override.path"
  cat > "${HARUDLE_APP_DIR}/.env" <<ENV
DEPLOY_ENV=${environment}
S3_BUCKET=techcourse-project-2026
S3_GENERATED_PREFIX=harudle/generated/diary-images/${environment}
S3_REFERENCE_PREFIX=harudle/references/generation/${environment}
DIARY_SOURCE_TEXT_KEY_BASE64=diary-key-test-secret
DIARY_SOURCE_TEXT_NEW_WRITES_ENABLED=true
ENV
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.prod.yaml"
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.dev.yaml"
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.diary-reader.yaml"
  printf 'services: {}\n' > "${HARUDLE_APP_DIR}/compose.diary-writer.yaml"
  printf '%s\n' "${release_mode}" > "${HARUDLE_APP_DIR}/diary-source-text-release-mode"
}

assert_private_output() {
  if grep -Fq 'diary-key-test-secret' "${TEST_DIR}/output"; then
    echo 'FAIL: A key, rendered configuration, or candidate diagnostic appeared in output.' >&2
    exit 1
  fi
}

assert_override_removed() {
  [[ ! -f "$(cat "${TEST_DIR}/override.path")" ]]
}

record_pass() {
  test_count=$((test_count + 1))
  printf 'PASS: %s\n' "$1"
}

for environment in dev prod; do
  prepare_case "${environment}" reader
  bash -x "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1
  printf 'render\nquiet\nverify\nup\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  grep -Fxq -- '--no-deps' "${TEST_DIR}/verify.args"
  grep -Fxq -- '-T' "${TEST_DIR}/verify.args"
  grep -Fxq -- '--entrypoint' "${TEST_DIR}/verify.args"
  grep -Fxq -- 'sh' "${TEST_DIR}/verify.args"
  grep -Fxq -- '--env' "${TEST_DIR}/verify.args"
  grep -Fxq -- 'LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain' \
    "${TEST_DIR}/verify.args"
  grep -Fq -- '-Dloader.main=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain' \
    "${TEST_DIR}/verify.args"
  grep -Fq -- 'exec java ${JAVA_OPTS:-} -Xmx128m' "${TEST_DIR}/verify.args"
  grep -Fq -- 'org.springframework.boot.loader.launch.PropertiesLauncher verify' "${TEST_DIR}/verify.args"
  grep -Fxq -- '-Dpreflight.parity=true' "${TEST_DIR}/java.args"
  grep -Fxq -- '-Dpreflight.test.secret=diary-key-test-secret' "${TEST_DIR}/java.args"
  grep -Fxq -- '-Xmx128m' "${TEST_DIR}/java.args"
  grep -Fxq -- 'com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain' \
    "${TEST_DIR}/java.loader-main"
  grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-reader.yaml" "${TEST_DIR}/verify.args"
  grep -Fq 'logging: !override' "${TEST_DIR}/override.contents"
  grep -Fq 'driver: none' "${TEST_DIR}/override.contents"
  if grep -Eq 'awslogs|options:' "${TEST_DIR}/override.contents" ||
    grep -Eq -- '--service-ports|--publish|diary-key-test-secret' "${TEST_DIR}/verify.args"; then
    echo 'FAIL: The verifier could publish ports, inherit awslogs, or pass a key as an argument.' >&2
    exit 1
  fi
  if [[ "${environment}" == dev ]]; then
    grep -Fxq -- "${HARUDLE_APP_DIR}/compose.dev.yaml" "${TEST_DIR}/verify.args"
  fi
  assert_private_output
  assert_override_removed
  record_pass "${environment}: verify before replacement without diagnostic or trace leaks"
done

for failure in 1 7; do
  prepare_case dev reader
  export DIARY_KEY_TEST_VERIFY_FAILURE="${failure}"
  if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
    echo 'FAIL: Failed key verification was accepted.' >&2
    exit 1
  fi
  printf 'render\nquiet\nverify\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  [[ ! -f "${TEST_DIR}/up.args" ]]
  grep -Fq 'Existing containers were not replaced.' "${TEST_DIR}/output"
  assert_private_output
  assert_override_removed
  record_pass "verifier exit ${failure}: existing containers remain untouched"
done

prepare_case prod writer
bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1
grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/verify.args"
grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/up.args"
assert_private_output
assert_override_removed
record_pass 'writer revision: verifier and deployment use the same mode override'

for failure_point in render quiet; do
  prepare_case dev
  case "${failure_point}" in
    render) export DIARY_KEY_TEST_RENDER_FAILURE=1 ;;
    quiet) export DIARY_KEY_TEST_QUIET_FAILURE=1 ;;
  esac
  if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
    echo 'FAIL: Invalid Compose configuration was accepted.' >&2
    exit 1
  fi
  [[ ! -f "${TEST_DIR}/verify.args" && ! -f "${TEST_DIR}/up.args" ]]
  assert_private_output
  record_pass "${failure_point}: configuration failure never verifies or replaces containers"
done

prepare_case dev
export DIARY_KEY_TEST_UP_FAILURE=1
if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: A failed container replacement was accepted.' >&2
  exit 1
fi
grep -Fq 'Cannot start the Compose deployment.' "${TEST_DIR}/output"
assert_private_output
record_pass 'replacement failure: fixed error without candidate diagnostics'

printf 'Diary key deployment verification: %s tests passed.\n' "${test_count}"
