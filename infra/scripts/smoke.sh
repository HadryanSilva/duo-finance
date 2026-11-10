#!/usr/bin/env bash
# Usage: smoke.sh <base-url>   Exits non-zero on the first failing check.
# SMOKE_RESOLVE=<address> (optional; deploy.sh passes 127.0.0.1): curl connects to <address> for
# the URL's host and port (--resolve), so the check skips public DNS and hairpin NAT while the
# Host header, SNI and certificate check still use the real host name. Port: the URL's explicit
# port, else 443 for https and 80 for http.
set -euo pipefail

base="${1:?usage: smoke.sh <base-url>}"
base="${base%/}"
body="$(mktemp)"
trap 'rm -f "$body"' EXIT

fail() {
  echo "smoke: FAIL: $*" >&2
  exit 1
}

curl_args=(-sS --max-time 10)
if [ -n "${SMOKE_RESOLVE:-}" ]; then
  authority="${base#*://}"
  authority="${authority%%/*}"
  host="${authority%%:*}"
  if [ "$authority" != "$host" ]; then
    port="${authority##*:}"
  elif [[ "$base" == https://* ]]; then
    port=443
  else
    port=80
  fi
  curl_args+=(--resolve "$host:$port:$SMOKE_RESOLVE")
fi

# check <path> <expected-substring-or-empty> <description>
check() {
  local path="$1" needle="$2" what="$3" code
  code="$(curl "${curl_args[@]}" -o "$body" -w '%{http_code}' "$base$path")" || fail "$what: request to $base$path failed"
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
