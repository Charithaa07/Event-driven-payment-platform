# Kubernetes and Helm deployment

Phase 13 packages the four Spring Boot services as independently scalable Kubernetes workloads. The Helm chart deploys application compute only; PostgreSQL, Kafka, Redis, the OIDC/JWKS provider, and the OTLP collector are supplied as external endpoints. This keeps stateful infrastructure replaceable and maps cleanly to managed services in the AWS phase.

## What the chart provides

- one `Deployment` and internal `ClusterIP` `Service` for Payment, Transaction, Audit, and Notification
- `autoscaling/v2` HPAs with CPU targets and bounded scale-up/scale-down behavior
- rolling updates with `maxUnavailable: 0`
- startup, readiness, and liveness probes using Spring Boot Actuator health groups
- CPU/memory requests and limits
- a shared runtime `ConfigMap` for non-secret environment configuration
- database credentials loaded from an existing Kubernetes `Secret`
- non-root containers, dropped Linux capabilities, read-only root filesystems, `RuntimeDefault` seccomp, and no service-account token mount
- `/tmp` backed by `emptyDir` so the JVM can run with a read-only root filesystem
- topology spread across nodes and PodDisruptionBudgets
- graceful Spring shutdown settings
- optional provider-neutral `Ingress` routing for the four API prefixes

## Build the service images

The repository uses one hardened runtime Dockerfile for all four services. Build the Spring Boot jars first:

```bash
mvn --batch-mode -DskipTests package
```

Then build one image per service:

```bash
docker build --build-arg JAR_FILE=payment-service/target/payment-service-0.1.0-SNAPSHOT.jar --build-arg SERVICE_PORT=8080 -t ghcr.io/charithaa07/payment-service:0.13.0 .
docker build --build-arg JAR_FILE=transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar --build-arg SERVICE_PORT=8081 -t ghcr.io/charithaa07/transaction-service:0.13.0 .
docker build --build-arg JAR_FILE=audit-service/target/audit-service-0.1.0-SNAPSHOT.jar --build-arg SERVICE_PORT=8082 -t ghcr.io/charithaa07/audit-service:0.13.0 .
docker build --build-arg JAR_FILE=notification-service/target/notification-service-0.1.0-SNAPSHOT.jar --build-arg SERVICE_PORT=8083 -t ghcr.io/charithaa07/notification-service:0.13.0 .
```

Image publication is deliberately separate from this phase. Override `services.<name>.image.repository` and `tag` for the registry used by the target environment.

## Supply credentials

The chart does **not** commit or generate database passwords. Create the referenced secret through your deployment system, an external-secrets controller, or manually for a development cluster:

```bash
kubectl -n payments create secret generic payment-platform-secrets \
  --from-literal=payment-db-user='<username>' \
  --from-literal=payment-db-password='<password>' \
  --from-literal=transaction-db-user='<username>' \
  --from-literal=transaction-db-password='<password>' \
  --from-literal=audit-db-user='<username>' \
  --from-literal=audit-db-password='<password>' \
  --from-literal=notification-db-user='<username>' \
  --from-literal=notification-db-password='<password>'
```

Never commit the real secret values or a `values-secret.yaml` file.

## Render and deploy

Validate the chart without touching a cluster:

```bash
helm lint deploy/helm/payment-platform
helm template payment-platform deploy/helm/payment-platform --namespace payments > /tmp/payment-platform.yaml
```

Install after overriding external dependency endpoints and immutable image tags:

```bash
helm upgrade --install payment-platform deploy/helm/payment-platform \
  --namespace payments \
  --create-namespace \
  --set-string global.kafkaBootstrapServers='kafka.example.internal:9092' \
  --set-string global.redisHost='redis.example.internal' \
  --set-string global.jwtIssuerUri='https://issuer.example.com/' \
  --set-string global.jwtJwkSetUri='https://issuer.example.com/.well-known/jwks.json' \
  --set-string services.payment.database.url='jdbc:postgresql://payment-db.example.internal:5432/payments' \
  --set-string services.transaction.database.url='jdbc:postgresql://transaction-db.example.internal:5432/transactions' \
  --set-string services.audit.database.url='jdbc:postgresql://audit-db.example.internal:5432/audit' \
  --set-string services.notification.database.url='jdbc:postgresql://notification-db.example.internal:5432/notifications'
```

Use versioned or digest-pinned image tags for shared environments instead of `latest`.

## Runtime correctness notes

Kubernetes improves process availability but does not change the platform's messaging guarantees. Payment outbox publication remains at least once, Transaction/Audit/Notification consumers remain idempotent at their database boundaries, and notification provider delivery still depends on the stable provider idempotency key for crash-after-send recovery.

The HPA scales application replicas only. PostgreSQL connection limits, Kafka partition counts, consumer-group parallelism, and notification provider quotas remain independent scaling constraints and must be sized with the workload.
