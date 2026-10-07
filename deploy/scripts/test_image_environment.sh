#!/usr/bin/env bash
set -Eeuo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/compose_environment.sh"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
test_count=0

record_pass() {
  test_count=$((test_count + 1))
  printf 'PASS: %s\n' "$1"
}

write_compose() {
  cat > "${TEST_DIR}/compose.prod.yaml" <<'YAML'
services:
  backend:
    image: busybox:latest
    env_file:
      - .env
YAML
  printf 'services: {}\n' > "${TEST_DIR}/compose.dev.yaml"
}

override_setting() {
  write_compose
  cat >> "${TEST_DIR}/compose.prod.yaml" <<YAML
    environment:
      $1: "$2"
YAML
}

write_settings() {
  local environment="$1"
  cat > "${TEST_DIR}/.env" <<ENV
DEPLOY_ENV="${environment}"
S3_BUCKET=techcourse-project-2026
S3_GENERATED_PREFIX=harudle/generated/diary-images/${environment}
S3_REFERENCE_PREFIX=harudle/references/generation/${environment}
ENV
}

change_setting() {
  sed "s|^$1=.*|$1=$2|" "${TEST_DIR}/.env" > "${TEST_DIR}/changed.env"
  mv "${TEST_DIR}/changed.env" "${TEST_DIR}/.env"
}

expect_rejected() {
  local setting="$1" label="${2:-$1 오류 거절}"
  if (configure_compose "${TEST_DIR}") > "${TEST_DIR}/output" 2>&1; then
    echo "FAIL: ${setting} 오류를 허용했습니다." >&2
    exit 1
  fi
  grep -Fq "${setting}" "${TEST_DIR}/output"
  record_pass "${label}"
}

write_compose

# 1. 정상적인 dev와 prod 설정을 허용한다.
for environment in dev prod; do
  export DEPLOYMENT_GROUP_NAME="harudle-${environment}"
  write_settings "${environment}"
  (configure_compose "${TEST_DIR}"; [[ "${DEPLOY_ENV_VALUE}" == "${environment}" ]])
  record_pass "${environment} 정상 설정"
done

# 2. 개발 서버에 운영 설정 전체를 넣어도 거절한다.
export DEPLOYMENT_GROUP_NAME=harudle-dev
write_settings prod
expect_rejected DEPLOY_ENV

# 3. 다른 버킷이나 운영 이미지 경로를 사용하면 거절한다.
write_settings dev
change_setting S3_BUCKET other-bucket
expect_rejected S3_BUCKET
write_settings dev
change_setting S3_GENERATED_PREFIX harudle/generated/diary-images/prod
expect_rejected S3_GENERATED_PREFIX
write_settings dev
change_setting S3_REFERENCE_PREFIX harudle/references/generation/prod
expect_rejected S3_REFERENCE_PREFIX

# 4. 필수값이 없거나 중복되면 거절한다.
write_settings dev
change_setting S3_GENERATED_PREFIX ''
expect_rejected S3_GENERATED_PREFIX
write_settings dev
printf 'DEPLOY_ENV=\n' >> "${TEST_DIR}/.env"
expect_rejected DEPLOY_ENV

# 5. 실제 배포 대상이 없으면 운영으로 추측하지 않는다.
write_settings dev
unset DEPLOYMENT_GROUP_NAME
expect_rejected DEPLOYMENT_GROUP_NAME

# 6. 따옴표와 CRLF는 실제 Compose 해석에서도 지원한다.
export DEPLOYMENT_GROUP_NAME=harudle-dev
cat > "${TEST_DIR}/.env" <<'ENV'
DEPLOY_ENV='dev'
S3_BUCKET='techcourse-project-2026'
S3_GENERATED_PREFIX='harudle/generated/diary-images/dev'
S3_REFERENCE_PREFIX='harudle/references/generation/dev'
ENV
(configure_compose "${TEST_DIR}")
record_pass '작은따옴표 정상 설정'
write_settings dev
awk '{ printf "%s\r\n", $0 }' "${TEST_DIR}/.env" > "${TEST_DIR}/windows.env"
mv "${TEST_DIR}/windows.env" "${TEST_DIR}/.env"
(configure_compose "${TEST_DIR}")
record_pass 'CRLF 정상 설정'

write_settings dev
sed '/^S3_GENERATED_PREFIX=/d' "${TEST_DIR}/.env" > "${TEST_DIR}/missing.env"
mv "${TEST_DIR}/missing.env" "${TEST_DIR}/.env"
expect_rejected S3_GENERATED_PREFIX '필수 선언 누락 거절'

# 7. Compose가 인식하는 export/콜론 재정의도 허용하지 않는다.
for syntax in export colon; do
  for setting in DEPLOY_ENV S3_BUCKET S3_GENERATED_PREFIX S3_REFERENCE_PREFIX; do
    write_settings dev
    case "${setting}" in
      DEPLOY_ENV) value=prod ;;
      S3_BUCKET) value=other-bucket ;;
      S3_GENERATED_PREFIX) value=harudle/generated/diary-images/prod ;;
      S3_REFERENCE_PREFIX) value=harudle/references/generation/prod ;;
    esac
    if [[ "${syntax}" == export ]]; then
      printf 'export %s=%s\n' "${setting}" "${value}" >> "${TEST_DIR}/.env"
    else
      printf '%s: %s\n' "${setting}" "${value}" >> "${TEST_DIR}/.env"
    fi
    expect_rejected "Unsupported image environment declaration: ${setting}." "${syntax} ${setting} 재정의 거절"
  done
done
write_settings dev
printf 'S3_GENERATED_PREFIX\n' >> "${TEST_DIR}/.env"
expect_rejected 'Unsupported image environment declaration: S3_GENERATED_PREFIX.' '값 없는 선언 거절'

# 8. .env가 정상이어도 Compose의 최종 backend 환경이 다르면 거절한다.
for setting in DEPLOY_ENV S3_BUCKET S3_GENERATED_PREFIX S3_REFERENCE_PREFIX; do
  write_settings dev
  case "${setting}" in
    DEPLOY_ENV) value=prod ;;
    S3_BUCKET) value=image-env-test-secret ;;
    S3_GENERATED_PREFIX) value=harudle/generated/diary-images/prod ;;
    S3_REFERENCE_PREFIX) value=harudle/references/generation/prod ;;
  esac
  override_setting "${setting}" "${value}"
  expect_rejected "Resolved image environment mismatch: ${setting}." "Compose ${setting} 덮어쓰기 거절"
  if grep -Fq 'image-env-test-secret' "${TEST_DIR}/output"; then
    echo 'FAIL: Compose 환경값을 오류 메시지에 노출했습니다.' >&2
    exit 1
  fi
done

write_settings dev
write_compose
cat > "${TEST_DIR}/compose.dev.yaml" <<'YAML'
services:
  backend:
    environment:
      DEPLOY_ENV: prod
      S3_GENERATED_PREFIX: harudle/generated/diary-images/prod
      S3_REFERENCE_PREFIX: harudle/references/generation/prod
YAML
expect_rejected 'Resolved image environment mismatch: DEPLOY_ENV.' 'dev Compose에서 운영 환경 전체 덮어쓰기 거절'

write_compose
cat > "${TEST_DIR}/override.env" <<'ENV'
DEPLOY_ENV=prod
S3_GENERATED_PREFIX=harudle/generated/diary-images/prod
S3_REFERENCE_PREFIX=harudle/references/generation/prod
ENV
cat > "${TEST_DIR}/compose.dev.yaml" <<'YAML'
services:
  backend:
    env_file:
      - override.env
YAML
expect_rejected 'Resolved image environment mismatch: DEPLOY_ENV.' '추가 env_file의 운영 환경 덮어쓰기 거절'

# 9. Compose 파싱 오류가 환경값이나 설정 원문을 출력하지 않는다.
write_compose
printf 'services: image-env-test-secret\n' > "${TEST_DIR}/compose.prod.yaml"
expect_rejected 'Cannot resolve the Docker Compose configuration.' 'Compose 파싱 오류 거절'
if grep -Fq 'image-env-test-secret' "${TEST_DIR}/output"; then
  echo 'FAIL: Compose 파싱 오류의 원문을 노출했습니다.' >&2
  exit 1
fi

# 10. jq가 없으면 직접 검사와 BeforeInstall 모두 파일 정리 전에 중단한다.
write_compose
mkdir "${TEST_DIR}/no-jq-bin"
cat > "${TEST_DIR}/no-jq-bin/docker" <<'SH'
#!/bin/sh
exit 0
SH
cat > "${TEST_DIR}/no-jq-bin/uname" <<'SH'
#!/bin/sh
printf 'arm64\n'
SH
cat > "${TEST_DIR}/no-jq-bin/install" <<'SH'
#!/bin/sh
echo 'Unexpected deployment file mutation.' >&2
exit 1
SH
chmod +x "${TEST_DIR}/no-jq-bin/docker" "${TEST_DIR}/no-jq-bin/uname" "${TEST_DIR}/no-jq-bin/install"
if (PATH="${TEST_DIR}/no-jq-bin" configure_compose "${TEST_DIR}") > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: jq 없이 환경 검사를 허용했습니다.' >&2
  exit 1
fi
grep -Fq 'jq is not installed.' "${TEST_DIR}/output"
record_pass '직접 환경 검사에서 jq 누락 거절'
if PATH="${TEST_DIR}/no-jq-bin" "${BASH}" "${SCRIPT_DIR}/before_install.sh" > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: jq 없이 BeforeInstall을 허용했습니다.' >&2
  exit 1
fi
grep -Fq 'jq is not installed.' "${TEST_DIR}/output"
record_pass 'BeforeInstall에서 파일 정리 전 jq 누락 거절'

printf 'Image environment: %s tests passed.\n' "${test_count}"
