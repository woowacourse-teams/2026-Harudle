#!/usr/bin/env bash

# Keep Compose's resolved environment and mounts while isolating each maintenance process.
# Callers must suppress or validate stdout; JVM/launcher diagnostics are never public output.
run_private_diary_cli() (
  set -Eeuo pipefail
  set +x

  local main_class="$1" candidate_image="$2" override
  shift 2
  case "${main_class}" in
    com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain|\
    com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain) ;;
    *) return 1 ;;
  esac
  if [[ -n "${candidate_image}" && ! "${candidate_image}" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    return 1
  fi
  override="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-cli.XXXXXX" 2>/dev/null)" || return 1
  trap "$(printf 'rm -f -- %q' "${override}")" EXIT
  cat > "${override}" <<'YAML'
services:
  backend:
    logging: !override
      driver: none
YAML
  if [[ -n "${candidate_image}" ]]; then
    printf '    image: "%s"\n    pull_policy: never\n' "${candidate_image}" >> "${override}"
  fi

  docker compose "${COMPOSE_ARGS[@]}" --file "${override}" \
    run --rm --no-deps --pull never -T --entrypoint sh \
    --env "LOADER_MAIN=${main_class}" backend -c \
    'exec java ${JAVA_OPTS:-} -Xmx128m -Xlog:disable -Dloader.main="$LOADER_MAIN" -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$@"' \
    sh "$@"
)

verify_diary_source_text_keys() (
  set -Eeuo pipefail
  set +x
  local output status=0
  output="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-key-result.XXXXXX" 2>/dev/null)" || {
    echo "일기 암호화 키 검증을 준비하지 못했습니다." >&2
    return 1
  }
  trap 'rm -f -- "${output}"' EXIT
  run_private_diary_cli \
    com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain "" verify \
    >"${output}" 2>/dev/null || status=$?
  # JVM actions such as -version can return zero without invoking the verifier.
  if (( status != 0 )) || ! printf '%s\n' '{"verified":true}' | cmp -s - "${output}"; then
    echo "일기 암호화 키 검증에 실패했습니다. 기존 컨테이너는 교체하지 않았습니다." >&2
    return 1
  fi
  echo "일기 암호화 키 검증을 통과했습니다."
)

verify_diary_source_text_ready() (
  set -Eeuo pipefail
  set +x
  local output status=0
  output="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-ready-result.XXXXXX" 2>/dev/null)" || {
    echo "일기 원문 전환 완료 검증을 준비하지 못했습니다." >&2
    return 1
  }
  trap 'rm -f -- "${output}"' EXIT
  run_private_diary_cli \
    com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain "" ready \
    >"${output}" 2>/dev/null || status=$?
  if (( status != 0 )) || [[ "$(wc -l < "${output}" | tr -d '[:space:]')" != 1 ]] ||
    ! jq -n --stream -e '
      [inputs | select(length == 2) | .[0]] as $paths |
      ($paths | length) == 7 and ($paths | unique | length) == 7
    ' "${output}" >/dev/null 2>&1 || ! jq -se '
      length == 1 and (.[0] | type == "object" and
        (keys | sort) == (["command", "legacyRows", "encryptedRows", "unsupportedRows",
          "processingLegacyRows", "orphanRows", "finalized"] | sort) and
        .command == "ready" and .finalized == true and
        .legacyRows == 0 and .unsupportedRows == 0 and .processingLegacyRows == 0 and
        ([.legacyRows, .encryptedRows, .unsupportedRows, .processingLegacyRows, .orphanRows] |
          all(type == "number" and . >= 0 and floor == .)))
    ' "${output}" >/dev/null 2>&1; then
    echo "일기 원문 전환 완료를 확인하지 못했습니다. 기존 컨테이너는 교체하지 않았습니다." >&2
    return 1
  fi
  echo "일기 원문 전환 완료를 확인했습니다."
)
