#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${script_dir}/../../backend/docker-entrypoint.sh"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "${test_dir}"' EXIT
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

run_entrypoint() {
  sh "${entrypoint}" sh -c 'printf started > "$1"' sh "${test_dir}/started" \
    >"${test_dir}/output" 2>&1
}

run_entrypoint
test "$(cat "${test_dir}/started")" = started
rm "${test_dir}/started"

for automatic_option in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
  export "${automatic_option}=-Ddiary.key=public-test-secret-sentinel"
  if run_entrypoint; then
    echo 'Runtime entrypoint accepted automatic JVM options.' >&2
    exit 1
  fi
  test ! -e "${test_dir}/started"
  test "$(cat "${test_dir}/output")" = 'Automatic JVM option environment variables are not allowed. Use JAVA_OPTS.'
  unset "${automatic_option}"
done

JAVA_TOOL_OPTIONS= JDK_JAVA_OPTIONS= _JAVA_OPTIONS= run_entrypoint
test "$(cat "${test_dir}/started")" = started
echo 'PASS: runtime starts normally and rejects automatic JVM options before starting the command.'
