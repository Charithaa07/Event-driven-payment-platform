# Performance run result

> Copy this file for a measured run. Do not report targets as measured results.

## Test identity

- Date/time:
- Git commit SHA:
- Environment: local / isolated Kubernetes / AWS test environment
- k6 version:
- Script:
- Base URL:
- Service replica count:
- Database/Kafka/Redis sizing:

## Load profile

- Requested arrival rate or VUs:
- Duration:
- Retry ratio:
- Max VUs:

## Measured results

- Completed iterations:
- HTTP request failure rate:
- Create p50 / p95 / p99:
- Idempotent retry p50 / p95 / p99:
- Read p50 / p95 / p99:
- Idempotency mismatches:
- Highest outbox pending count:
- Highest oldest-outbox age:
- Transaction DLT events during run:
- Notification terminal failures during run:

## Capacity observations

- Hikari active/max connections:
- CPU / memory saturation:
- HPA replica changes (if Kubernetes):
- Kafka lag/backlog observations:
- Bottleneck identified:

## Conclusion

State what this run actually demonstrated, including the first resource that saturated. Avoid extrapolating beyond the tested environment and duration.
