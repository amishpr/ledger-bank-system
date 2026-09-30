# 0005. Run integration tests against embedded Postgres and Kafka

Status: accepted

## Context

Integration tests need a real Postgres, because row locks, `SKIP LOCKED`,
triggers and window functions behave differently or do not exist in H2. They
need a real Kafka broker to test the outbox, consumers and dead letter topics.
Testcontainers is the usual answer, but it needs Docker, and the machine this
was built on runs macOS 12 without it. A suite that only runs on some machines
does not get run.

## Decision

- Postgres comes from zonky's embedded binaries: a real Postgres 17 server
  started once per test JVM, with a separate database per service, the same
  split as production.
- Kafka is a real KRaft broker started in the test JVM by Spring Kafka's
  `@EmbeddedKafka`.
- Both live in the `test-support` module, so every service uses them the same
  way. `./mvnw verify` needs nothing installed but a JDK, locally and in CI.

## Consequences

- No mocks for the database or the broker in integration tests, and no Docker
  dependency.
- These are the same engines, not the same deployments: no network latency, one
  broker instead of three. Tests that depend on replication or partition
  rebalancing would still need a containerised or staging environment.
- The same two pieces power `dev-infra`, the one-command local environment for
  machines without Docker.
