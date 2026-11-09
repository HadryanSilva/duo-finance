#!/usr/bin/env bats
# deploy.sh tests, stub-based (no Docker, no network). Run from the repo root:
#   npx --yes bats@latest infra/tests/deploy.bats
# Each test copies deploy.sh into a temp dir with a .env (IMAGE_TAG=sha-old) and runs it with
# helpers/bin first on PATH: the docker stub records calls and the tag it "started", the smoke
# stub (SMOKE_CMD) passes or fails per tag. flock is stubbed only when the host has none.

# Each @test runs in its own subshell, so per-test exports are intended.
# shellcheck disable=SC2030,SC2031
bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
# From pwd: on Git Bash BATS_TEST_DIRNAME may be C:/..., whose colon would split PATH.
HELPERS="$REPO_ROOT/infra/tests/helpers"
API=ghcr.io/hadryansilva/duo-finance-api
NGINX=ghcr.io/hadryansilva/duo-finance-nginx

setup() {
  WORK="$BATS_TEST_TMPDIR/deploy"
  mkdir -p "$WORK"
  cp "$REPO_ROOT/infra/scripts/deploy.sh" "$WORK/deploy.sh"
  # Values that are not shell-safe on purpose: deploy.sh must never source .env.
  # shellcheck disable=SC2016
  printf '%s\n' \
    '# production env' \
    'POSTGRES_PASSWORD=p$w`d"x'"'"'y' \
    'IMAGE_TAG=sha-old' \
    'PUBLIC_BASE_URL=https://duofinance.test' \
    'JWT_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIIabc+/=\n-----END PRIVATE KEY-----' \
    'EMAIL_FROM=Duo Finance <no-reply@duofinance.test>' \
    '' \
    'TRAILING=last line' >"$WORK/.env"
  cp "$WORK/.env" "$BATS_TEST_TMPDIR/env.orig"

  export STUB_LOG="$BATS_TEST_TMPDIR/calls.log"
  export STUB_STATE="$BATS_TEST_TMPDIR/state"
  mkdir -p "$STUB_STATE"
  : >"$STUB_LOG"
  echo sha-old >"$STUB_STATE/running-tag"

  export PATH="$HELPERS/bin:$PATH"
  if ! command -v flock >/dev/null 2>&1; then
    export PATH="$HELPERS/flock:$PATH"
  fi
  export SMOKE_CMD="$HELPERS/bin/smoke"
  export SMOKE_TIMEOUT=0 SMOKE_INTERVAL=0
  export DEPLOY_LOCK_FILE="$BATS_TEST_TMPDIR/deploy.lock"
  unset SMOKE_BASE_URL SMOKE_FAIL_FOR_TAG SMOKE_FAIL_TIMES DOCKER_PULL_FAIL DOCKER_IMAGES FLOCK_BUSY
}

teardown() {
  exec 8>&- 2>/dev/null || true
}

image_tag() {
  sed -n 's/^IMAGE_TAG=//p' "$WORK/.env"
}

# Every .env line except IMAGE_TAG is byte-for-byte what setup wrote.
assert_other_lines_untouched() {
  cmp <(grep -v '^IMAGE_TAG=' "$BATS_TEST_TMPDIR/env.orig") <(grep -v '^IMAGE_TAG=' "$WORK/.env")
  [ "$(grep -c '^IMAGE_TAG=' "$WORK/.env")" -eq 1 ]
  # IMAGE_TAG stays on its original line.
  [ "$(grep -n '^IMAGE_TAG=' "$WORK/.env" | cut -d: -f1)" -eq 3 ]
}

@test "deploy_updatesTagAndPassesSmoke" {
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(image_tag)" = sha-new ]
  [ "$(cat "$WORK/.previous-tag")" = sha-old ]
  assert_other_lines_untouched
  # Every deploy.sh log line is timestamped.
  [[ "${lines[0]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\ deploy: ]]
  [ "$(grep -c 'deploy: ' <<<"$output")" -eq "$(grep -cE '^[0-9-]{10}T[0-9:]{8}Z deploy: ' <<<"$output")" ]
  # pull then up, both on the production compose file, then the smoke against PUBLIC_BASE_URL.
  grep -n . "$STUB_LOG"
  pull_line="$(grep -n '^docker compose -f docker-compose.yml pull api nginx$' "$STUB_LOG" | cut -d: -f1)"
  up_line="$(grep -n '^docker compose -f docker-compose.yml up -d --remove-orphans$' "$STUB_LOG" | cut -d: -f1)"
  [ -n "$pull_line" ]
  [ -n "$up_line" ]
  [ "$pull_line" -lt "$up_line" ]
  grep -qx 'smoke https://duofinance.test sha-new' "$STUB_LOG"
  run ! grep -q '^curl' "$STUB_LOG"
}

@test "deploy_smokeBaseUrlOverridesPublicBaseUrl" {
  SMOKE_BASE_URL=http://localhost:8088 run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  grep -qx 'smoke http://localhost:8088 sha-new' "$STUB_LOG"
}

@test "deploy_retriesSmokeUntilHealthy" {
  export SMOKE_TIMEOUT=30 SMOKE_FAIL_TIMES=2
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(grep -c '^smoke ' "$STUB_LOG")" -eq 3 ]
  [ "$(image_tag)" = sha-new ]
}

@test "deploy_rollsBackWhenHealthFails" {
  export SMOKE_FAIL_FOR_TAG=sha-new
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(image_tag)" = sha-old ]
  assert_other_lines_untouched
  [[ "$output" == *"rolled back to sha-old"* ]]
  # Old version started again and verified.
  [ "$(grep -c '^docker compose -f docker-compose.yml up -d' "$STUB_LOG")" -eq 2 ]
  [ "$(cat "$STUB_STATE/running-tag")" = sha-old ]
  grep -qx 'smoke https://duofinance.test sha-old' "$STUB_LOG"
  # No pruning after a failed deploy.
  run ! grep -q '^docker image rm' "$STUB_LOG"
}

@test "deploy_rollsBackWhenPullFails" {
  export DOCKER_PULL_FAIL=1
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(image_tag)" = sha-old ]
  [[ "$output" == *"rolled back to sha-old"* ]]
}

@test "deploy_exits2WhenRollbackAlsoFails" {
  export SMOKE_FAIL_FOR_TAG="sha-new sha-old"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [ "$(image_tag)" = sha-old ]
  assert_other_lines_untouched
  [[ "$output" == *"rollback to sha-old FAILED"* ]]
}

@test "deploy_exits2WhenNoPreviousTag" {
  sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=/' "$WORK/.env"
  export SMOKE_FAIL_FOR_TAG=sha-new
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [[ "$output" == *"no previous tag"* ]]
}

@test "deploy_refusesConcurrentRun" {
  if [ "$(command -v flock)" = "$HELPERS/flock/flock" ]; then
    export FLOCK_BUSY=1
  else
    # Real flock: hold the lock from this test process on another open file description.
    exec 8>"$DEPLOY_LOCK_FILE"
    flock -n 8
  fi
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -ne 0 ]
  [[ "$output" == *"another deploy is running"* ]]
  cmp "$BATS_TEST_TMPDIR/env.orig" "$WORK/.env"
  [ ! -e "$WORK/.previous-tag" ]
  run ! grep -q '^docker' "$STUB_LOG"
}

@test "deploy_requiresTagArgument" {
  run "$WORK/deploy.sh"
  [ "$status" -ne 0 ]
  [[ "$output" == *"usage: deploy.sh <image-tag>"* ]]
  run "$WORK/deploy.sh" 'sha-new;rm -rf /'
  [ "$status" -ne 0 ]
  [[ "$output" == *"invalid image tag"* ]]
  cmp "$BATS_TEST_TMPDIR/env.orig" "$WORK/.env"
  run ! grep -q '^docker' "$STUB_LOG"
}

@test "deploy_prunesOnlyOldAppImages" {
  export DOCKER_IMAGES="$API:sha-new $API:sha-old $API:sha-older $API:<none> \
$NGINX:sha-new $NGINX:sha-old $NGINX:sha-ancient $NGINX:main postgres:16 certbot/certbot:v3.0.1"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  grep '^docker image rm' "$STUB_LOG" | sort >"$BATS_TEST_TMPDIR/rm.log"
  cat "$BATS_TEST_TMPDIR/rm.log"
  printf '%s\n' "docker image rm $API:sha-older" "docker image rm $NGINX:main" \
    "docker image rm $NGINX:sha-ancient" | sort | cmp - "$BATS_TEST_TMPDIR/rm.log"
  run ! grep -q 'prune' "$STUB_LOG"
}

@test "deploy_sameTagRedeployKeepsRealPrevious" {
  run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  export SMOKE_FAIL_FOR_TAG=sha-new
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(cat "$WORK/.previous-tag")" = sha-old ]
  [ "$(image_tag)" = sha-old ]
  [[ "$output" == *"rolled back to sha-old"* ]]
}
