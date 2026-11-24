#!/usr/bin/env bash
# Retry Gradle only for transient repository/network failures (for example,
# Maven Central HTTP 429). Source and test failures still fail immediately.
set -uo pipefail

if (($# == 0)); then
  echo "Usage: $0 <gradle arguments...>" >&2
  exit 2
fi

max_attempts="${GRADLE_RETRY_ATTEMPTS:-3}"
base_delay_seconds="${GRADLE_RETRY_BASE_DELAY_SECONDS:-15}"
if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || ! [[ "$base_delay_seconds" =~ ^[0-9]+$ ]]; then
  echo "GRADLE_RETRY_ATTEMPTS must be positive and GRADLE_RETRY_BASE_DELAY_SECONDS must be non-negative." >&2
  exit 2
fi

log_file="${GRADLE_LOG_FILE:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-retry.log}"
mkdir -p "$(dirname "$log_file")" || exit 1
: > "$log_file" || exit 1

is_transient_failure() {
  grep -Eiq \
    '(^|[^0-9])(429|500|502|503|504)([^0-9]|$)|too many requests|rate[- ]limit|unknownhostexception|sockettimeoutexception|read timed out|connection (reset|refused|timed out)|temporary failure in name resolution|resource temporarily unavailable|no route to host|could not resolve host' \
    "$1"
}

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  echo "Gradle attempt ${attempt}/${max_attempts}: gradle $*"
  gradle "$@" 2>&1 | tee -a "$log_file"
  gradle_status=${PIPESTATUS[0]}

  if ((gradle_status == 0)); then
    exit 0
  fi

  if ((attempt == max_attempts)) || ! is_transient_failure "$log_file"; then
    echo "Gradle failed with exit code ${gradle_status}; no retryable repository/network failure remains." >&2
    exit "$gradle_status"
  fi

  delay_seconds=$((base_delay_seconds * attempt))
  echo "Transient repository/network failure detected; retrying in ${delay_seconds}s." >&2
  sleep "$delay_seconds"
done
