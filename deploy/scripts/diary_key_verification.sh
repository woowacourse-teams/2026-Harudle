#!/usr/bin/env bash

# Run the candidate's real key verifier before replacing the serving containers.
# A subshell keeps the temporary-file cleanup and tracing settings local to this check.
verify_diary_source_text_keys() (
  set -Eeuo pipefail
  set +x

  local verification_override
  if ! verification_override="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-key-check.XXXXXX" 2>/dev/null)"; then
    echo "Cannot prepare diary encryption key verification." >&2
    return 1
  fi
  trap 'rm -f -- "${verification_override}"' EXIT

  # Never inherit awslogs for this one-off process, including early JVM/bootstrap failures.
  cat > "${verification_override}" <<'YAML'
services:
  backend:
    logging: !override
      driver: none
YAML

  if ! docker compose "${COMPOSE_ARGS[@]}" --file "${verification_override}" \
    run --rm --no-deps -T --entrypoint sh \
    --env LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain \
    backend -c \
    'exec java ${JAVA_OPTS:-} -Xmx128m -Dloader.main=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher verify' \
    >/dev/null 2>&1; then
    echo "Diary encryption key verification failed. Existing containers were not replaced." >&2
    return 1
  fi

  echo "Diary encryption key verification passed."
)
