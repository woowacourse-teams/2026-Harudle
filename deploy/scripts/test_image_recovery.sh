#!/usr/bin/env bash
set -Eeuo pipefail
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
export RECOVERY_TEST_DIR="${TEST_DIR}"
mkdir -p "${TEST_DIR}/bin"
touch "${TEST_DIR}/test.env"
chmod 600 "${TEST_DIR}/test.env"
export PATH="${TEST_DIR}/bin:${PATH}"
cat > "${TEST_DIR}/bin/stat" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "${RECOVERY_TEST_MODE:-600}"
SH
cat > "${TEST_DIR}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ps ]]; then
  [[ "${RECOVERY_TEST_DOCKER_FAILURE:-false}" == false ]] || exit 1
  printf '%s' "${RECOVERY_TEST_BACKEND:-}"
else
  printf '%s\n' "$@" > "${RECOVERY_TEST_DIR}/run.args"
fi
SH
chmod +x "${TEST_DIR}/bin/"*
image="sha256:$(printf 'a%.0s' {1..64})"

if RECOVERY_TEST_BACKEND=existing bash "${SCRIPT_DIR}/run_image_recovery.sh" "${image}" "${TEST_DIR}/test.env" > /dev/null 2>&1; then
  echo '실행 중인 정상 백엔드를 거절해야 합니다.' >&2; exit 1
fi
[[ ! -f "${TEST_DIR}/run.args" ]]
if RECOVERY_TEST_DOCKER_FAILURE=true bash "${SCRIPT_DIR}/run_image_recovery.sh" "${image}" "${TEST_DIR}/test.env" > /dev/null 2>&1; then
  echo '정상 백엔드 상태를 확인하지 못하면 중단해야 합니다.' >&2; exit 1
fi
[[ ! -f "${TEST_DIR}/run.args" ]]
if RECOVERY_TEST_MODE=644 bash "${SCRIPT_DIR}/run_image_recovery.sh" "${image}" "${TEST_DIR}/test.env" > /dev/null 2>&1; then
  echo '안전하지 않은 환경 파일 권한을 거절해야 합니다.' >&2; exit 1
fi
if bash "${SCRIPT_DIR}/run_image_recovery.sh" latest "${TEST_DIR}/test.env" > /dev/null 2>&1; then
  echo '변경 가능한 이미지 태그를 거절해야 합니다.' >&2; exit 1
fi
bash "${SCRIPT_DIR}/run_image_recovery.sh" "${image}" "${TEST_DIR}/test.env"
for required in '127.0.0.1:18080:8080' 'SPRING_PROFILES_ACTIVE=prod,image-recovery' \
  'SPRING_JPA_HIBERNATE_DDL_AUTO=validate' 'SPRING_JPA_GENERATE_DDL=false' \
  'SPRING_FLYWAY_ENABLED=false' 'SPRING_SQL_INIT_MODE=never' 'JAVA_TOOL_OPTIONS=' \
  'JDK_JAVA_OPTIONS=' '_JAVA_OPTIONS=' 'HARUDLE_IMAGE_RECOVERY_AI_ENABLED=false' \
  '-Dloader.main=com.harudle.recovery.ImageRecoveryApplication' 'org.springframework.boot.loader.launch.PropertiesLauncher'; do
  grep -Fqx -- "${required}" "${TEST_DIR}/run.args"
done
if grep -Eq '(^up$|^--force-recreate$|^--restart$|^privileged$)' "${TEST_DIR}/run.args"; then
  echo '일반 컨테이너를 교체하거나 재시작하면 안 됩니다.' >&2; exit 1
fi
echo 'Image recovery launcher checks passed.'
