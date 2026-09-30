# 0001. Split the backend into five services along ownership lines

Status: accepted

## Context

The original backend was one Node process that served the REST API, held the
WebSocket connections, and ran the recurring transfer scheduler on a timer.
That was the right size for the app, and one process is easier to run than
five. The goal of this rewrite is different, though: to show the structure a
bank's backend actually has, where those jobs have different owners, scale
differently, and fail independently.

## Decision

Split by who owns the data and how each part scales, not by technical layer:

- **ledger-service** owns accounts, transactions, entries and the audit log.
  It is the only writer of money.
- **recurring-transfer-service** owns schedules and the background sweep,
  which must run on exactly one instance at a time.
- **insights-service** owns a read model built from events.
- **notification-service** owns nothing but open sockets, which scale with
  connected browsers, not with requests.
- **api-gateway** is the one public address.

Each service has its own database. None reads another's tables.

## Consequences

- A failure stays where it starts: the dashboard keeps working when insights is
  down, and schedules keep listing when the ledger is down.
- Work that was a function call is now a network call or an event, so it needs
  timeouts, retries, idempotency and eventual consistency. Most of the code in
  `platform` exists because of this decision.
- Running locally means six JVMs. `dev-infra` and Docker Compose keep that to one
  command, but it is heavier than the old `npm run dev`.
- For an app this size a modular monolith would be the honest production
  choice. The split is here to demonstrate the patterns, and the boundaries are
  drawn where a real team would draw them.
