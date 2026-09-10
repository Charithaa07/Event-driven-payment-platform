# DLT Recovery Runbook

## Purpose

`payments.created.v1.DLT` contains payment events that Transaction Service could not process after the configured retry policy. Phase 10 adds a durable operational index and a controlled replay path rather than requiring operators to use Kafka command-line tooling directly.

## Durable index

`DeadLetterIndexer` consumes the DLT with its own consumer group and inserts each Kafka position into `dead_letter_events`. The database uniqueness constraint on `(dlt_topic, dlt_partition, dlt_offset)` makes DLT indexing idempotent if the indexer receives the same record again.

When Spring Kafka failure headers are present, the index stores:

- original topic, partition, offset, and consumer group;
- DLT topic, partition, and offset;
- message key and payload;
- failure class and failure message;
- recovery status, replay attempts, operator identity, timestamps, and last replay error.

## Authorization

Transaction Service is an OAuth2 resource server for operational endpoints.

| Endpoint | Required scope |
| --- | --- |
| `GET /api/v1/operations/dlt?status=PENDING` | `ops:read` |
| `GET /api/v1/operations/dlt/{eventId}` | `ops:read` |
| `POST /api/v1/operations/dlt/{eventId}/replay` | `ops:write` |

The list response omits the event payload to reduce unnecessary exposure. The detail endpoint returns the payload for an operator who explicitly inspects one record.

## Replay state machine

```text
PENDING -----> REPLAYING -----> REPLAYED
   ^               |
   |               |
   +---- FAILED <--+
```

A replay request first performs a conditional database update. Only `PENDING`, `FAILED`, or a stale `REPLAYING` row can be claimed. The claim records the authenticated operator subject and increments `replay_attempts`.

Kafka publication happens **after the claim transaction commits** so broker latency never holds a PostgreSQL transaction or row lock open.

On successful broker acknowledgement, the row becomes `REPLAYED`. On send failure or timeout it becomes `FAILED` with `last_replay_error`, and an operator may retry later.

A `REPLAYING` claim older than 30 seconds is treated as stale and may be reclaimed. This prevents an application crash between claim and completion from permanently stranding the record.

## Delivery semantics

Replay is intentionally **at least once**, not end-to-end exactly once.

A process can publish the event successfully and then die before updating the recovery row to `REPLAYED`. After the stale-claim window, the record can be replayed again. This is safe because the Transaction Service business consumer already stores the immutable payment event ID in `processed_events` and makes duplicate delivery a no-op.

Once PostgreSQL records a recovery row as `REPLAYED`, repeating the replay API call returns the existing recovery result and does not intentionally send the event again.

## Poison events

A deterministic malformed payload will fail again if replayed unchanged. Operators should inspect `failureClass`, `failureMessage`, and `payload`, correct the underlying producer/data/application issue, and only then decide whether replay is appropriate.

Phase 10 deliberately does not offer arbitrary payload editing through the replay endpoint. Mutating financial event payloads during recovery would require a separate audited correction workflow rather than silently changing the original dead-letter record.

## Metrics

Prometheus exposes:

- `transactions.kafka.dlt` — records published to the DLT;
- `transactions.kafka.dlt.indexed` — records durably indexed;
- `transactions.kafka.dlt.backlog` — `PENDING + FAILED` recovery backlog;
- `transactions.kafka.dlt.replay{outcome=success|failure}` — replay outcomes.

These metrics let an operator distinguish message-processing failures from recovery backlog and replay health.
