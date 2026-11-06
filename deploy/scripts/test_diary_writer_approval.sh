#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/diary_writer_approval.sh"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TEST_DIR}"' EXIT
export HARUDLE_DIARY_WRITER_APPROVAL_FILE="${TEST_DIR}/approval.json"
export HARUDLE_DIARY_KEY_VERIFICATION_FILE="${TEST_DIR}/canary.json"
test_count=0

record_pass() {
  test_count=$((test_count + 1))
  printf 'PASS: %s\n' "$1"
}

prepare_case() {
  export DEPLOY_ENV_VALUE=prod DIARY_SOURCE_TEXT_RELEASE_MODE_VALUE=writer
  rm -f -- "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
  printf 'public-canary-approval-test-secret\n' > "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
  chmod 640 "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
  jq -cn --arg digest "$(diary_approval_sha256 "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}")" '{
    formatVersion: 1, environment: "prod", canarySha256: $digest,
    devRestoreVerified: true, devRestoreRecord: "dev-approval-test-secret",
    prodBackupVerified: true, prodBackupRecord: "prod-approval-test-secret",
    readerRevision: "immutable-approval-test-secret", approvedAt: "2026-01-01T00:00:00Z",
    approvedBy: "승인자-approval-test-secret"
  }' > "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
  chmod 600 "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
}

change_approval() {
  jq -c "$1" "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" > "${TEST_DIR}/changed.json"
  mv "${TEST_DIR}/changed.json" "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
  chmod 600 "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
}

expect_rejected() {
  if verify_diary_writer_approval > "${TEST_DIR}/output" 2>&1; then
    echo 'FAIL: 유효하지 않은 운영 writer 승인을 허용했습니다.' >&2
    exit 1
  fi
  printf '%s\n' '운영 writer 승인을 확인하지 못했습니다. 복원 검증 기록을 확인하고 기존 컨테이너를 유지하세요.' \
    > "${TEST_DIR}/expected"
  cmp "${TEST_DIR}/expected" "${TEST_DIR}/output"
  record_pass "$1"
}

prepare_case
verify_diary_writer_approval > "${TEST_DIR}/output" 2>&1
[[ ! -s "${TEST_DIR}/output" ]]
record_pass 'prod writer는 현재 canary에 묶인 EUID 소유 600 승인만 허용'
chmod 600 "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
verify_diary_writer_approval
record_pass 'canary의 더 엄격한 600 권한도 허용'

for environment in dev prod; do
  for mode in reader writer; do
    [[ "${environment}:${mode}" == prod:writer ]] && continue
    export DEPLOY_ENV_VALUE="${environment}" DIARY_SOURCE_TEXT_RELEASE_MODE_VALUE="${mode}"
    rm -f -- "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
    verify_diary_writer_approval > "${TEST_DIR}/output" 2>&1
    [[ ! -s "${TEST_DIR}/output" ]]
    record_pass "${environment} ${mode}는 prod writer 승인 대상에서 제외"
  done
done

for field in devRestoreVerified prodBackupVerified; do
  prepare_case
  change_approval ".${field} = false"
  expect_rejected "${field} 미완료를 거절"
done
for expression in \
  '.formatVersion = 2' '.environment = "dev"' '.canarySha256 = "incorrect"' \
  '.devRestoreVerified = "true"' '.devRestoreRecord = "  "' '.prodBackupRecord = null' \
  '.readerRevision = ""' '.approvedBy = "\t"' '.readerRevision = "first\nsecond"' \
  '.devRestoreRecord = "first\nsecond"' '.prodBackupRecord = "first\rsecond"' \
  '.approvedBy = "first\nsecond"' '.readerRevision = ("a" * 1025)' \
  '.approvedAt = "2026-02-30T00:00:00Z"' '.approvedAt = "2026-01-01T09:00:00+09:00"' \
  '.approvedAt = null' '.extra = "approval-test-secret"' 'del(.prodBackupRecord)'; do
  prepare_case
  change_approval "${expression}"
  expect_rejected '스키마·참조·UTC 시각·필수 필드 오류를 고정 진단으로 거절'
done

prepare_case
change_approval '.approvedAt = "9999-01-01T00:00:00Z"'
expect_rejected '형식이 올바른 UTC 시각이어도 미래 승인을 거절'

for kind in approval canary; do
  prepare_case
  if [[ "${kind}" == approval ]]; then file="${HARUDLE_DIARY_WRITER_APPROVAL_FILE}";
  else file="${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"; fi
  rm "${file}"
  expect_rejected "${kind} 파일 누락 거절"
  prepare_case
  if [[ "${kind}" == approval ]]; then file="${HARUDLE_DIARY_WRITER_APPROVAL_FILE}";
  else file="${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"; fi
  mv "${file}" "${TEST_DIR}/symlink-target"
  ln -s "${TEST_DIR}/symlink-target" "${file}"
  expect_rejected "${kind} 심링크 거절"
  prepare_case
  if [[ "${kind}" == approval ]]; then file="${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"; chmod 640 "${file}";
  else file="${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"; chmod 644 "${file}"; fi
  expect_rejected "${kind} 공개되거나 쓰기 가능한 권한 거절"
  prepare_case
  if [[ "${kind}" == approval ]]; then file="${HARUDLE_DIARY_WRITER_APPROVAL_FILE}";
  else file="${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"; fi
  : > "${file}"
  expect_rejected "${kind} 빈 파일 거절"
done

prepare_case
head -c 16385 /dev/zero > "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
expect_rejected '16KiB를 넘는 승인 파일 거절'
prepare_case
head -c 65537 /dev/zero > "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
expect_rejected '64KiB를 넘는 canary 거절'

prepare_case
printf 'changed-public-canary\n' > "${HARUDLE_DIARY_KEY_VERIFICATION_FILE}"
expect_rejected 'canary 교체 뒤 과거 승인 무효화'
prepare_case
sed 's/"formatVersion":1/"formatVersion":0,"formatVersion":1/' \
  "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" > "${TEST_DIR}/duplicate.json"
mv "${TEST_DIR}/duplicate.json" "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
chmod 600 "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
expect_rejected '마지막 값만 맞는 중복 JSON 필드도 거절'
prepare_case
cat "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}" > "${TEST_DIR}/multiple.json"
mv "${TEST_DIR}/multiple.json" "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
chmod 600 "${HARUDLE_DIARY_WRITER_APPROVAL_FILE}"
expect_rejected '여러 JSON 승인 문서 거절'

prepare_case
if (stat() { printf '%s:600:10\n' "$((EUID + 1))"; }; verify_diary_writer_approval) \
  > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: 다른 UID 소유의 승인 파일을 허용했습니다.' >&2
  exit 1
fi
printf '%s\n' '운영 writer 승인을 확인하지 못했습니다. 복원 검증 기록을 확인하고 기존 컨테이너를 유지하세요.' \
  > "${TEST_DIR}/expected"
cmp "${TEST_DIR}/expected" "${TEST_DIR}/output"
record_pass '실행 EUID가 아닌 파일 소유자 거절'

printf '운영 writer 승인: %s개 테스트 통과.\n' "${test_count}"
