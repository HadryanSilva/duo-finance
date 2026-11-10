#!/usr/bin/env bats
# smoke.sh tests, stub-based (no Docker, no network). Run from the repo root:
#   npx --yes bats@latest infra/tests/smoke.bats
# A curl stub first on PATH logs each call's arguments (one line per call) and answers 200 with a
# body that satisfies every check.

bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"

setup() {
  local bin="$BATS_TEST_TMPDIR/bin"
  mkdir -p "$bin"
  cat >"$bin/curl" <<'STUB'
#!/usr/bin/env bash
echo "curl $*" >>"$CURL_LOG"
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '%s' '{"status":"UP"} <div id="app">' >"$out"
printf 200
STUB
  chmod +x "$bin/curl"
  export CURL_LOG="$BATS_TEST_TMPDIR/curl.log"
  : >"$CURL_LOG"
  export PATH="$bin:$PATH"
  unset SMOKE_RESOLVE
}

@test "smoke_withoutResolve_usesDns" {
  run bash "$REPO_ROOT/infra/scripts/smoke.sh" https://duofinance.test
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(wc -l <"$CURL_LOG")" -eq 4 ]
  run ! grep -q -- '--resolve' "$CURL_LOG"
  grep -q ' https://duofinance.test/api/actuator/health$' "$CURL_LOG"
}

@test "smoke_resolve_pinsHttpsHostToGivenAddress" {
  SMOKE_RESOLVE=127.0.0.1 run bash "$REPO_ROOT/infra/scripts/smoke.sh" https://duofinance.test/
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(wc -l <"$CURL_LOG")" -eq 4 ]
  [ "$(grep -c -- '--resolve duofinance.test:443:127.0.0.1 ' "$CURL_LOG")" -eq 4 ]
  # The URL keeps the host name (SNI and certificate check unchanged).
  grep -q ' https://duofinance.test/transactions$' "$CURL_LOG"
}

@test "smoke_resolve_usesSchemeOrExplicitPort" {
  SMOKE_RESOLVE=127.0.0.1 run bash "$REPO_ROOT/infra/scripts/smoke.sh" http://duofinance.test
  [ "$status" -eq 0 ]
  [ "$(grep -c -- '--resolve duofinance.test:80:127.0.0.1 ' "$CURL_LOG")" -eq 4 ]
  : >"$CURL_LOG"
  SMOKE_RESOLVE=127.0.0.1 run bash "$REPO_ROOT/infra/scripts/smoke.sh" http://localhost:8088
  [ "$status" -eq 0 ]
  [ "$(grep -c -- '--resolve localhost:8088:127.0.0.1 ' "$CURL_LOG")" -eq 4 ]
}
