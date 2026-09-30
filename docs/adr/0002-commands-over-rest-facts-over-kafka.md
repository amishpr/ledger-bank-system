# 0002. Commands go over REST, facts go over Kafka

Status: accepted

## Context

Services need to talk to each other in two different ways. The recurring
transfer service needs the ledger to do something and needs to know whether it
worked. Other services just need to know that something already happened.

Doing everything over Kafka would make the scheduler send a command message,
wait for a reply event, and correlate the two, which is a saga for what is
really one request. Doing everything over REST would make the ledger call
every interested service after each posting, so it would have to know about
all of them and would slow down or fail when any of them did.

## Decision

- A **command**, where the caller needs the answer, is a synchronous REST call
  through a declarative HTTP client with timeouts, retries on transient errors
  and a circuit breaker. Retrying a posting is safe because every posting
  carries an idempotency key.
- A **fact**, something that already happened, is an event on Kafka:
  `account.created`, `transaction.posted`, `transaction.reversed`,
  `recurring-transfer.executed`, `recurring-transfer.failed`. The producer does
  not know who is listening.

## Consequences

- The ledger has no idea that insights, notifications or the scheduler's
  account replica exist. Adding a new consumer is a deployment, not a change to
  the ledger.
- Consumers are eventually consistent. The spending chart can lag a posting by
  a moment, and the API documentation says so.
- The scheduler is coupled to the ledger's availability at the moment it
  posts. That is handled by leaving the occurrence due and retrying on the next
  sweep with the same key, rather than by hiding the coupling.
