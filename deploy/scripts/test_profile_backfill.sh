#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
export HARUDLE_APP_DIR="${TEST_DIR}/app"
export BACKFILL_TEST_DIR="${TEST_DIR}"
mkdir -p "${HARUDLE_APP_DIR}" "${TEST_DIR}/bin" "${TEST_DIR}/maintenance"
printf 'DEPLOY_ENV=dev\n' > "${HARUDLE_APP_DIR}/.env"
cp "${SCRIPT_DIR}/run_profile_backfill.sh" "${TEST_DIR}/maintenance/"
printf 'SELECT 1;\n' > "${TEST_DIR}/maintenance/backfill_user_public_profiles.sql"

cat > "${TEST_DIR}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
case "$1" in
  compose) printf 'test-backend\n' ;;
  inspect) printf '["DEPLOY_ENV=dev","DB_HOST=test-db","DB_PASSWORD=password=with$special"]\n' ;;
  run)
    printf '%s\n' "$@" > "${BACKFILL_TEST_DIR}/run.args"
    [[ "${PGHOST}" == test-db && "${PGPASSWORD}" == 'password=with$special' ]]
    exit "${BACKFILL_TEST_EXIT:-0}"
    ;;
  *) exit 1 ;;
esac
SH
chmod +x "${TEST_DIR}/bin/docker"
export PATH="${TEST_DIR}/bin:${PATH}"

bash "${TEST_DIR}/maintenance/run_profile_backfill.sh" dev > "${TEST_DIR}/output" 2>&1
grep -Fq 'CALL pg_temp.backfill_user_public_profiles(100);' "${TEST_DIR}/run.args"
grep -Fq 'container:test-backend' "${TEST_DIR}/run.args"
grep -Fq 'Public profile backfill completed.' "${TEST_DIR}/output"
if grep -Fq 'password=with$special' "${TEST_DIR}/output" "${TEST_DIR}/run.args"; then
  echo 'FAIL: DB password appeared in output or Docker arguments.' >&2
  exit 1
fi
echo 'PASS: 정상 실행과 DB 설정 전달'

rm "${TEST_DIR}/run.args"
if bash "${TEST_DIR}/maintenance/run_profile_backfill.sh" prod > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: Environment mismatch was ignored.' >&2
  exit 1
fi
[[ ! -f "${TEST_DIR}/run.args" ]]
grep -Fq 'Backend environment mismatch' "${TEST_DIR}/output"
echo 'PASS: 환경 불일치 시 실행 중단'

export BACKFILL_TEST_EXIT=3
if bash "${TEST_DIR}/maintenance/run_profile_backfill.sh" dev > "${TEST_DIR}/output" 2>&1; then
  echo 'FAIL: DB execution failure was ignored.' >&2
  exit 1
fi
[[ -f "${TEST_DIR}/run.args" ]]
if grep -Fq 'Public profile backfill completed.' "${TEST_DIR}/output"; then
  echo 'FAIL: Failed backfill was reported as completed.' >&2
  exit 1
fi
echo 'PASS: DB 실행 실패 전달'

echo 'Public profile backfill runner: 3 tests passed.'
