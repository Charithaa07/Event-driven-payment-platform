# Local Observability Stack

The local monitoring stack for the Event-Driven Payment Platform includes:

- **Prometheus** scraping Micrometer metrics from Payment, Transaction, and Audit services.
- **Grafana** provisioned with Prometheus and Tempo data sources plus the payment-platform dashboard.
- **Tempo** receiving OTLP traces from all three Spring Boot services when tracing export is enabled.

## Start infrastructure

```bash
docker compose --profile observability up -d
```

The observability services are available at:

- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`
- Tempo: `http://localhost:3200`
- OTLP HTTP receiver: `http://localhost:4318/v1/traces`

Grafana anonymous admin access exists **only for this local developer profile** and should not be copied to a shared or production environment.

## Start the application services with local telemetry

Application services protect `/actuator/prometheus` with `ops:read` by default. The local Prometheus container does not carry an OAuth token, so local development explicitly enables the public scrape endpoint:

```bash
export OBSERVABILITY_PUBLIC_PROMETHEUS=true
export OTEL_TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
export TRACING_SAMPLING_PROBABILITY=1.0
```

Then start the services:

```bash
mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl transaction-service
mvn spring-boot:run -pl audit-service
```

Prometheus scrapes:

- Payment Service on `host.docker.internal:8080`
- Transaction Service on `host.docker.internal:8081`
- Audit Service on `host.docker.internal:8082`

`OBSERVABILITY_PUBLIC_PROMETHEUS=true` is a local convenience switch. Leave it `false` in real deployments and give the scraper an authenticated or otherwise protected path.

## Platform metrics

The services expose framework and domain telemetry including:

- Payment API request rate and latency
- JVM/runtime and datasource metrics
- outbox backlog, Kafka publish outcomes, and publish latency
- Transaction Service event outcomes and processing latency
- DLT publication, durable indexing, recovery backlog, and replay outcomes
- Audit Service ingestion outcomes through `audit.payment.events{outcome=received|stored|duplicate|malformed|dead_lettered}`

The existing dashboard covers core API/outbox/transaction signals. Audit metrics are immediately queryable in Prometheus and can be added to future Grafana panels without changing application instrumentation.

## Trace boundary

Kafka observation is enabled so context can propagate through normal Kafka records. The transactional outbox still stores only the business payload, not the original HTTP W3C trace context. Because the payment request commits before the scheduled relay publishes later, the original HTTP trace and the outbox-relay trace remain separate unless trace context is explicitly persisted with the outbox event in a future milestone.
