#!/usr/bin/env bats
# bats file_tags=docker
# Image tests. Run from the repo root (Git Bash on Windows, host Docker):
#   npx --yes bats@latest infra/tests/images.bats
# Keys are generated at run time into a temp env file; nothing secret is committed.

bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
API_IMAGE="duo-api:test"

setup_file() {
  export RUN_ID="duo-img-$$-$RANDOM"
  export NET="$RUN_ID-net"
  export ENV_FILE
  ENV_FILE="$(mktemp)"
  docker build -q -t "$API_IMAGE" "$REPO_ROOT/backend" >/dev/null
}

teardown_file() {
  rm -f "$ENV_FILE"
  docker ps -aq --filter "name=$RUN_ID" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}

teardown() {
  docker ps -aq --filter "name=$RUN_ID" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}

write_env() {
  local profiles="$1"
  {
    echo "SPRING_PROFILES_ACTIVE=$profiles"
    echo "DB_URL=jdbc:postgresql://$RUN_ID-db:5432/duo"
    echo "DB_USER=duo"
    echo "DB_PASSWORD=duo-test-pw"
    echo "PUBLIC_BASE_URL=http://localhost"
    echo "RESEND_API_KEY=re_test"
    echo "EMAIL_FROM=test@example.com"
    bash "$REPO_ROOT/infra/scripts/gen-jwt-keys.sh"
  } >"$ENV_FILE"
}

start_db() {
  docker network create "$NET" >/dev/null
  docker run -d --name "$RUN_ID-db" --network "$NET" \
    -e POSTGRES_DB=duo -e POSTGRES_USER=duo -e POSTGRES_PASSWORD=duo-test-pw \
    postgres:16 >/dev/null
  local i
  for i in $(seq 1 60); do
    docker exec "$RUN_ID-db" pg_isready -U duo -d duo >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "postgres did not become ready" >&2
  return 1
}

start_api() {
  docker run -d --name "$RUN_ID-api" --network "$NET" --env-file "$ENV_FILE" \
    -p 127.0.0.1::8080 "$API_IMAGE" >/dev/null
}

wait_healthy() {
  local port i
  port="$(docker port "$RUN_ID-api" 8080/tcp | head -n1 | sed 's/.*://' | tr -d '\r')"
  for i in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$port/api/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 1
  done
  docker logs "$RUN_ID-api" 2>&1 | tail -n 40 >&2
  return 1
}

@test "gen-jwt-keys prints two single-line PEM values" {
  run --separate-stderr bash "$REPO_ROOT/infra/scripts/gen-jwt-keys.sh"
  [ "$status" -eq 0 ]
  [ "$(echo "$output" | wc -l | tr -d ' ')" -eq 2 ]
  [[ "${lines[0]}" == JWT_PRIVATE_KEY=-----BEGIN\ PRIVATE\ KEY-----*-----END\ PRIVATE\ KEY----- ]]
  [[ "${lines[1]}" == JWT_PUBLIC_KEY=-----BEGIN\ PUBLIC\ KEY-----*-----END\ PUBLIC\ KEY----- ]]
}

@test "api image builds, runs as non-root and answers health with prod profile" {
  write_env prod
  start_db
  start_api
  wait_healthy
  run docker exec "$RUN_ID-api" id -u
  [ "$status" -eq 0 ]
  [ "$output" = "10001" ]
}

@test "api image refuses prod+e2e" {
  write_env prod,e2e
  start_db
  start_api
  run docker wait "$RUN_ID-api"
  [ "$status" -eq 0 ]
  [ "$output" != "0" ]
  run docker logs "$RUN_ID-api"
  [[ "$output" == *"must never run with prod"* ]]
}

@test "api image shuts down gracefully on docker stop (java is PID 1)" {
  write_env prod
  start_db
  start_api
  wait_healthy
  start=$SECONDS
  run docker stop -t 30 "$RUN_ID-api"
  [ "$status" -eq 0 ]
  [ $((SECONDS - start)) -lt 30 ]
  code="$(docker inspect -f '{{.State.ExitCode}}' "$RUN_ID-api")"
  [ "$code" = "0" ] || [ "$code" = "143" ]
  run docker logs "$RUN_ID-api"
  [[ "$output" == *"Commencing graceful shutdown"* ]]
}
