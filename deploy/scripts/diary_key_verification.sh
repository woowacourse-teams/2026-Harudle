#!/usr/bin/env bash

# Run the candidate's real key verifier before replacing the serving containers.
# A subshell keeps the temporary-file cleanup and tracing settings local to this check.
verify_diary_source_text_keys() (
  set -Eeuo pipefail
  set +x

  local verification_override verification_output verification_status=0
  if ! verification_override="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-key-check.XXXXXX" 2>/dev/null)"; then
    echo "일기 암호화 키 검증을 준비하지 못했습니다." >&2
    return 1
  fi
  trap 'rm -f -- "${verification_override}" "${verification_output:-}"' EXIT
  if ! verification_output="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-key-result.XXXXXX" 2>/dev/null)"; then
    echo "일기 암호화 키 검증을 준비하지 못했습니다." >&2
    return 1
  fi

  # Never inherit awslogs for this one-off process, including early JVM/bootstrap failures.
  cat > "${verification_override}" <<'YAML'
services:
  backend:
    logging: !override
      driver: none
YAML

  docker compose "${COMPOSE_ARGS[@]}" --file "${verification_override}" \
    run --rm --no-deps -T --entrypoint sh \
    --env LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain \
    backend -c \
    'exec java ${JAVA_OPTS:-} -Xmx128m -Xlog:disable -Dloader.main=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher verify' \
    >"${verification_output}" 2>/dev/null || verification_status=$?

  # Exit zero alone also accepts JVM options such as -version that never invoke the verifier.
  if (( verification_status != 0 )) ||
    ! printf '%s\n' '{"verified":true}' | cmp -s - "${verification_output}"; then
    echo "일기 암호화 키 검증에 실패했습니다. 기존 컨테이너는 교체하지 않았습니다." >&2
    return 1
  fi

  echo "일기 암호화 키 검증을 통과했습니다."
)
