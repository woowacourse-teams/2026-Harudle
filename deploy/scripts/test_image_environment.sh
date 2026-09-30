#!/usr/bin/env bash
set -Eeuo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/compose_environment.sh"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
printf 'services: {}\n' > "${TEST_DIR}/compose.dev.yaml"

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
  local setting="$1"
  if (configure_compose "${TEST_DIR}") > "${TEST_DIR}/output" 2>&1; then
    echo "FAIL: ${setting} 오류를 허용했습니다." >&2
    exit 1
  fi
  grep -Fq "${setting}" "${TEST_DIR}/output"
  printf 'PASS: %s 오류 거절\n' "${setting}"
}

# 1. 정상적인 dev와 prod 설정을 허용한다.
for environment in dev prod; do
  export DEPLOYMENT_GROUP_NAME="harudle-${environment}"
  write_settings "${environment}"
  (configure_compose "${TEST_DIR}"; [[ "${DEPLOY_ENV_VALUE}" == "${environment}" ]])
  printf 'PASS: %s 정상 설정\n' "${environment}"
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

printf 'Image environment: 9 tests passed.\n'
