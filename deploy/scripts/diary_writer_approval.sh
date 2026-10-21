#!/usr/bin/env bash

# This is an operator attestation, not automatic proof of off-host backup or HTTP recovery checks.
# The file lives outside deployment artifacts and must be renewed when the canary changes.
verify_diary_writer_approval() (
  set -Eeuo pipefail
  set +x

  [[ "${DEPLOY_ENV_VALUE:-}" == prod && "${DIARY_SOURCE_TEXT_RELEASE_MODE_VALUE:-}" == writer ]] || return 0
  local approval="${HARUDLE_DIARY_WRITER_APPROVAL_FILE:-/etc/harudle/diary-writer-approval.json}"
  local canary="${HARUDLE_DIARY_KEY_VERIFICATION_FILE:-/etc/harudle/diary-key-checks.json}"
  local canary_digest

  if ! private_approval_file "${approval}" approval 16384 ||
    ! private_approval_file "${canary}" canary 65536 ||
    ! canary_digest="$(diary_approval_sha256 "${canary}")"; then
    diary_writer_approval_failure
    return 1
  fi

  # Reject duplicate field names as well as multiple JSON documents before checking the flat schema.
  if ! jq -n --stream -e '
    [inputs | select(length == 2) | .[0]] as $paths |
    ($paths | length) == 10 and ($paths | unique | length) == 10
  ' "${approval}" >/dev/null 2>&1 || ! jq -se --arg digest "${canary_digest}" '
    def nonblank: type == "string" and length <= 1024 and test("\\S") and (test("[\\r\\n]") | not);
    length == 1 and (.[0] |
      type == "object" and
      (keys | sort) == (["formatVersion", "environment", "canarySha256", "devRestoreVerified",
        "devRestoreRecord", "prodBackupVerified", "prodBackupRecord", "readerRevision",
        "approvedAt", "approvedBy"] | sort) and
      .formatVersion == 1 and .environment == "prod" and .canarySha256 == $digest and
      .devRestoreVerified == true and .prodBackupVerified == true and
      (.devRestoreRecord | nonblank) and (.prodBackupRecord | nonblank) and (.approvedBy | nonblank) and
      (.readerRevision | nonblank) and
      (.approvedAt | type == "string" and
        test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") and
        . == (try (fromdateiso8601 | strftime("%Y-%m-%dT%H:%M:%SZ")) catch null) and
        (try (fromdateiso8601 <= now) catch false)))
  ' "${approval}" >/dev/null 2>&1; then
    diary_writer_approval_failure
    return 1
  fi
)

private_approval_file() {
  local file="$1" kind="$2" maximum="$3" metadata owner mode size
  [[ -f "${file}" && ! -L "${file}" ]] || return 1
  if ! metadata="$(stat -c '%u:%a:%s' -- "${file}" 2>/dev/null)"; then
    metadata="$(stat -f '%u:%Lp:%z' "${file}" 2>/dev/null)" || return 1
  fi
  IFS=: read -r owner mode size <<<"${metadata}"
  [[ "${owner}" == "${EUID}" && "${size}" =~ ^[0-9]+$ ]] || return 1
  (( size > 0 && size <= maximum )) || return 1
  if [[ "${kind}" == approval ]]; then
    [[ "${mode}" == 600 ]]
  else
    # The application UID 10001 needs the documented root:10001 640 canary mount.
    [[ "${mode}" == 600 || "${mode}" == 640 ]]
  fi
}

diary_approval_sha256() {
  local file="$1" result digest
  if command -v sha256sum >/dev/null 2>&1; then
    result="$(sha256sum -- "${file}" 2>/dev/null)" || return 1
  else
    result="$(shasum -a 256 -- "${file}" 2>/dev/null)" || return 1
  fi
  digest="${result%% *}"
  [[ "${digest}" =~ ^[a-f0-9]{64}$ ]] || return 1
  printf '%s' "${digest}"
}

diary_writer_approval_failure() {
  echo "운영 writer 승인을 확인하지 못했습니다. 복원 검증 기록을 확인하고 기존 컨테이너를 유지하세요." >&2
}
