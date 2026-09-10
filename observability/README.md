# Local Observability Stack

Phase 9 adds a local monitoring stack for the Event-Driven Payment Platform:

- **Prometheus** scrapes Micrometer metrics from both Spring Boot services.
- **Grafana** is provisioned with Prometheus and Tempo data sources plus a payment-platform dashboard.
- **Tempo** receives OTLP traces from the Payment Service and Transaction Service.

## Start infrastructure

```bash
docker compose --profile observability up -d
```

The observability services are available at:

- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`
- Tempo: `http://localhost:3200`
- OTLP HTTP receiver: `http://localhost:4318/v1/traces`

Grafana is configured for anonymous admin access **only for this local developer profile**. Do not copy that setting to a shared or production environment.

## Start the application services with local telemetry

Payment Service protects `/actuator/prometheus` with `ops:read` by default. The local Prometheus container does not have an OAuth2 token, so local development explicitly enables the public scrape endpoint:

```bash
export OBSERVABILITY_PUBLIC_PROMETHEUS=true
export OTEL_TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
export TRACING_SAMPLING_PROBABILITY=1.0
```

Then start both services:

```bash
mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl transaction-service
```

`OBSERVABILITY_PUBLIC_PROMETHEUS=true` is a local convenience switch. Leave it `false` in real deployments and give the Prometheus scraper an authenticated path instead.

## Metrics represented in the dashboard

The provisioned dashboard combines framework and domain-specific telemetry, including:

- Payment API request rate and p95 latency
- JVM heap usage
- outbox backlog by `PENDING`, `PROCESSING`, and `FAILED` status
- outbox Kafka publish success/failure rate and publish latency
- Transaction Service event outcomes (`received`, `created`, `duplicate`, `malformed`)
- transaction processing latency
- dead-letter publications

## Trace boundary

Kafka observation is enabled on the producer template and listener container so trace context can propagate through normal Kafka records. The transactional outbox intentionally persists only the business event payload today. Because the original HTTP request commits before the relay later reads the outbox row, the HTTP request trace and asynchronous relay trace should be treated as separate traces unless trace context is explicitly persisted with the outbox event in a future milestone.
