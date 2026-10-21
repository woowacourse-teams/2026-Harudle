#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
export HARUDLE_APP_DIR="${TEST_DIR}/app"
export DIARY_KEY_TEST_DIR="${TEST_DIR}"
export TMPDIR="${TEST_DIR}/tmp"
mkdir -p "${HARUDLE_APP_DIR}" "${TEST_DIR}/bin" "${TMPDIR}"
test_count=0
source "${SCRIPT_DIR}/diary_writer_approval.sh"

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
    for ((index = 0; index < ${#arguments[@]}; index++)); do
      if [[ "${arguments[index]}" == backend ]]; then
        backend_index="${index}"
        break
      fi
    done
    operation="${arguments[backend_index + 4]}"
    echo "${operation}" >> "${DIARY_KEY_TEST_DIR}/calls"
    printf '%s\n' "$@" > "${DIARY_KEY_TEST_DIR}/${operation}.args"
    printf '%s\n' "${last_file}" >> "${DIARY_KEY_TEST_DIR}/override.paths"
    cp "${last_file}" "${DIARY_KEY_TEST_DIR}/override.contents"
    echo 'diary-key-test-secret' >&2
    failure="${DIARY_KEY_TEST_VERIFY_FAILURE:-0}"
    [[ "${operation}" == verify ]] || failure="${DIARY_KEY_TEST_READY_FAILURE:-0}"
    if [[ "${failure}" == 0 ]]; then
      for ((index = 0; index < ${#arguments[@]}; index++)); do
        if [[ "${arguments[index]}" == --env ]]; then
          export "${arguments[index + 1]}"
        fi
      done
      sh "${arguments[@]:backend_index + 1}"
    else
      echo 'diary-key-test-secret'
    fi
    exit "${failure}"
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
operation="${!#}"
printf '%s\n' "$@" > "${DIARY_KEY_TEST_DIR}/${operation}.java.args"
printf '%s\n' "${LOADER_MAIN:-}" > "${DIARY_KEY_TEST_DIR}/${operation}.java.loader-main"
# These JVM actions exit successfully before invoking a main method.
for argument in "$@"; do
  case "${argument}" in
    -version) echo 'diary-key-test-secret' >&2; exit 0 ;;
    --dry-run) exit 0 ;;
  esac
done
# The dev Compose options write GC diagnostics to stdout unless the one-off disables them.
if [[ " $* " == *' -Xlog:gc*,safepoint:stdout:'* && " $* " != *' -Xlog:disable '* ]]; then
  echo 'diary-key-test-secret'
fi
if [[ "${operation}" == ready ]]; then
  case "${DIARY_KEY_TEST_READY_OPTION:-}" in
    -version|--dry-run) exit 0 ;;
  esac
  result='{"command":"ready","legacyRows":0,"encryptedRows":12,"unsupportedRows":0,"processingLegacyRows":0,"orphanRows":1,"finalized":true}'
  case "${DIARY_KEY_TEST_READY_RESULT:-valid}" in
    valid) printf '%s\n' "${result}" ;;
    empty) ;;
    diagnostic) printf '%s\n' 'diary-key-test-secret' "${result}" ;;
    extra-field) jq -c '.extra="diary-key-test-secret"' <<<"${result}" ;;
    wrong-command) jq -c '.command="status"' <<<"${result}" ;;
    not-finalized) jq -c '.finalized=false' <<<"${result}" ;;
    legacy) jq -c '.legacyRows=1' <<<"${result}" ;;
    unsupported) jq -c '.unsupportedRows=1' <<<"${result}" ;;
    processing) jq -c '.processingLegacyRows=1' <<<"${result}" ;;
    negative) jq -c '.encryptedRows=-1' <<<"${result}" ;;
    string-count) jq -c '.encryptedRows="12"' <<<"${result}" ;;
    fractional-count) jq -c '.orphanRows=0.5' <<<"${result}" ;;
    duplicate) printf '%s\n' "${result}" "${result}" ;;
    duplicate-field) printf '%s\n' "${result/\"legacyRows\":0/\"legacyRows\":1,\"legacyRows\":0}" ;;
    no-newline) printf '%s' "${result}" ;;
    extra-newline) printf '%s\n\n' "${result}" ;;
    *) exit 1 ;;
  esac
  exit "${DIARY_KEY_TEST_READY_JAVA_FAILURE:-0}"
fi
case "${DIARY_KEY_TEST_RESULT:-valid}" in
  valid) printf '%s\n' '{"verified":true}' ;;
  empty) ;;
  diagnostic) printf '%s\n' 'diary-key-test-secret' '{"verified":true}' ;;
  extra-field) printf '%s\n' '{"verified":true,"extra":"diary-key-test-secret"}' ;;
  false) printf '%s\n' '{"verified":false}' ;;
  duplicate) printf '%s\n' '{"verified":true}' '{"verified":true}' ;;
  no-newline) printf '%s' '{"verified":true}' ;;
  extra-newline) printf '%s\n\n' '{"verified":true}' ;;
  *) exit 1 ;;
esac
if [[ "${DIARY_KEY_TEST_CHANGE_CANARY:-0}" == 1 ]]; then
  printf 'changed-public-canary\n' > "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
fi
exit "${DIARY_KEY_TEST_JAVA_FAILURE:-0}"
SH
chmod +x "${TEST_DIR}/bin/docker" "${TEST_DIR}/bin/java"
export PATH="${TEST_DIR}/bin:${PATH}"

prepare_case() {
  local environment="$1" release_mode="${2:-reader}"
  export DIARY_KEY_TEST_ENV="${environment}"
  export DEPLOYMENT_GROUP_NAME="harudle-${environment}"
  export DIARY_KEY_TEST_RENDER_FAILURE=0 DIARY_KEY_TEST_QUIET_FAILURE=0
  export DIARY_KEY_TEST_VERIFY_FAILURE=0 DIARY_KEY_TEST_READY_FAILURE=0 DIARY_KEY_TEST_UP_FAILURE=0
  export DIARY_KEY_TEST_RESULT=valid DIARY_KEY_TEST_JAVA_FAILURE=0 DIARY_KEY_TEST_CHANGE_CANARY=0
  export DIARY_KEY_TEST_READY_RESULT=valid DIARY_KEY_TEST_READY_JAVA_FAILURE=0 DIARY_KEY_TEST_READY_OPTION=''
  export JAVA_OPTS='-Dpreflight.parity=true -Dpreflight.test.secret=diary-key-test-secret'
  export LOADER_MAIN=untrusted.test.Main
  : > "${TEST_DIR}/calls"
  rm -f "${TEST_DIR}/verify.args" "${TEST_DIR}/up.args" "${TEST_DIR}/override.paths"
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
  export HARUDLE_DIARY_WRITER_APPROVAL_FILE="${TEST_DIR}/approval.json"
  export HARUDLE_DIARY_KEY_VERIFICATION_FILE="${TEST_DIR}/canary.json"
  printf 'public-canary-fixture\n' > "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
  chmod 640 "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
  jq -n --arg digest "$(diary_approval_sha256 "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}")" '{
    formatVersion: 1, environment: "prod", canarySha256: $digest,
    devRestoreVerified: true, devRestoreRecord: "dev-restore-fixture",
    prodBackupVerified: true, prodBackupRecord: "prod-backup-fixture",
    readerRevision: "immutable-artifact-fixture", approvedAt: "2026-01-01T00:00:00Z", approvedBy: "fixture"
  }' > "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
  chmod 600 "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
}

assert_private_output() {
  if grep -Fq 'diary-key-test-secret' "${TEST_DIR}/output"; then
    echo 'FAIL: A key, rendered configuration, or candidate diagnostic appeared in output.' >&2
    exit 1
  fi
}

assert_override_removed() {
  while IFS= read -r path; do
    [[ ! -f "${path}" ]]
  done < "${TEST_DIR}/override.paths"
  if compgen -G "${TMPDIR}/harudle-diary-*" >/dev/null; then
    echo 'FAIL: 비공개 키 검증 임시 파일이 정리되지 않았습니다.' >&2
    exit 1
  fi
}

record_pass() {
  test_count=$((test_count + 1))
  printf 'PASS: %s\n' "$1"
}

for environment in dev prod; do
  prepare_case "${environment}" reader
  bash -x "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1
  printf 'render\nquiet\nverify\nready\nup\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  grep -Fxq -- '--no-deps' "${TEST_DIR}/verify.args"
  grep -Fxq -- '-T' "${TEST_DIR}/verify.args"
  grep -Fxq -- '--entrypoint' "${TEST_DIR}/verify.args"
  grep -Fxq -- 'sh' "${TEST_DIR}/verify.args"
  grep -Fxq -- '--env' "${TEST_DIR}/verify.args"
  grep -Fxq -- 'LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain' \
    "${TEST_DIR}/verify.args"
  grep -Fq -- '-Dloader.main="$LOADER_MAIN"' \
    "${TEST_DIR}/verify.args"
  grep -Fq -- 'exec java ${JAVA_OPTS:-} -Xmx128m' "${TEST_DIR}/verify.args"
  grep -Fq -- 'org.springframework.boot.loader.launch.PropertiesLauncher "$@"' "${TEST_DIR}/verify.args"
  grep -Fxq -- '-Dpreflight.parity=true' "${TEST_DIR}/verify.java.args"
  grep -Fxq -- '-Dpreflight.test.secret=diary-key-test-secret' "${TEST_DIR}/verify.java.args"
  grep -Fxq -- '-Xmx128m' "${TEST_DIR}/verify.java.args"
  grep -Fxq -- '-Xlog:disable' "${TEST_DIR}/verify.java.args"
  grep -Fxq -- 'com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain' \
    "${TEST_DIR}/verify.java.loader-main"
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
  grep -Fxq -- 'LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain' "${TEST_DIR}/ready.args"
  grep -Fxq -- 'com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain' "${TEST_DIR}/ready.java.loader-main"
  grep -Fxq -- '-Dpreflight.parity=true' "${TEST_DIR}/ready.java.args"
  grep -Fxq -- '-Xlog:disable' "${TEST_DIR}/ready.java.args"
  grep -Fxq -- 'ready' "${TEST_DIR}/ready.java.args"
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
  grep -Fq '기존 컨테이너는 교체하지 않았습니다.' "${TEST_DIR}/output"
  assert_private_output
  assert_override_removed
  record_pass "verifier exit ${failure}: existing containers remain untouched"
done

assert_verifier_rejected() {
  if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
    echo 'FAIL: 실제 검증 성공 증거가 없는 후보를 허용했습니다.' >&2
    exit 1
  fi
  printf 'render\nquiet\nverify\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  [[ ! -f "${TEST_DIR}/up.args" ]]
  grep -Fq '기존 컨테이너는 교체하지 않았습니다.' "${TEST_DIR}/output"
  assert_private_output
  assert_override_removed
  record_pass "$1"
}

for option in -version --dry-run; do
  prepare_case dev reader
  export JAVA_OPTS="${JAVA_OPTS} ${option}"
  assert_verifier_rejected "${option}: Main을 실행하지 않은 JVM의 종료 0을 거절"
done

for result in empty diagnostic extra-field false duplicate no-newline extra-newline; do
  prepare_case dev reader
  export DIARY_KEY_TEST_RESULT="${result}"
  assert_verifier_rejected "${result}: 정확한 한 줄 성공 JSON만 허용"
done

prepare_case dev reader
export DIARY_KEY_TEST_JAVA_FAILURE=7
assert_verifier_rejected '성공 JSON이 있어도 비정상 종료 코드를 거절'

prepare_case dev reader
export JAVA_OPTS="${JAVA_OPTS} -Xlog:gc*,safepoint:stdout:time,uptime,level,tags"
bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1
grep -Fxq -- '-Xlog:gc*,safepoint:stdout:time,uptime,level,tags' "${TEST_DIR}/verify.java.args"
grep -Fxq -- '-Xlog:disable' "${TEST_DIR}/verify.java.args"
[[ -f "${TEST_DIR}/up.args" ]]
assert_private_output
assert_override_removed
record_pass 'dev GC 옵션과 시스템 프로퍼티를 유지하고 one-off JVM 로그만 억제'

assert_ready_rejected() {
  if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
    echo 'FAIL: 실제 전환 완료 증거가 없는 후보를 허용했습니다.' >&2
    exit 1
  fi
  printf 'render\nquiet\nverify\nready\n' > "${TEST_DIR}/expected.calls"
  diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
  [[ ! -f "${TEST_DIR}/up.args" ]]
  grep -Fq '기존 컨테이너는 교체하지 않았습니다.' "${TEST_DIR}/output"
  assert_private_output
  assert_override_removed
  record_pass "$1"
}

for failure in 1 2; do
  prepare_case dev reader
  export DIARY_KEY_TEST_READY_FAILURE="${failure}"
  assert_ready_rejected "ready exit ${failure}: 기존 컨테이너 유지"
done
for option in -version --dry-run; do
  prepare_case dev reader
  export DIARY_KEY_TEST_READY_OPTION="${option}"
  assert_ready_rejected "ready ${option}: Main 미실행 종료 0 거절"
done
for result in empty diagnostic extra-field wrong-command not-finalized legacy unsupported processing \
  negative string-count fractional-count duplicate duplicate-field no-newline extra-newline; do
  prepare_case dev reader
  export DIARY_KEY_TEST_READY_RESULT="${result}"
  assert_ready_rejected "ready ${result}: 단일 완료 JSON과 올바른 집계만 허용"
done
prepare_case dev reader
export DIARY_KEY_TEST_READY_JAVA_FAILURE=7
assert_ready_rejected 'ready 성공 JSON이 있어도 비정상 종료 거절'

prepare_case prod writer
bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1
grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/verify.args"
grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/ready.args"
grep -Fxq -- "${HARUDLE_APP_DIR}/compose.diary-writer.yaml" "${TEST_DIR}/up.args"
assert_private_output
assert_override_removed
record_pass 'writer revision: verifier and deployment use the same mode override'

prepare_case prod writer
rm "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: 운영 writer 승인 없이 교체를 허용했습니다.' >&2
  exit 1
fi
printf 'render\nquiet\n' > "${TEST_DIR}/expected.calls"
diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
[[ ! -f "${TEST_DIR}/verify.args" && ! -f "${TEST_DIR}/up.args" ]]
assert_private_output
record_pass 'prod writer 승인 누락은 키 검증과 컨테이너 교체 전에 거절'

prepare_case prod writer
export DIARY_KEY_TEST_CHANGE_CANARY=1
if bash "${SCRIPT_DIR}/application_start.sh" > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: 키 검증 중 변경된 canary의 과거 승인을 허용했습니다.' >&2
  exit 1
fi
printf 'render\nquiet\nverify\n' > "${TEST_DIR}/expected.calls"
diff -u "${TEST_DIR}/expected.calls" "${TEST_DIR}/calls"
[[ ! -f "${TEST_DIR}/up.args" ]]
assert_private_output
assert_override_removed
record_pass '키 검증 뒤 canary 승인 재확인 실패는 교체를 막음'

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
