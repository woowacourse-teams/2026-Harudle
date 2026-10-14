#!/usr/bin/env bash
set -euo pipefail

mode="${1:-peak}"
run="${2:-$mode}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$mode" != smoke && "$mode" != peak ]] || [[ ! "$run" =~ ^[a-zA-Z0-9_-]+$ ]]; then
  echo 'Usage: bash load-test/run.sh [smoke|peak] [run-folder-name]' >&2
  exit 1
fi
if [[ ! -f "$script_dir/runs/$run/members.json" ]]; then
  echo 'Prepare accounts and apply seed.sql first.' >&2
  exit 1
fi
if ! (set -o noclobber; : > "$script_dir/runs/$run/.started") 2>/dev/null; then
  echo 'This fixture was already started. Refresh tokens rotate; prepare a fresh run.' >&2
  exit 1
fi
docker run --rm \
  -p 127.0.0.1:5665:5665 \
  -v "$script_dir:/scripts" \
  -e "MODE=$mode" -e "MEMBERS_FILE=/scripts/runs/$run/members.json" \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 \
  -e K6_WEB_DASHBOARD_PERIOD=5s \
  -e "K6_WEB_DASHBOARD_EXPORT=/scripts/runs/$run/report.html" \
  grafana/k6:2.3.0 run --summary-export "/scripts/runs/$run/summary.json" /scripts/members.js \
  2>&1 | tee "$script_dir/runs/$run/k6.log"
