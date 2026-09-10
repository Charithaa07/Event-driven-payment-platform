#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
JWT_TOKEN="${JWT_TOKEN:-}"
PAYMENT_COUNT="${PAYMENT_COUNT:-5}"
RECOVERY_TIMEOUT_SECONDS="${RECOVERY_TIMEOUT_SECONDS:-90}"
ALLOW_NONLOCAL="${ALLOW_NONLOCAL:-false}"

if [[ -z "$JWT_TOKEN" ]]; then
  echo "JWT_TOKEN is required (payments:write scope)." >&2
  exit 2
fi

if [[ "$BASE_URL" != http://localhost:* && "$BASE_URL" != http://127.0.0.1:* && "$ALLOW_NONLOCAL" != "true" ]]; then
  echo "Refusing a broker failure drill for a non-local target. Set ALLOW_NONLOCAL=true only in an isolated test environment." >&2
  exit 2
fi

restore_kafka() {
  echo "Ensuring local Kafka is running..."
  docker compose start kafka >/dev/null
}
trap restore_kafka EXIT

outbox_unpublished() {
  docker compose exec -T postgres \
    psql -U payments -d payments -Atc \
    "SELECT count(*) FROM outbox_events WHERE status IN ('PENDING','PROCESSING');"
}

echo "Stopping local Kafka. Payment API should remain available because event intent is stored in the transactional outbox."
docker compose stop kafka >/dev/null

run_id="kafka-outage-$(date +%s)-$$"
for i in $(seq 1 "$PAYMENT_COUNT"); do
  status=$(curl --silent --show-error \
    --output /dev/null \
    --write-out '%{http_code}' \
    --request POST "$BASE_URL/api/v1/payments" \
    --header "Authorization: Bearer $JWT_TOKEN" \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: $run_id-$i" \
    --data '{"amount":42.50,"currency":"USD"}')

  if [[ "$status" != "201" ]]; then
    echo "Expected HTTP 201 while Kafka was unavailable; request $i returned $status" >&2
    exit 1
  fi
done

backlog=$(outbox_unpublished)
if (( backlog < PAYMENT_COUNT )); then
  echo "Expected at least $PAYMENT_COUNT unpublished outbox rows during outage; observed $backlog" >&2
  exit 1
fi

echo "PASS: $PAYMENT_COUNT payment requests committed while Kafka was stopped; unpublished outbox rows=$backlog."

echo "Restarting Kafka and waiting for the relay to drain the backlog..."
docker compose start kafka >/dev/null

deadline=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
while (( $(date +%s) < deadline )); do
  backlog=$(outbox_unpublished)
  if [[ "$backlog" == "0" ]]; then
    echo "PASS: outbox backlog drained after Kafka recovery."
    exit 0
  fi
  sleep 2
done

echo "Outbox backlog did not drain within ${RECOVERY_TIMEOUT_SECONDS}s; remaining=$backlog" >&2
exit 1
