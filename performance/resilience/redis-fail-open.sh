#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
JWT_TOKEN="${JWT_TOKEN:-}"
ALLOW_NONLOCAL="${ALLOW_NONLOCAL:-false}"

if [[ -z "$JWT_TOKEN" ]]; then
  echo "JWT_TOKEN is required (payments:write scope)." >&2
  exit 2
fi

if [[ "$BASE_URL" != http://localhost:* && "$BASE_URL" != http://127.0.0.1:* && "$ALLOW_NONLOCAL" != "true" ]]; then
  echo "Refusing to stop Redis for a non-local target. Set ALLOW_NONLOCAL=true only in an isolated test environment." >&2
  exit 2
fi

restore_redis() {
  echo "Restoring local Redis..."
  docker compose start redis >/dev/null
}
trap restore_redis EXIT

echo "Stopping local Redis to exercise PostgreSQL idempotency fallback..."
docker compose stop redis >/dev/null

key="redis-outage-$(date +%s)-$$"
response_file="$(mktemp)"
trap 'rm -f "$response_file"; restore_redis' EXIT

status=$(curl --silent --show-error \
  --output "$response_file" \
  --write-out '%{http_code}' \
  --request POST "$BASE_URL/api/v1/payments" \
  --header "Authorization: Bearer $JWT_TOKEN" \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $key" \
  --data '{"amount":42.50,"currency":"USD"}')

if [[ "$status" != "201" ]]; then
  echo "Expected HTTP 201 while Redis was unavailable; got $status" >&2
  cat "$response_file" >&2
  exit 1
fi

echo "PASS: payment creation remained available with Redis stopped (HTTP 201)."
