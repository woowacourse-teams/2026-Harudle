#!/usr/bin/env bash
set -euo pipefail

release_mode="${DIARY_SOURCE_TEXT_RELEASE_MODE:-reader}"
case "${release_mode}" in
  reader|writer) ;;
  *) echo "Invalid diary source text release mode." >&2; exit 1 ;;
esac

release_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test -f "${release_dir}/compose.diary-${release_mode}.yaml"
printf '%s\n' "${release_mode}" > "${release_dir}/diary-source-text-release-mode"
echo "Diary source text release mode: ${release_mode}."
