# Architecture Notes

## Phase 7 request and event flow

1. A client obtains a bearer access token from an external OAuth2/OIDC provider.
2. Payment Service acts as a stateless OAuth2 Resource Server and validates the JWT signature against the configured JWKS.
3. The token is also validated for issuer, audience, expiry/timing, and a non-empty subject no longer than the payment `customer_id` boundary.
4. `POST /api/v1/payments` requires `payments:write`; `GET /api/v1/payments/{id}` requires `payments:read`.
5. The JWT `sub` claim becomes the trusted customer identity. `customerId` is not accepted as trusted create-payment input.
6. Payment retrieval queries by both payment ID and authenticated customer ID. A resource owned by another customer therefore resolves as `404`.
7. Payment request idempotency is scoped by `(customer_id, idempotency_key)` in PostgreSQL and by a hashed customer/key namespace in Redis.
8. On a Redis miss or failure, Payment Service checks PostgreSQL for that authenticated customer's durable idempotency mapping.
9. If absent, Payment Service attempts `INSERT ... ON CONFLICT DO NOTHING` against the customer-scoped uniqueness constraint.
10. The winning insert creates the payment and `payments.created.v1` outbox record in the same transaction; a losing concurrent request loads the same customer's winner.
11. Redis is populated only after the payment/outbox transaction commits.
12. The outbox relay claims eligible rows in a short transaction using `FOR UPDATE SKIP LOCKED`, records a processing lease, and commits the claim before Kafka I/O.
13. Successful publication marks the event `PUBLISHED`; failure schedules bounded backoff or eventually marks it `FAILED`.
14. Transaction Service consumes `payments.created.v1`, commits its business transaction and `processed_events` marker together, and safely tolerates redelivery.
15. Retryable consumer failures receive two retries; exhausted or malformed events are routed to `payments.created.v1.DLT`.

## Security boundary

```text
OAuth2 / OIDC Provider
        |
        | publishes signing keys
        v
      JWKS
        |
        v
Payment Service resource server
        |
        +-- verify JWT signature
        +-- validate issuer
        +-- validate audience
        +-- validate exp / timing
        +-- validate subject
        |
        v
OAuth scope authorization
        |
        +-- payments:write -> POST /api/v1/payments
        +-- payments:read  -> GET  /api/v1/payments/{id}
        `-- ops:read       -> actuator metrics / prometheus
        |
        v
JWT sub -> customer ownership identity
```

The service does not issue tokens. Credential issuance, login, MFA, refresh tokens, and authorization grants belong to the external identity provider. Payment Service only validates bearer access tokens and enforces authorization at its boundary.

`/actuator/health` and `/actuator/info` remain public so infrastructure can probe the service without application credentials. Metrics and Prometheus endpoints require `ops:read` because they can reveal operational information.

## Customer ownership and request idempotency

```text
POST /api/v1/payments
Bearer JWT sub = customer-A
Idempotency-Key = checkout-42
        |
        v
Redis key = SHA-256(customer-A + separator + checkout-42)
        |
        +-- hit --> compare amount/currency --> return / 409
        |
        `-- miss
             |
             v
PostgreSQL lookup
WHERE customer_id = customer-A
  AND idempotency_key = checkout-42
             |
             +-- existing --> compare --> return / 409
             |
             `-- absent
                  |
                  v
INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING
```

This avoids both failure modes of a global idempotency key: one customer cannot collide with another customer's retry key, and cached results cannot leak across customer boundaries. Two customers may use the same textual key and receive independent payments.

For one customer, the key remains bound to the original payment semantics. Reusing it with a different amount or currency returns `409 Conflict`.

## Transactional outbox and multi-instance claiming

```text
payment transaction
  +-- payment row
  `-- PENDING outbox row
          |
          v
relay claim transaction
  SELECT eligible rows
  FOR UPDATE SKIP LOCKED
  mark PROCESSING
  set claimed_at
  increment attempts
COMMIT
          |
          v
Kafka send outside DB transaction
    |
    +-- success --> PUBLISHED
    `-- failure --> PENDING + backoff + last_error
                         |
                         `-- attempts exhausted --> FAILED
```

`SKIP LOCKED` lets multiple Payment Service instances poll concurrently without waiting on the same rows. A stale `PROCESSING` lease becomes claimable again if an instance dies after claiming. Kafka I/O remains outside the claim transaction so broker latency does not hold database locks open.

The delivery guarantee is at-least-once. If Kafka accepts an event and the process dies before PostgreSQL records `PUBLISHED`, the event can be sent again. Consumer idempotency is therefore part of the architecture, not an optional optimization.

## Consumer failure flow

```text
payments.created.v1
        |
        v
Transaction Service
        |
        +-- success ----------------------> commit offset
        |
        +-- retryable failure
        |      +--> retry 1 (1s)
        |      +--> retry 2 (1s)
        |      `--> payments.created.v1.DLT
        |
        `-- malformed payload ------------> payments.created.v1.DLT
```

Transaction Service stores the immutable event ID in `processed_events`. The business transaction row and processed-event marker commit together, with database uniqueness constraints providing an additional duplicate-write guard.

## Service data ownership

Payment Service owns the payment PostgreSQL database on local port `5432`. Transaction Service owns a separate PostgreSQL database on local port `5433`. Neither service reads the other's tables; Kafka is the integration boundary.

Within Payment Service, authenticated customer identity is enforced in repository queries. Redis is shared infrastructure but contains only an optimization of the customer-scoped idempotency result; PostgreSQL remains authoritative.

## Container-backed verification

The Maven suite exercises real Redis, PostgreSQL, and Kafka dependencies through Testcontainers and runs HTTP authorization checks through Spring Security's filter chain.

### Payment Service verification

The suite verifies:

- two simultaneous same-customer/same-key creates resolve to one payment and one outbox event;
- different customers can independently reuse the same textual idempotency key;
- same-customer/same-key/different-body requests return an idempotency conflict;
- rolled-back work does not populate Redis;
- the Phase 7 Flyway migration changes uniqueness to `(customer_id, idempotency_key)`;
- the native `FOR UPDATE SKIP LOCKED` claim query executes against PostgreSQL;
- unauthenticated payment creation receives `401`;
- authenticated requests with the wrong scope receive `403`;
- the JWT subject overrides/ignores spoofed customer identity in JSON;
- payment reads enforce ownership and return `404` to other customers;
- health is public while metrics require `ops:read`.

The MockMvc JWT tests exercise the authorization/filter-chain behavior without contacting an external identity provider. Production token signature and claim validation are provided by the configured Nimbus JWT decoder using the provider's JWKS, issuer, and audience settings.

### Transaction Service verification

A real Apache Kafka broker and PostgreSQL database verify duplicate delivery produces exactly one business transaction and malformed JSON reaches `payments.created.v1.DLT`.

## Security configuration contract

Production environments must provide:

- `JWT_ISSUER_URI` — expected token issuer
- `JWT_AUDIENCE` — required audience, default logical API name `payment-api`
- `JWT_JWK_SET_URI` — provider JWKS endpoint used for signature verification

The values checked into `application.yml` are development placeholders and are not credentials.

## Next milestones

The main remaining platform work is OpenAPI documentation, richer OpenTelemetry/Prometheus/Grafana observability, operational DLT replay, and deployment infrastructure such as Kubernetes/Helm and AWS architecture.
