#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "${test_dir}"' EXIT
mkdir -p "${test_dir}/scripts"
cp "${script_dir}/prepare_diary_release.sh" "${test_dir}/scripts/"
cp "${script_dir}/../compose.diary-reader.yaml" "${script_dir}/../compose.diary-writer.yaml" "${test_dir}/"

env -u DIARY_SOURCE_TEXT_RELEASE_MODE bash "${test_dir}/scripts/prepare_diary_release.sh" >/dev/null
test "$(cat "${test_dir}/diary-source-text-release-mode")" = reader
DIARY_SOURCE_TEXT_RELEASE_MODE=writer bash "${test_dir}/scripts/prepare_diary_release.sh" >/dev/null
test "$(cat "${test_dir}/diary-source-text-release-mode")" = writer
if DIARY_SOURCE_TEXT_RELEASE_MODE=invalid bash "${test_dir}/scripts/prepare_diary_release.sh" >/dev/null 2>&1; then
  echo "Invalid release mode was accepted." >&2
  exit 1
fi
test "$(cat "${test_dir}/diary-source-text-release-mode")" = writer
rm "${test_dir}/compose.diary-reader.yaml"
if DIARY_SOURCE_TEXT_RELEASE_MODE=reader bash "${test_dir}/scripts/prepare_diary_release.sh" >/dev/null 2>&1; then
  echo "Missing reader override was accepted." >&2
  exit 1
fi
test "$(cat "${test_dir}/diary-source-text-release-mode")" = writer
echo "PASS: release selection defaults to reader and preserves the artifact on invalid input."
