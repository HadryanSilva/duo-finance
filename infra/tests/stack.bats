#!/usr/bin/env bats
# bats file_tags=docker
# Full local stack test. Run from the repo root (Git Bash on Windows, host Docker):
#   npx --yes bats@latest infra/tests/stack.bats
# Builds both images, starts docker-compose.yml + docker-compose.local.yml on host port 8088 with a
# temp env file (random passwords, keys from gen-jwt-keys.sh) written outside the repo, and always
# runs `down -v` at the end.

bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"

docker() {
  MSYS_NO_PATHCONV=1 command docker "$@"
}

stack() {
  (cd "$REPO_ROOT/infra" && DUO_ENV_FILE="$ENV_FILE" docker compose -p "$RUN_ID" --env-file "$ENV_FILE" \
    -f docker-compose.yml -f docker-compose.local.yml "$@")
}

setup_file() {
  export RUN_ID="duo-stack-$$-$RANDOM"
  export ENV_FILE
  ENV_FILE="$(mktemp)"
  # Native Windows docker compose needs a Windows path; no-op on Linux.
  if command -v cygpath >/dev/null 2>&1; then ENV_FILE="$(cygpath -m "$ENV_FILE")"; fi
  local pw
  pw="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  {
    echo "IMAGE_TAG=local"
    echo "POSTGRES_DB=duo"
    echo "POSTGRES_USER=duo"
    echo "POSTGRES_PASSWORD=$pw"
    echo "DB_URL=jdbc:postgresql://postgres:5432/duo"
    echo "DB_USER=duo"
    echo "DB_PASSWORD=$pw"
    echo "PUBLIC_BASE_URL=http://localhost:8088"
    echo "RESEND_API_KEY=re_test"
    echo "EMAIL_FROM=test@example.com"
    bash "$REPO_ROOT/infra/scripts/gen-jwt-keys.sh"
  } >"$ENV_FILE"
  if ! stack up -d --build --wait --wait-timeout 300 >&3 2>&3; then
    stack logs --tail 60 >&3 2>&3 || true
    return 1
  fi
}

teardown_file() {
  stack down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
}

@test "local stack boots and passes smoke" {
  run bash "$REPO_ROOT/infra/scripts/smoke.sh" http://localhost:8088
  echo "$output"
  [ "$status" -eq 0 ]
}

@test "api and postgres are not published" {
  run stack port api 8080
  [[ -z "${output//[[:space:]]/}" || "${output//[[:space:]]/}" == ":0" ]]
  run stack port postgres 5432
  [[ -z "${output//[[:space:]]/}" || "${output//[[:space:]]/}" == ":0" ]]
  run stack config
  [ "$(echo "$output" | grep -c "published:")" -eq 1 ]
}

@test "flyway migrated on startup" {
  run stack exec -T postgres psql -U duo -d duo -tA -c "SELECT max(version::int) FROM flyway_schema_history"
  [ "$status" -eq 0 ]
  [ "$(echo "$output" | tr -d '[:space:]')" = "4" ]
}

@test "public api works through nginx" {
  run curl -fsS http://localhost:8088/api/v1/public/registration-mode
  [ "$status" -eq 0 ]
  [ "$output" = '{"mode":"INVITE_ONLY"}' ]
  run curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d '{"email":"smoke@example.com","locale":"pt-BR"}' http://localhost:8088/api/v1/public/waitlist
  [ "$output" = "202" ]
}

@test "api healthcheck probe (bash /dev/tcp) works inside the image" {
  run stack exec -T api bash -c "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3 && grep -q '\"status\":\"UP\"' <&3"
  [ "$status" -eq 0 ]
}
