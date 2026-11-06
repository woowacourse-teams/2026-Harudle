#!/usr/bin/env sh
set -eu

# The JVM echoes these automatic options before application logging or secret redaction can run.
# Use JAVA_OPTS, which the private deployment verifier also receives, for supported JVM settings.
if [ -n "${JAVA_TOOL_OPTIONS:-}" ] || [ -n "${JDK_JAVA_OPTIONS:-}" ] || [ -n "${_JAVA_OPTIONS:-}" ]; then
  echo 'Automatic JVM option environment variables are not allowed. Use JAVA_OPTS.' >&2
  exit 1
fi

exec "$@"
