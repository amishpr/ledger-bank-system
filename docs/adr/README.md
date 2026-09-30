# Architecture decision records

Short notes on the decisions that shaped the backend, written when they were
made. Each one says what the situation was, what was decided, and what that
costs. A decision that is later reversed gets a new record rather than an edit,
for the same reason the ledger posts reversals instead of editing history.

| # | Decision |
| --- | --- |
| [0001](0001-split-into-services.md) | Split the backend into five services along ownership lines |
| [0002](0002-commands-over-rest-facts-over-kafka.md) | Commands go over REST, facts go over Kafka |
| [0003](0003-transactional-outbox.md) | Publish events through a transactional outbox |
| [0004](0004-database-enforces-ledger-rules.md) | Let the database enforce the ledger's rules too |
| [0005](0005-tests-without-docker.md) | Run integration tests against embedded Postgres and Kafka |
| [0006](0006-no-discovery-or-config-server.md) | No service discovery or config server |
