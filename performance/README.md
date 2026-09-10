# Performance and resilience verification

Phase 15 separates **load generation**, **correctness under contention**, and **operational SLOs**. The repository contains executable tools for each layer, but it does not claim production throughput until a controlled run has actually been executed and recorded.

## 1. Mixed payment API load

`k6/payment-api.js` drives a constant arrival rate of unique payment creates, optional same-key retries, and authenticated reads. Same-key retries verify that the returned payment ID is identical to the first response.

The token must include `payments:write payments:read`.

```bash
docker run --rm --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e JWT_TOKEN="$JWT_TOKEN" \
  -e RATE=25 \
  -e DURATION=1m \
  -e RETRY_RATIO=0.25 \
  -v "$PWD/performance/k6:/scripts:ro" \
  grafana/k6:2.2.0 run /scripts/payment-api.js
```

Default threshold targets are intentionally visible in the script: create p95 < 500 ms, create p99 < 1 s, retry/read p95 < 300 ms, request failures < 1%, checks > 99%, and zero idempotency mismatches. These are **engineering targets**, not pre-claimed measurements.

## 2. Redis/idempotency hot-key profile

`k6/idempotency-hot-key.js` seeds one payment and then sends concurrent retries using the exact same customer-scoped idempotency key and request body. Every successful retry must resolve to the seeded payment ID.

```bash
docker run --rm --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e JWT_TOKEN="$JWT_TOKEN" \
  -e VUS=20 \
  -e DURATION=30s \
  -v "$PWD/performance/k6:/scripts:ro" \
  grafana/k6:2.2.0 run /scripts/idempotency-hot-key.js
```

This profile primarily stresses the Redis fast path. The Java Testcontainers stress test separately covers simultaneous first-writer contention against PostgreSQL.

## 3. Local Redis failure drill

`resilience/redis-fail-open.sh` deliberately stops the **local Docker Compose Redis only**, creates a payment, verifies HTTP 201 through the PostgreSQL fallback, and restores Redis with a shell trap.

```bash
JWT_TOKEN="$JWT_TOKEN" ./performance/resilience/redis-fail-open.sh
```

The script refuses non-local targets unless `ALLOW_NONLOCAL=true` is explicitly set. Do not point failure drills at shared or production infrastructure.

## 4. Local Kafka outage / outbox recovery drill

`resilience/kafka-outage-outbox-recovery.sh` stops the local Kafka broker, sends several authenticated payment creates, verifies the API still accepts them, confirms unpublished outbox rows accumulate in PostgreSQL, restarts Kafka, and waits for the relay to drain the backlog.

```bash
JWT_TOKEN="$JWT_TOKEN" PAYMENT_COUNT=5 \
  ./performance/resilience/kafka-outage-outbox-recovery.sh
```

This directly exercises the reason the platform uses a transactional outbox: broker availability is decoupled from committing the payment request. The drill remains at-least-once; downstream idempotency is still the duplicate-safety boundary after broker recovery.

## 5. Kubernetes/HPA observation

For an isolated Kubernetes test environment, run a k6 profile while observing application resources:

```bash
kubectl -n payments get hpa -w
kubectl -n payments top pods
kubectl -n payments get pods -o wide
```

Record the initial replica count, peak replica count, CPU/memory pressure, Hikari pool utilization, Kafka behavior, and the load level at which latency or error thresholds first fail. HPA scaling is a capacity observation; it does not alter the platform's at-least-once/idempotency correctness model.

## 6. Result discipline

Copy `results/TEMPLATE.md` for each real benchmark. Record commit SHA, environment sizing, exact load profile, latency percentiles, error rate, and the first saturated dependency. Results from a laptop must not be represented as AWS/EKS capacity, and validated Terraform must not be represented as an executed cloud benchmark.

## CI scope

Normal CI **inspects** both k6 programs, syntax-checks the local resilience drills, and validates Prometheus alert rules. It intentionally does not generate benchmark traffic because CI runner performance is noisy and there is no production-like OAuth/database/Kafka target in the validation job. Correctness stress remains deterministic through Testcontainers.
