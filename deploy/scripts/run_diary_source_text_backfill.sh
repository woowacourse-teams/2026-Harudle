#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly APP_DIR="${HARUDLE_APP_DIR:-/opt/harudle}"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  echo "$1" >&2
  exit 1
}

if (( $# < 3 )); then
  fail 'Usage: run_diary_source_text_backfill.sh <dev|prod> --candidate-image=<sha256:image-id> <status|migrate|verify|finalize|ready> [--batch-size N] [--max-batches N]'
fi
readonly EXPECTED_ENV="$1" COMMAND="$3"
[[ "$2" == --candidate-image=* ]] || fail 'An explicit --candidate-image is required.'
readonly CANDIDATE_IMAGE="${2#--candidate-image=}"
shift 3
[[ "${EXPECTED_ENV}" =~ ^(dev|prod)$ ]] || fail 'Invalid deployment environment.'
[[ "${CANDIDATE_IMAGE}" =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'An explicit sha256 candidate image ID is required.'
case "${COMMAND}" in
  status|migrate|verify|finalize|ready) ;;
  *) fail 'Unsupported diary source text command.' ;;
esac

options=()
seen_batch_size=false
seen_max_batches=false
while (( $# > 0 )); do
  (( $# >= 2 )) || fail 'Each maintenance option requires a value.'
  case "$1" in
    --batch-size)
      [[ "${COMMAND}" != status && "${COMMAND}" != ready && "${seen_batch_size}" == false \
        && "$2" =~ ^[1-9][0-9]{0,3}$ ]] && (( $2 <= 1000 )) \
        || fail 'batch-size is available once for migrate, verify, or finalize, between 1 and 1000.'
      seen_batch_size=true
      ;;
    --max-batches)
      [[ "${COMMAND}" == migrate && "${seen_max_batches}" == false && "$2" =~ ^[1-9][0-9]{0,4}$ ]] \
        && (( $2 <= 10000 )) || fail 'max-batches is available once for migrate, between 1 and 10000.'
      seen_max_batches=true
      ;;
    *) fail 'Unsupported maintenance option.' ;;
  esac
  options+=("$1" "$2")
  shift 2
done

for command_name in docker jq; do
  command -v "${command_name}" >/dev/null 2>&1 || fail 'Docker Compose and jq are required.'
done
if ! image_id="$(docker image inspect --format '{{.Id}}' "${CANDIDATE_IMAGE}" 2>/dev/null)" \
  || [[ "${image_id}" != "${CANDIDATE_IMAGE}" ]]; then
  fail 'The exact candidate image must be loaded locally before maintenance.'
fi

export DEPLOYMENT_GROUP_NAME="harudle-${EXPECTED_ENV}"
source "${SCRIPT_DIR}/compose_environment.sh"
source "${SCRIPT_DIR}/diary_key_verification.sh"
configure_compose "${APP_DIR}"
cd "${APP_DIR}"

# Raw stdout and stderr may contain environment values from early JVM or launcher failures.
# Publish only the CLI's fixed, typed result fields after checking the complete output.
result_file="$(mktemp "${TMPDIR:-/tmp}/harudle-diary-backfill-result.XXXXXX" 2>/dev/null)" ||
  fail 'Diary source text maintenance could not prepare private output.'
trap 'rm -f -- "${result_file}"' EXIT
result_status=0
run_private_diary_cli \
  com.harudle.diary.infrastructure.crypto.DiarySourceTextBackfillMain "${CANDIDATE_IMAGE}" \
  "${COMMAND}" ${options[@]+"${options[@]}"} >"${result_file}" 2>/dev/null || result_status=$?
if ! safe_result="$(validate_diary_source_text_cli_result "${COMMAND}" "${result_status}" "${result_file}")"; then
  fail 'Diary source text maintenance failed. Candidate diagnostics were suppressed.'
fi
printf '%s\n' "${safe_result}"
if (( result_status == 2 )); then
  echo 'Diary source text migration remains pending; inspect the counts and rerun migrate.' >&2
elif (( result_status == 1 )); then
  echo 'Diary source text maintenance failed. Candidate diagnostics were suppressed.' >&2
fi
exit "${result_status}"
