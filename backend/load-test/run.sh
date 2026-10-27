#!/usr/bin/env bash
set -euo pipefail

mode="${1:-peak}"
run="${2:-$mode}"
members="${3:-members}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$mode" != smoke && "$mode" != peak && "$mode" != mixed ]] || [[ ! "$run" =~ ^[a-zA-Z0-9_-]+$ ]] || [[ ! "$members" =~ ^[a-zA-Z0-9_-]+$ ]]; then
  echo 'Usage: bash load-test/run.sh [smoke|peak|mixed] [new-run-name] [members-folder=members]' >&2
  exit 1
fi
if [[ ! -f "$script_dir/runs/$members/members.json" ]]; then
  echo 'Receive the reusable members.json first. Before each run, use ec2.py reset on EC2.' >&2
  exit 1
fi
if [[ "${HARUDLE_LOAD_TEST_LOCKED:-}" != "$run" ]]; then
  exec python3 - "$script_dir" "$mode" "$run" "$members" <<'PY'
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, sys.argv[1])
from assets import load_fixture, run_lock
from prepare import write_fixture
directory, mode, run, members = sys.argv[1:]
group = Path(directory) / 'runs' / members
with run_lock(group):
    accounts = load_fixture(group)
    expected = 1200 if mode == 'mixed' else 200
    if len(accounts['members']) != expected:
        raise ValueError(f'{mode} requires {expected} members; choose the matching member folder')
    write_fixture(Path(directory) / 'runs' / run, accounts=accounts)
    result = subprocess.run(['bash', directory + '/run.sh', mode, run, members],
                            env={**os.environ, 'HARUDLE_LOAD_TEST_LOCKED': run})
    sys.exit(result.returncode)
PY
fi
if [[ -f "$script_dir/runs/$run/cleanup.json" ]]; then
  echo 'Cleanup has started; prepare a fresh run.' >&2
  exit 1
fi
if ! (set -o noclobber; : > "$script_dir/runs/$run/.started") 2>/dev/null; then
  echo 'This run was already started. Reset on EC2 and use a new run name.' >&2
  exit 1
fi
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -p 127.0.0.1:5665:5665 \
  -v "$script_dir:/scripts" \
  -e "MODE=$mode" -e "MEMBERS_FILE=/scripts/runs/$run/members.json" \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 \
  -e K6_WEB_DASHBOARD_PERIOD=5s \
  -e "K6_WEB_DASHBOARD_EXPORT=/scripts/runs/$run/report.html" \
  grafana/k6:2.3.0 run --summary-export "/scripts/runs/$run/summary.json" /scripts/members.js \
  2>&1 | tee "$script_dir/runs/$run/k6.log"
