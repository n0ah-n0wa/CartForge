#!/usr/bin/env bash
# Post-deployment smoke test for CartForge.
#
# Verifies readiness (includes PostgreSQL), public catalog, and auth endpoint
# behaviour. Redis may be up or fail-open; the application must remain usable
# either way. Any unhealthy or unexpected response fails the script.
#
# Usage:
#   ./scripts/ci/smoke-test.sh [base-url]
#   SMOKE_BASE_URL=http://127.0.0.1:8080 ./scripts/ci/smoke-test.sh
#
set -euo pipefail

BASE_URL="${1:-${SMOKE_BASE_URL:-http://127.0.0.1:8080}}"
BASE_URL="${BASE_URL%/}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-90}"
CURL_OPTS=(--silent --show-error --connect-timeout 5 --max-time 15)

log() { printf 'smoke: %s\n' "$*"; }
fail() { printf 'smoke: FAIL: %s\n' "$*" >&2; exit 1; }

require_json_status_up() {
  local body="$1"
  local label="$2"
  printf '%s' "${body}" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' \
    || fail "${label} did not report status UP. Body: ${body}"
}

http_code() {
  local method="$1"
  local url="$2"
  shift 2
  curl "${CURL_OPTS[@]}" -o /tmp/smoke-body.txt -w '%{http_code}' -X "${method}" "${url}" "$@"
}

wait_for_readiness() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  local body=""
  local code=""
  log "waiting for readiness at ${BASE_URL} (timeout ${TIMEOUT_SECONDS}s)"
  while (( SECONDS < deadline )); do
    code="$(curl "${CURL_OPTS[@]}" -o /tmp/smoke-ready.json -w '%{http_code}' \
      "${BASE_URL}/actuator/health/readiness" || true)"
    if [[ "${code}" == "200" ]]; then
      body="$(cat /tmp/smoke-ready.json)"
      if printf '%s' "${body}" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        log "readiness UP (includes database probe)"
        return 0
      fi
    fi
    sleep 2
  done
  fail "application did not become ready within ${TIMEOUT_SECONDS}s (last HTTP ${code:-none})"
}

check_health() {
  local body
  body="$(curl "${CURL_OPTS[@]}" --fail "${BASE_URL}/actuator/health/liveness")"
  require_json_status_up "${body}" "liveness"

  body="$(curl "${CURL_OPTS[@]}" --fail "${BASE_URL}/actuator/health")"
  require_json_status_up "${body}" "health"
  log "health and liveness UP"
}

check_catalog() {
  local body code
  code="$(http_code GET "${BASE_URL}/api/v1/categories")"
  [[ "${code}" == "200" ]] || fail "GET /api/v1/categories expected 200, got ${code}"
  body="$(cat /tmp/smoke-body.txt)"
  [[ "${body}" == \[* ]] || fail "categories response was not a JSON array: ${body}"

  code="$(http_code GET "${BASE_URL}/api/v1/products?page=0&size=1")"
  [[ "${code}" == "200" ]] || fail "GET /api/v1/products expected 200, got ${code}"
  body="$(cat /tmp/smoke-body.txt)"
  printf '%s' "${body}" | grep -q '"content"' \
    || fail "products response missing pagination content: ${body}"
  log "public catalog endpoints OK (Redis up or fail-open)"
}

check_auth() {
  # Deterministic: unknown credentials must yield 401 (DB + auth path).
  # 429 is acceptable (rate limit still proves the endpoint is serving).
  # 5xx always fails.
  local code
  code="$(http_code POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    --data '{"email":"smoke-nonexistent@example.com","password":"SmokeTestPassword1!"}')"

  case "${code}" in
    401)
      log "auth login endpoint OK (401 for unknown credentials)"
      ;;
    429)
      log "auth login endpoint OK (429 rate limited)"
      ;;
    *)
      fail "POST /api/v1/auth/login expected 401 or 429, got ${code}. Body: $(cat /tmp/smoke-body.txt)"
      ;;
  esac
}

main() {
  log "base URL=${BASE_URL}"
  command -v curl >/dev/null || fail "curl is required"
  wait_for_readiness
  check_health
  check_catalog
  check_auth
  log "PASS: deployment smoke checks succeeded"
}

main "$@"
