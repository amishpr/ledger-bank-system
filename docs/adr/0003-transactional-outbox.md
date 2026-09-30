# 0003. Publish events through a transactional outbox

Status: accepted

## Context

After a posting commits, the ledger must publish `transaction.posted`. Writing
to Postgres and then sending to Kafka are two separate systems, so there is no
single transaction around both. If the process stops between them, the
database has a posting that Kafka never hears about. If the order is swapped,
Kafka can announce a posting that then rolls back.

## Decision

Write the event to an `outbox_event` table in the same database transaction
as the change it describes. A relay in the same service publishes unpublished
rows to Kafka, waits for the broker's acknowledgement, and only then marks
them published. Rows are claimed with `FOR UPDATE SKIP LOCKED` so any number of
instances can relay without taking the same row. The relay backs off when
Kafka is down and the backlog is exported as a metric
(`ledger.outbox.pending`). The writer requires an existing transaction and
fails without one.

The outbox lives in the shared `platform` module as auto-configuration, so the
ledger and recurring transfer services use the identical implementation.

## Consequences

- An event is published if and only if its change committed.
- Delivery is at least once: a relay that crashes after sending and before
  marking can send a row again. Every consumer therefore deduplicates, by event
  id (insights) or by being naturally idempotent (the account replica's upsert).
- Events arrive a few hundred milliseconds after the commit rather than at
  once. Change data capture (Debezium reading the write-ahead log) would cut
  that and remove the polling, at the cost of running Kafka Connect.
- The trace context is stored with each row and restored when it is published,
  so one trace covers the HTTP request, the database write and the consumer.
