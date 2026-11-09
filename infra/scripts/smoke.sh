#!/usr/bin/env bash
# Usage: smoke.sh <base-url>   Exits non-zero on the first failing check.
set -euo pipefail

base="${1:?usage: smoke.sh <base-url>}"
base="${base%/}"
body="$(mktemp)"
trap 'rm -f "$body"' EXIT

fail() {
  echo "smoke: FAIL: $*" >&2
  exit 1
}

# check <path> <expected-substring-or-empty> <description>
check() {
  local path="$1" needle="$2" what="$3" code
  code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 10 "$base$path")" || fail "$what: request to $base$path failed"
  [ "$code" = "200" ] || fail "$what: $base$path returned $code"
  if [ -n "$needle" ] && ! grep -q -- "$needle" "$body"; then
    fail "$what: $base$path response lacks $needle"
  fi
  echo "smoke: ok: $what"
}

check /api/actuator/health '"status":"UP"' "api health is UP"
check / '<div id="app">' "SPA index served"
check /api/v1/public/registration-mode "" "public registration-mode"
check /transactions '<div id="app">' "SPA fallback for deep link"
echo "smoke: all checks passed"
