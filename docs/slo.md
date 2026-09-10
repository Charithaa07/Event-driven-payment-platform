# Service-level objectives and resilience signals

These are **reference engineering objectives** for the Event-Driven Payment Platform. They define what a production-like environment should measure; they are not claims about an AWS environment that has not been provisioned and load-tested.

## Objectives

| Capability | SLI | Reference objective | Primary signals |
| --- | --- | --- | --- |
| Payment API availability | non-5xx responses / total API responses | >= 99.9% over 30 days | `http_server_requests_seconds_count` |
| Payment API latency | server-side request duration | p95 < 500 ms, p99 < 1 s | `http_server_requests_seconds_bucket` |
| Outbox freshness | age of oldest unpublished payment event | < 30 s during steady state | `payments_outbox_oldest_age_seconds` |
| Outbox durability | terminal failed outbox rows | 0 sustained | `payments_outbox_events{status="failed"}` |
| Transaction recovery | records routed to transaction DLT | 0 in healthy steady state | `transactions_kafka_dlt_total` |
| Notification delivery | terminal provider failures | < 1% of delivery attempts over 30 days | `notifications_delivery_attempts_total{outcome="failed"}` |
| Database headroom | active Hikari connections / configured max | < 85% sustained | `hikaricp_connections_active`, `hikaricp_connections_max` |

Client-caused 4xx responses are excluded from the availability error numerator. Authentication/authorization failures should be analyzed independently rather than counted as service outages.

## Alert philosophy

Prometheus rules in `observability/prometheus/alerts/payment-platform.yml` use short windows as operational early-warning signals. An alert is not automatically an SLO breach: a 30-day SLO and a 5- or 10-minute alert serve different purposes.

- API latency/error alerts identify sustained customer-facing degradation.
- Outbox age is preferred over raw queue length for event freshness because load volume can change dramatically.
- DLT activity is a warning that retry recovery was exhausted or a poison record appeared; it should trigger inspection, not automatic destructive replay.
- Hikari saturation indicates database capacity pressure before requests necessarily fail.
- Notification terminal failures separate permanent/exhausted delivery from `retry_scheduled`, which is expected transient-recovery behavior.

## Performance test gates

The k6 profiles define per-run thresholds for failure rate, correctness checks, and request latency. These thresholds are useful regression gates for a controlled test environment, but CI only performs script validation. Benchmark results should be committed separately using `performance/results/TEMPLATE.md` and include the exact commit/environment/load profile.

## Capacity and scaling interpretation

Horizontal Pod Autoscaling can add service replicas when CPU rises, but it cannot increase every downstream capacity boundary. Kafka partition count constrains consumer parallelism; PostgreSQL connection and I/O capacity constrain database work; Redis and provider quotas constrain their respective edges. A capacity report should therefore identify the **first saturated dependency**, not merely the highest request rate observed.

## Failure drills

Failure injection should be restricted to disposable/local environments. The included Redis drill verifies the architectural invariant that Redis is a fast-path optimization and PostgreSQL remains authoritative. Future drills can add broker latency, provider timeouts, or pod termination only when they are isolated and measurable.
