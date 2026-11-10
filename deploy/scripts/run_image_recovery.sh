#!/usr/bin/env bash
# Manual, local-only maintenance process. Not called by CodeDeploy or normal startup.
set -Eeuo pipefail
set +x

if (( $# != 2 )); then
  echo "사용법: $0 <검토한 백엔드 이미지 ID 또는 digest> <환경 파일 절대 경로>" >&2
  exit 2
fi
image="$1"
env_file="$2"
if [[ ! "${image}" =~ ^sha256:[a-f0-9]{64}$ && ! "${image}" =~ ^[a-zA-Z0-9._:/-]+@sha256:[a-f0-9]{64}$ ]]; then
  echo "변경 가능한 태그 대신 검토한 이미지 ID 또는 digest를 지정하세요." >&2
  exit 2
fi
if [[ "${env_file}" != /* || ! -f "${env_file}" ]]; then
  echo "읽을 수 있는 환경 파일 절대 경로가 필요합니다." >&2
  exit 2
fi
if [[ "$(stat -c '%a' "${env_file}")" != "600" ]]; then
  echo "환경 파일 권한은 600이어야 합니다." >&2
  exit 2
fi
if ! running="$(docker ps -q --filter label=com.docker.compose.project=harudle --filter label=com.docker.compose.service=backend)"; then
  echo "정상 백엔드 중지 여부를 확인하지 못했습니다." >&2
  exit 1
fi
if [[ -n "${running}" ]]; then
  echo "정상 백엔드를 먼저 중지하고 진행 중인 생성 작업의 종료를 확인하세요." >&2
  exit 1
fi

arguments=(--rm --name harudle-image-recovery)
if [[ -n "${HARUDLE_RECOVERY_GCP_FILE:-}" ]]; then
  if [[ "${HARUDLE_RECOVERY_GCP_FILE}" != /* || ! -f "${HARUDLE_RECOVERY_GCP_FILE}" ]]; then
    echo "GCP 인증 파일의 올바른 절대 경로가 필요합니다." >&2
    exit 2
  fi
  arguments+=(--mount "type=bind,source=${HARUDLE_RECOVERY_GCP_FILE},target=/var/run/harudle/gcp-wif.json,readonly")
fi

exec docker run "${arguments[@]}" \
  --network "${HARUDLE_RECOVERY_NETWORK:-harudle_app}" \
  --publish 127.0.0.1:18080:8080 \
  --env-file "${env_file}" --log-driver none \
  --env JAVA_TOOL_OPTIONS= --env JDK_JAVA_OPTIONS= --env _JAVA_OPTIONS= \
  --env SPRING_PROFILES_ACTIVE=prod,image-recovery \
  --env SPRING_JPA_HIBERNATE_DDL_AUTO=validate --env SPRING_JPA_GENERATE_DDL=false \
  --env SPRING_FLYWAY_ENABLED=false --env SPRING_SQL_INIT_MODE=never \
  --env SERVER_PORT=8080 --env MANAGEMENT_SERVER_PORT=8081 \
  --env HARUDLE_IMAGE_RECOVERY_AI_ENABLED="${HARUDLE_IMAGE_RECOVERY_AI_ENABLED:-false}" \
  --entrypoint java "${image}" -Xmx768m \
  -Dloader.main=com.harudle.recovery.ImageRecoveryApplication \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
