# Project walkthrough

This document is meant to help explain the project out loud, for example in an interview. It
covers what the app does, why it was built the way it was, the reasoning behind each technology
choice, the interesting technical problems that came up, and the bugs that got caught along the
way.

## The short version

Ledger is a double entry ledger, the same kind of system that sits underneath a bank account or a
product like Ramp or Brex. The backend is five Java and Spring Boot services behind a Spring Cloud
Gateway. They talk to each other over REST when one needs an answer and over Kafka when one is
announcing something that already happened, each keeps its own Postgres database, and a React
dashboard updates in real time over a WebSocket.

The interesting part is not the CRUD. Every transaction is checked for balance before it is
written, money is never a floating point number, a retried request cannot create a duplicate
transfer, and corrections are made by reversing a transaction instead of editing it. The database
itself refuses to change a posted entry. Events leave each service through a transactional outbox,
so a change and its announcement can never disagree. Recurring transfers run from a background
sweep that only one instance runs at a time. And the whole test suite, real Postgres and real
Kafka included, runs with nothing installed but a JDK.

## Why a ledger

Many portfolio projects are a stock price predictor or a crypto price tracker. Those show that
someone can call an API and plot a chart, but they say little about how the author thinks about
correctness. A ledger is different. Almost every fintech company has one somewhere in its stack,
whether that is a bank's core system, a corporate card company tracking spend, or a payments
company tracking who owes whom. It is also a common system design interview topic, so having built
one gives a concrete example to point to instead of describing it in the abstract.

## The architecture

```
Browser (React dashboard)
   |  REST /api/v1/**                        WebSocket /ws
   v
api-gateway :4000   (Spring Cloud Gateway: routing, CORS, rate limit, JWT, Swagger UI)
   |-- /api/v1/accounts, /transactions  ->  ledger-service :8081               -> Postgres "ledger"
   |-- /api/v1/recurring-transfers      ->  recurring-transfer-service :8082   -> Postgres "recurring"
   |-- /api/v1/insights                 ->  insights-service :8083             -> Postgres "insights"
   '-- /ws                              ->  notification-service :8084         (sockets only)

Kafka topics
   ledger.accounts.v1        account.created                    ledger -> recurring (account replica)
   ledger.transactions.v1    transaction.posted / .reversed     ledger -> insights, notifications
   recurring.transfers.v1    recurring-transfer.executed / ...  recurring -> notifications
```

The split follows ownership, not technical layers (see [ADR 0001](docs/adr/0001-split-into-services.md)).
The ledger service is the only thing that ever writes money. The recurring transfer service owns
schedules and the background job. The insights service owns a read model of spending. The
notification service owns nothing but open browser sockets, which scale with how many dashboards
are open rather than how many requests arrive. The gateway is the only public address.

Services talk two ways ([ADR 0002](docs/adr/0002-commands-over-rest-facts-over-kafka.md)). When
one needs something done and needs to know whether it worked, that is a command, and it is a REST
call: the scheduler asks the ledger to post a transfer. When one announces something that already
happened, that is a fact, and it is a Kafka event: the ledger announces that a transaction was
posted, and whoever cares reacts. The ledger does not know insights or notifications exist.

Shared plumbing lives in a `platform` module written as Spring Boot auto-configuration, the way a
company's internal "service chassis" works. Every service gets the same error format, the same
cents parsing, the same outbox, and the same Kafka retry policy by adding one dependency.

## A transfer from start to finish

This trace covers what happens when someone moves money between two accounts.

The user fills out the transfer form and picks a from account, a to account, and an amount like ten
dollars. The frontend converts "10.00" into the integer 1000 with a small parsing function, so a
whole number of cents is what gets sent, as the string `"1000"`. It also generates a random
idempotency key with `crypto.randomUUID` and sends it in the `Idempotency-Key` header.

The request reaches the gateway at `POST /api/v1/transactions`. The gateway checks CORS, spends a
token from that client's rate limit bucket (writes only), starts a trace, and forwards the request
to the ledger service.

In the ledger service, Spring MVC binds the JSON to a Java record and runs Bean Validation on it. The
amount field is a small type called `MinorUnits` that accepts `1000` or `"1000"` and rejects
everything else, including `10.5`, `"-5"` and `"1e3"`, so a float can never quietly become money. A
bad request gets a 400 in RFC 9457 Problem Details form, with a stable `code` and a list of which
fields were wrong.

The controller hands a command to `PostingService`, which checks the rules that need no database:
at least two entries, every amount positive, debits equal to credits. If an idempotency key was
sent, it looks for an earlier transaction with that key. Same key and same request body (compared by
a SHA-256 fingerprint) returns the original transaction with a 200 and an `Idempotent-Replayed:
true` header. Same key with a different body is a 409 conflict.

A new transaction then goes to `Journal`, the one class that ever writes entries, inside a database
transaction. It locks every account involved with `SELECT ... FOR UPDATE`, always in id order so two
transfers between the same accounts in opposite directions cannot deadlock. With the locks held it
confirms the accounts exist and share a currency, sums the current balance of each asset account,
and refuses anything that would take one below zero. Then it inserts the transaction and its entries,
writes an audit log row, and writes a `transaction.posted` event to the outbox table, all in the
same commit.

The response goes back through the gateway with a `Location` header, a 201, and an `X-Trace-Id`
header. A moment later the outbox relay picks up the event, publishes it to Kafka, and marks it
sent. The insights service adds the entries to its spending totals. The notification service turns
it into the exact message the dashboard expects and pushes it to every open socket, through the
gateway. Every open dashboard, including the one that made the request, refetches the affected
balances, which is why another browser tab updates without a refresh.

## How the recurring transfer service works

This is the part of the project that shows a background job, not only a request and response.

A schedule says which two accounts, how much, how often (every minute for demos, daily, weekly or
monthly) and when it is next due. Creating one moves no money. The service checks that both accounts
are asset accounts by looking them up in its own copy of the ledger's accounts, an `account_replica`
table kept current by consuming `account.created` events. That replica is why listing schedules keeps
working when the ledger is down. An account opened a second ago may not have arrived yet, so on a
miss the service asks the ledger once over REST and stores the answer.

A `@Scheduled` sweep runs every fifteen seconds behind a ShedLock lock in the service's database, so
however many copies of the service are running, only one sweeps at a time. For each due schedule it
derives an idempotency key from the schedule's id and the exact due time, for example
`recurring:0190c...:2026-01-01T00:00:00Z`, and posts the transfer through the ledger's public REST
API with that key, through a client that retries transient failures and has a circuit breaker.

The derived key is what makes this safe. A retry after a timeout, a restart in the middle of a sweep,
or two sweeps racing all send the same key, and the ledger returns the original transaction instead
of posting a second one. The job does not have to be perfectly reliable for the money to be right.

What happens next depends on the answer:

- **Posted.** The run is recorded as a success, the next due time moves forward, and a
  `recurring-transfer.executed` event goes out through this service's own outbox.
- **Rejected**, for example insufficient funds. The failure is recorded, the occurrence is skipped,
  and a `recurring-transfer.failed` event goes out so the dashboard can show it. Retrying would only
  fail the same way every fifteen seconds.
- **Unavailable**: the ledger timed out, answered 5xx, or the circuit is open. The occurrence stays
  due and the next sweep tries again with the same key. This is an improvement on the first version,
  which skipped the occurrence whether the problem was the customer's balance or the server's health.

The ledger call happens outside any database transaction, so no connection or row lock is held while
waiting on the network. Recording the outcome is a separate short transaction that reloads the
schedule and checks it was not paused, deleted or advanced in the meantime. A `@Version` column
catches the case where someone pauses a schedule in the same instant.

## The data model

Each service owns its own tables in its own database.

**Ledger:** `account`, `ledger_transaction`, `journal_entry`, `audit_log`, `outbox_event`.

An account has a type, which is one of asset, liability, equity, revenue, or expense. The type
decides which direction of entry increases the balance. For an asset or an expense account, a debit
increases the balance and a credit decreases it. For a liability, equity, or revenue account it is
the reverse. That rule lives in one place, the `AccountType` enum, and everything else uses it.

For example, a checking account is an asset. Paying rent out of it is a credit to checking (its
balance goes down) and a debit to the rent expense account (its balance goes up). An opening balance
works the other way around: checking is debited and the equity account that funds it is credited,
and that credit increases the equity balance. It feels backwards at first, but it stops feeling that
way once the rule is applied consistently.

A ledger transaction is one event, like a transfer or a fee. It has a description, an optional
idempotency key with the fingerprint of the request that used it, a status of POSTED or VOIDED, an
optional origin (for example "recurring transfer" plus the schedule's id), and for a reversal, the
transaction it reverses.

A journal entry is one leg of a transaction: a debit or a credit of a positive amount against one
account. A transfer between two accounts is always exactly two entries for the same amount. That is
what "double entry" means. Each entry also gets a `seq` number from an identity column, the journal
sequence, which orders entries written in the same instant.

Balances are never stored. A balance is always the sum of an account's entries, so there is exactly
one source of truth for how much money is in an account: its history.

**Recurring transfers:** `recurring_transfer`, `account_replica`, `shedlock`, `outbox_event`.

**Insights:** `spending_by_category`, `spending_by_month`, `processed_event`. All of it can be thrown
away and rebuilt by replaying the transactions topic.

## Events and the outbox

A service must never write to its database and then publish to Kafka as two separate steps. If it
stops in between, the database has a change Kafka never hears about. If the order is swapped, Kafka
announces a change that then rolls back. Both happen eventually at any real volume.

So events are written to an `outbox_event` table in the same database transaction as the change
([ADR 0003](docs/adr/0003-transactional-outbox.md)). A relay publishes unpublished rows, waits for
Kafka to acknowledge them, and only then marks them sent. It claims rows with `FOR UPDATE SKIP
LOCKED`, so several instances can relay without taking the same row, and it backs off exponentially
when Kafka is down instead of hammering it. The number of unpublished rows is a metric, and it is
the one to alert on, because it only grows when something is wrong.

Delivery is therefore at least once: a relay that crashes after sending but before marking will send
again. Every consumer is built for that. Insights inserts each event id into a `processed_event` table
in the same transaction as the projection update, so a duplicate is skipped. The account replica is
an upsert, so applying an event twice leaves the same row. The notification service does not care,
since a repeated "refresh your balances" message is harmless.

Each event is a versioned record in the `event-contracts` module with an `eventType`, an `eventId`, a
`schemaVersion` and a timestamp. The rules for a published version are: never remove or rename a
field, only add optional ones, and move to a new topic (`.v2`) for anything else. Consumers ignore
fields they do not recognise. The notification service never forwards internal events to browsers
as they are; it maps them to the browser's own message format, so internal contracts can grow
without the dashboard noticing.

A record a consumer cannot read (malformed JSON, an unknown event type) goes straight to that
consumer's dead letter topic, for example `ledger.transactions.v1.insights-service.dlt`, with the
error in its headers. A record that fails for a transient reason is retried with exponential
backoff first. Either way, one bad record never blocks the records behind it.

## Design decisions and why they were made

**Money is whole cents in a `long`, never a float or a decimal.** A binary floating point number
cannot represent most decimal fractions exactly, which is why `0.1 + 0.2` does not equal `0.3`.
Every amount in this project, from the database column (`BIGINT`) to the JSON on the wire, is a whole
number of cents. JSON carries it as a string, because a JavaScript number cannot represent every
integer above 2^53. Requests go through the `MinorUnits` type described above. A total that would
overflow a `long` is rejected with `Math.addExact` rather than allowed to wrap around. Floats appear
only where they are harmless, such as positioning points on a chart or animating a count up.

**Every transaction must balance before it is written.** This is enforced in code, not assumed. If
the debits and credits do not add up to the same total, the request is refused before anything
touches the database. This is the core rule of double entry accounting and the reason to build this
instead of a system that increments and decrements a number on an account.

**Idempotency keys prevent duplicate transfers on retry.** A client can send a request, lose the
response to a network timeout, and not know whether it succeeded. If it naively retries, someone
could be charged twice. This follows the pattern Stripe's API uses: the client sends a key in the
`Idempotency-Key` header, and the same key again returns the original result. The first version had
a race the new one closes: two requests with the same key arriving at the same moment could both
pass the "seen this key?" check. Now the unique index on the key decides, the second insert fails,
and that request returns whatever the first one posted. A test fires eight identical requests at once
and checks that exactly one transaction exists.

**Nothing is ever edited or deleted, and the database enforces it.** To correct a transaction, the
system posts a new transaction with every entry flipped and marks the original as voided. That much
was true before. Now a database trigger also rejects any update or delete on an entry, and any change
to a transaction other than POSTED becoming VOIDED ([ADR 0004](docs/adr/0004-database-enforces-ledger-rules.md)).
A bug, a careless migration, or someone with a SQL prompt cannot rewrite history, and a test proves it
by trying.

**Overdraft protection holds under real concurrency, using row locks.** The first version noted that
SQLite serialises all writers and that Postgres would need `SELECT ... FOR UPDATE`. That is now done:
a posting locks the accounts it touches, in id order to avoid deadlocks, before reading their
balances. A test sends twenty simultaneous withdrawals of $1 against a $10 balance and checks that
exactly ten succeed and the balance ends at zero.

**Statements compute running balances in SQL with a window function.** The first version replayed an
account's whole history in application memory on every request. The statement now comes from one
query, `SUM(...) OVER (ORDER BY created_at, seq)`, answered from a covering index, and the limit is
applied after the window so the oldest line shown still has the right running balance. At bank
scale the next step would be a balance snapshot per account updated inside the posting transaction,
with these sums kept as the reconciliation check.

**The recurring sweep has one owner.** The first version admitted that two copies of the server
would both sweep. ShedLock now gives the sweep a lock in the database, using the database's clock so
instances with drifting clocks still agree. The derived idempotency key is still there underneath as
the safety net. A test runs two sweeps at the same moment and checks that only one reached the ledger.

**Monthly schedules count from an anchor date.** A month is not 43,200 minutes, so the next run is
calendar arithmetic in the schedule's time zone. The first version added one month to the previous
run, which in JavaScript rolls January 31 into early March. Java's `plusMonths` clamps to February 28
instead, but then adding a month to February 28 gives March 28, and the schedule drifts to the 28th
for good. So monthly runs are counted from the first occurrence: Jan 31, Feb 28, Mar 31, Apr 30.
Daily schedules keep the same wall clock time across a daylight saving change.

**Timestamps are whole milliseconds everywhere.** The services share a `Clock` that ticks in
milliseconds. Java can produce nanoseconds, Postgres stores microseconds, and JavaScript has
milliseconds, so without this a timestamp could read back from the database slightly different
from how it was written. That matters because the scheduler's idempotency key contains the due time:
if it changed between the first attempt and a retry, the ledger would see a new key and post twice.

**Ids are time ordered UUIDs (version 7).** The first 48 bits are the creation time, so new rows land
at the end of a B-tree index instead of scattered through it, and rows created in the same
millisecond still sort in creation order because of a counter in the next 12 bits.

**Errors are RFC 9457 Problem Details with a stable code.** Every service, and the gateway itself,
answers errors in the same `application/problem+json` shape with a `code` such as
`INSUFFICIENT_FUNDS` that a client can switch on, and a `traceId` that finds the request in Jaeger.
The codes match the original API one for one, so the dashboard needed only its error parser changed.
Unexpected errors are logged in full and returned as a generic 500, so a stack trace or a database
message never reaches a client.

**The gateway is the only public address.** It routes by path, so the dashboard has one base URL.
It answers CORS for the dashboard's origin only and exposes exactly the headers the dashboard reads,
such as `Content-Disposition`, which carries the CSV filename. It rate limits writes per client with
a token bucket, keyed by the authenticated user or the connection's address and never by an
`X-Forwarded-For` header a client can fake. It adds security headers. It can require a JWT with
`ledger.read` or `ledger.write` scopes, switched off by default because the dashboard has no login.
It also serves one Swagger UI with a dropdown for every service's OpenAPI document.

**Trace context crosses Kafka.** Every request is traced with Micrometer and OpenTelemetry. The trace
context is saved with each outbox row and restored when the relay publishes it, so a single trace in
Jaeger runs from the browser's request through the database write and Kafka to the consumer that
reacted to it. Logs carry the trace id, and in containers they are structured JSON.

**Calls between services expect failure.** The scheduler's ledger client has connect and read
timeouts, retries only transient failures (refused connections, timeouts, 5xx, 429) with jittered
exponential backoff, and wraps them in a Resilience4j circuit breaker. A 4xx is the ledger saying no,
so it is neither retried nor counted against the ledger's health. Retrying a POST is safe here only
because every posting carries an idempotency key, which is worth saying explicitly whenever retries
come up.

**The demo data is a year of history generated with a seeded random number generator.** The seeder
builds about 240 transactions spread across real calendar dates over the past year: biweekly
paychecks, weekly groceries, monthly rent, and so on. Amounts come from a `mulberry32` generator
instead of true randomness, so every run produces the exact same year. The Java seeder is a port of
the original TypeScript one, call for call, and a test checks the Java generator returns the same
numbers as the TypeScript one. The result is that a local run and the hosted demo show the same
balances to the cent.

**Backdating the demo data is allowed in exactly one place.** The API never accepts a timestamp from
a client, which stops anyone from lying about when money moved. The seeder still needs realistically
dated history, so `PostingService` has a `postBackdated` method that runs every normal rule and only
differs in taking the time. An ArchUnit test fails the build if anything outside the `seed` package
calls it.

**Architecture rules are tests.** ArchUnit checks that layers only depend downwards (web on
application, application on repository and domain), that the domain does not depend on Spring web or
JDBC classes, that nothing uses field injection, and the backdating rule above. Rules that only live
in a reviewer's head get broken the first week someone new joins.

**The charts and the CSV export were built without adding a dependency.** The spending breakdown is
plain HTML bars sized by percentage. The balance history chart is a hand written SVG line and area
chart with rounded axis ticks, a crosshair, and a tooltip that follows the pointer. The CSV is built
on the server by a small class that escapes commas, quotes and newlines and joins rows with the
standard CRLF line ending. It also prefixes a description starting with `=`, `+`, `-` or `@` with an
apostrophe, because a spreadsheet would otherwise run it as a formula when the file is opened. Each of
these is simple enough that a dependency would have cost more than it saved.

**The balance chart and the statement table deliberately do not share one data window.** The table
stays capped at forty rows and reads as recent activity, the chart fetches a much larger window so
the full year is visible, and the CSV export, which contains an account's whole history, keeps every
charted value reachable outside the chart, which matters for accessibility.

**A sixth expense category would have silently broken the chart colors, so extras fold into
"Other".** The spending chart assigns one color per category from a palette validated for colorblind
separation, with five slots. The seed data has six expense categories. Cycling back to the first color
would make two unrelated categories look identical, so the chart keeps the top four and sums the rest
into one muted "Other" bar.

**The dashboard talks to an interface, which is what makes a free hosted demo possible.** The real
backend is five JVMs, Postgres and Kafka, which no free static host can run. Because every component
talks to a small `LedgerApi` interface rather than calling `fetch` directly, a second implementation
keeps its tables in memory in the browser: the same balance check, overdraft protection, idempotency
keys, reversals, calendar math and recurring transfer sweep, running inside the tab. One line picks
which implementation the app gets, based on a build flag. The demo seeds itself with the same seeded
generator, which is why its numbers match a local run.

**Two implementations of the same rules can drift, so both are pinned to the same cases.** The
in-browser backend is a port, not shared code, and it is now a port in a different language. The
mitigation is that the test cases match: the original Node suite was copied case for case into the
demo's tests, and the same cases now run in the Java integration tests. If either side stops
rejecting an unbalanced transaction, blocking an overdraft, or replaying an idempotency key, a test
fails.

**The demo says it is a demo.** A dashboard showing a green "Live" indicator and a year of real
looking history could easily be mistaken for a deployed product. The demo build carries a banner
saying everything runs in the browser tab and nothing is saved.

## Frontend and visual design decisions

The dashboard is styled as a calm, dense operations screen: Geist and Geist Mono, one accent color,
tabular numbers, and restrained motion. All colors are CSS custom properties, with the dark navy theme
as the default and a light theme defined as an override under `data-theme="light"`. The chosen theme
is stored in `localStorage`.

Two color rules keep the interface readable. First, the positive and negative colors mean a balance
went up or down, not that an entry was a debit or a credit. Since a credit increases a liability or
revenue account, coloring by literal debit and credit would show a good change as a bad one for
those account types. Second, anything a user can click carries the accent color even at rest, with
the negative color reserved for destructive actions like canceling a recurring transfer.

## Why each technology was chosen

**Java 21 and Spring Boot 4.1.** Java is what most banks run their backends on, and Spring Boot is
the default way to build Java services. Java 21 is the current long term support release that large
companies standardise on, and it brings records (every DTO and event here is one), sealed interfaces
(the event hierarchy, so a `switch` over events must handle every type or fail to compile), pattern
matching, and virtual threads, which are switched on for every service. Boot 4.1 is the current
release and comes with Jackson 3, Hibernate 7 and Spring Framework 7.

**Spring Cloud Gateway (2025.1).** The standard Spring edge. The WebFlux version was picked over the
servlet one because it can proxy WebSockets, which the live updates need.

**Postgres 17, one database per service.** Row locks, `SKIP LOCKED`, triggers, `jsonb` for audit and
outbox payloads, and window functions all get used. A service owning its database is what makes it
independently deployable; sharing one would quietly couple them through the schema.

**Spring Data JPA and Hibernate for aggregates, `JdbcClient` for reports.** Entities with behaviour
(a transaction that can be voided, a schedule that can record a run) suit JPA. Balance sums,
statements and the insights projection are set based SQL, which is clearer as SQL. The insights
service uses no JPA at all.

**Flyway** for versioned migrations. Hibernate only validates the schema against the entities and
never changes it.

**Kafka (KRaft, no ZooKeeper) through Spring Kafka.** The standard event backbone at large banks.
Events are plain JSON written with the application's own JSON mapper, so what is on the wire is
visible and tested rather than hidden in a serializer.

**Resilience4j** for retry and the circuit breaker on the one synchronous dependency, with its metrics
exported to Prometheus.

**ShedLock** for the scheduler lock. A job queue such as Quartz or a message-driven worker would also
work; a single lock row is the smallest thing that gives the sweep one owner.

**springdoc-openapi** to generate OpenAPI documents from the controllers, with the gateway
showing them all in one Swagger UI.

**Micrometer, OpenTelemetry, Prometheus, Grafana and Jaeger** for metrics, traces and dashboards.
Micrometer is the API Spring uses natively, OpenTelemetry carries traces in a vendor neutral format,
and the other three are the common open source backends.

**JUnit 6, AssertJ, Mockito, MockMvc, WebTestClient and ArchUnit** for tests, with **embedded
Postgres and an embedded Kafka broker** for integration tests instead of Testcontainers, because
Testcontainers needs Docker and the machine this was built on does not have it
([ADR 0005](docs/adr/0005-tests-without-docker.md)).

**Maven multi-module**, the build tool most enterprise Java teams use, with the wrapper checked in
so nothing needs installing.

**React with Vite, and no UI library.** The dashboard is small enough that a component library or a
state management library would have added more to explain than it was worth. A handful of hooks, a
WebSocket hook with reconnect logic, and one fetch based API client cover everything it needs.

## Bugs and problems caught along the way

These are worth knowing because each one says something about how the project was checked.

**The outbox relay never published a single event, and only an integration test noticed.** Every
unit test passed and the posting endpoints worked. The integration test that reads the Kafka topic
saw nothing. The relay's log said only that it could not construct a Kafka producer. The cause was a
setting: `delivery.timeout.ms` had been set to 30 seconds, and Kafka requires it to be at least
`linger.ms` plus `request.timeout.ms`, which was 30.005 seconds. The relay was catching the error and
backing off politely forever. The fix was one line, and the relay now logs the full exception the
first time it fails. The lesson was that a component that degrades gracefully also fails silently,
so it needs a test at the level where the failure becomes visible, and a metric (the outbox backlog)
that would show it in production.

**A timestamp could change between being written and being read.** Java's clock produces
nanoseconds and Postgres keeps microseconds. The scheduler's idempotency key includes the due time,
so a key built from the in-memory value on the first attempt would not match a key built from the
database value on a retry, and the ledger would post twice. The fix was a millisecond clock shared by
every service, and truncating a client-supplied start time on the way in.

**A 429 was going to be treated as the customer's fault.** The ledger client retries a 429 because
it is transient, but the first version classified any 4xx that survived the retries as a business
rejection, which would have skipped that month's transfer. A 429 now counts as "unavailable", so the
occurrence stays due.

**The platform's tests registered configuration they should not have.** Spring Boot
auto-configuration classes contain nested configuration classes that are only meant to load when
their parent's conditions pass. The platform's own test application sat in the same package and
component-scanned them directly, conditions and all, so the outbox's metrics tried to start without
an outbox. Real services live in other packages and were never affected. The test application now
does no component scanning.

**The schema and the entities disagreed about a column type.** `CHAR(3)` in SQL and a Java `String`
are not the same type to Hibernate, which validates the schema at startup and refused to start. The
columns became `VARCHAR`, and the check stayed on, since a mismatch caught at startup is much cheaper
than one caught in production.

**A service that is down came back as a 500 instead of a 502.** The gateway maps "could not connect"
to 502 so a client can tell an outage from a bug. A test pointed one route at a port where nothing
listens, and the connection error turned out to be an `UnknownHostException` from the DNS resolver,
not a `ConnectException`. Both now mean "not reachable right now", which also covers a Kubernetes
Service with no healthy pods.

**The embedded Kafka broker ignored the port it was given.** The local `dev-infra` tool needed Kafka
on 9092, but the KRaft test broker always picks a random port and ignores both `kafkaPorts()` and the
`listeners` setting. Rather than patch the test kit, a small TCP forwarder listens on 9092. That
works because Kafka clients only use the bootstrap address for their first metadata request and then
connect to the address the broker advertises, which is also on localhost.

**Statement badges were colored by literal debit and credit.** The first version of the statement
colored a debit one way and a credit the other. That is wrong for liability, equity, and revenue
accounts, where a credit increases the balance. The fix was to color by whether the entry increases
or decreases that account's balance, using the same rule as the server.

**The sixth expense category reused the first category's color.** Covered above. It was found by
loading a year of seed data with more categories than palette slots.

**The seed script described a transfer in the wrong direction.** A seeded transaction was described
as going from Jordan to Alex when the entries moved money from Alex to Jordan. The numbers were right
and the label was wrong, which is the kind of mismatch that is easy to miss in a ledger demo.

**The chart tooltip looked broken, but the test was.** A headless browser check reported that the
tooltip never appeared. The test viewport was shorter than the page, so the chart was below the fold,
and a raw mouse move does not scroll. The lesson was to confirm which side a failure belongs to before
changing the code.

**The balance chart never resized.** It measures its container with a `ResizeObserver`, set up in an
effect that ran once on first render, before the element existed. The chart sat at its fallback width
forever. A callback ref, which fires exactly when the element appears, fixed it.

## What the tests cover

`./mvnw verify` in `services/` runs about 150 backend tests in a few minutes, and needs nothing but a
JDK. The integration tests use a real Postgres and a real Kafka broker started inside the test JVM.

- **Ledger service:** every case from the original Node suite, run over HTTP against real Postgres
  (unbalanced transactions, overdrafts, idempotent replay, key conflicts, reversals, double
  reversals), plus 404s, currency mismatch, fractional cents, statements with running balances, and
  the CSV export. Beyond those: twenty concurrent withdrawals that must leave exactly zero, eight
  concurrent requests with one idempotency key that must post exactly once, triggers that must refuse
  an edit, the audit trail, events reaching Kafka with account names attached, a request's trace id
  arriving on the Kafka record after the outbox relay published it, and the demo seed
  (twelve accounts, books that balance, checking never negative, never seeded twice). Unit tests cover
  the debit rule for every account type, the posting rules, the request fingerprint, the CSV, and the
  random generator matching TypeScript. ArchUnit covers the layering.
- **Recurring transfer service:** every case from the original scheduler suite (a due transfer posts
  and advances, a double sweep sends the same key, a rejection is recorded and skipped, a paused
  schedule is ignored, a deleted one is gone), plus transient failures staying due, two concurrent
  sweeps where only one runs, account replication from Kafka, the REST fallback, and validation. The
  ledger client's retry and circuit breaker are tested against a scripted HTTP server. The calendar
  tests include month ends and daylight saving.
- **Insights service:** only expense debits count, a redelivered event counts once, months are UTC,
  the read model builds from Kafka, and a poison message lands in the dead letter topic without
  stopping the ones behind it.
- **Notification service:** the exact JSON the dashboard receives, a scheduled transfer announced
  once rather than twice, two browsers both receiving events over a real WebSocket, and a handshake
  from an unknown origin refused.
- **Gateway:** routing to stub services, trace propagation, CORS, Problem Details for unknown routes
  and down services, the write rate limit, WebSocket proxying, the Swagger aggregation, and JWT scopes
  checked through the real filter chain.
- **Platform:** the outbox (commits publish, rollbacks do not, writes outside a transaction fail,
  backlogs drain in batches, old rows are purged), dead lettering, the Problem Details handler, cents
  parsing, and UUID ordering.

The frontend has its own tests, which run the original cases against the in-browser demo backend and
check that the generated year never takes the main checking account negative.

Automated tests alone do not prove the system works together, so there was also an end to end run:
all five services, Postgres and Kafka started locally, the real dashboard pointed at the gateway, and
two browser tabs driven by Playwright. A transfer posted in one tab appeared in the other without a
reload, the overdraft message came through, and the spending totals the insights service built from
events matched the ledger's balances to the cent.

## What was left out, and why

**A login flow.** The dashboard still has no users. The gateway can enforce JWT scopes when an issuer
is configured, and every service would trust the gateway's checks. Per-user ownership of accounts
would add an owner column and an authorization check on every query, which does not change how the
accounting works.

**Multiple currencies in one transaction.** A real implementation needs an explicit exchange rate and
a separate pair of entries to represent the conversion against an FX account. That is its own small
project.

**Avro and a schema registry.** At bank scale, event schemas would live in a registry that rejects an
incompatible change before it is deployed. Here the contracts are versioned Java records and the
compatibility rules are written down. The `event-contracts` module is where a registry would plug in.

**A stored balance per account.** Balances are sums over an indexed table. The next step is described
above.

**Service discovery and a config server.** On Kubernetes, DNS and ConfigMaps already do both jobs
([ADR 0006](docs/adr/0006-no-discovery-or-config-server.md)).

## Questions that might come up

**Why split such a small app into five services?**
Honestly, for an app this size a well structured single service would be the right production call,
and the ADRs say so. The split is here to show the problems a real microservice system has to solve
and how they are solved: who owns which data, how a change and its event stay consistent, how a
consumer survives duplicates and poison messages, how a caller survives a dependency being down, and
how a background job gets exactly one owner. The boundaries are drawn where a real team would draw
them, by ownership, not one service per table.

**What happens if two requests try to spend the last dollar in an account at the same time?**
The posting locks the account row with `SELECT ... FOR UPDATE` before reading the balance, so the
second request waits until the first commits and then sees the new balance. Locks are always taken in
account id order, so two transfers between the same pair of accounts in opposite directions cannot
deadlock. There is a test that sends twenty withdrawals at once.

**How do you stop a customer being charged twice if a request times out and is retried?**
That is what the idempotency key is for. The client generates one key per attempt at an action, not
per network request, and sends it in a header. If the same key comes back, the ledger returns the
original result. If two requests with the same key arrive at the same instant, the unique index lets
one insert and the other reads the result.

**Is Kafka delivery exactly once?**
No, and nothing here pretends it is. Kafka's transactions give exactly once between Kafka topics,
but a service writing to its own database still sees at least once. So the producer side uses the
outbox (an event exists if and only if the change committed), and every consumer is idempotent (it
records the event id in the same transaction as its own change, or its change is naturally
repeatable).

**What happens if Kafka goes down?**
The ledger keeps accepting postings. Events pile up in the outbox table, the relay backs off, and the
`ledger.outbox.pending` metric climbs, which is what an alert would watch. When Kafka comes back the
relay drains the backlog. The dashboard stops getting live updates in the meantime and still shows
correct data on reload, because the source of truth is the database, not the event stream.

**What happens if the ledger goes down?**
Reads of schedules and spending keep working, because those services have their own data. The
scheduler's calls fail fast once the circuit breaker opens, the due occurrences stay due, and the
next sweep after the ledger recovers posts them with the same keys. Nothing is posted twice and
nothing is silently skipped.

**How would the scheduler run with multiple instances?**
It already can. ShedLock gives the sweep a lock row in the database, so only one instance sweeps at a
time and any of them can take over if that one dies. The derived idempotency key is still underneath
as a second line of defence.

**How do you trace one request across all of this?**
The gateway starts a trace and returns its id in `X-Trace-Id`. The id travels to the ledger in the
`traceparent` header, is stored with the outbox row, is restored when the event is published, and is
picked up by the consumer. One search in Jaeger shows the HTTP request, the database work and every
consumer that reacted. Error responses carry the same id, so a user's bug report can include it.

**Why not use a decimal or float type for money?**
Floating point numbers cannot represent most decimal fractions exactly, so small rounding errors
accumulate. A decimal type avoids that but still adds rounding rules for division. Storing an integer
count of the smallest unit avoids the whole category of problem, and it is what real payment
platforms do.

**What was the hardest part?**
In the ledger itself, getting the balance direction right for every account type, which feels
backwards for equity, revenue and liability accounts until it clicks. In the distributed part, being
honest about failure: deciding for every call whether a failure is the customer's (do not retry,
report it), the system's (retry with the same key, keep it due), or a poison record (park it and move
on), and then testing each of those cases rather than only the happy path.

**How would this scale to a real bank's transaction volume?**
The ledger scales out behind the gateway, since it holds no state in memory, and Kubernetes manifests
include an autoscaler for it. Postgres would get read replicas for statements and reports. Each
account would get a stored balance updated in the posting transaction, with the sums kept as a
nightly reconciliation. Very busy accounts, such as a bank's own fee account, would be split into
several sub-accounts so postings do not all queue on one row lock. Kafka topics are keyed by
transaction id so they spread across partitions. None of that changes the core rule, that a
transaction is a balanced set of entries and a balance is derived from history.

**Why is there no service discovery server?**
Because Kubernetes already provides it. A Service name is a DNS name with load balancing behind it,
so the gateway's routes are plain URLs. Running Eureka on top would be another stateful system to
keep alive for no gain.

**Is the hosted demo the real application?**
No, and the page says so in a banner. The React dashboard is exactly the real one, but the backend it
talks to is a second implementation that runs in the browser tab, because a static host cannot run
JVMs, Postgres and Kafka. Everything visible behaves the way the real services behave. Running the
real stack is `docker compose up`, or `npm run dev` on a machine without Docker.

**How would you deploy the real version?**
Every service builds into a container image from one Dockerfile, in layers so a code change only
rebuilds a few kilobytes. The `deploy/k8s` directory has Kustomize manifests: a Deployment and Service
per service, liveness, readiness and startup probes on the Actuator health groups, resource limits, a
restricted security context (non-root, read-only root filesystem, no capabilities), graceful shutdown,
autoscaling for the ledger and gateway, a disruption budget, and an Ingress for the gateway only.
Postgres and Kafka would be managed services such as Amazon RDS and MSK. CI builds, tests and scans
every image on each change.

## A short glossary

**Double entry accounting** means every transaction is recorded as at least two entries, a debit and
a credit of equal size, so the books balance by construction instead of by hoping nobody made a
mistake.

**Debit and credit** do not mean "subtract" and "add" the way the words are used casually. Whether a
debit increases or decreases a balance depends on the type of account. It increases assets and
expenses, and decreases liabilities, equity, and revenue. A credit is the reverse.

**Idempotency** means that doing the same operation more than once has the same effect as doing it
once. An idempotency key is how a client tells a server that this is another attempt at the same
request, not a new one.

**ACID** is the set of guarantees a database transaction gives: atomic (all or nothing), consistent
(it leaves the data valid), isolated (concurrent transactions do not see each other's half finished
work), and durable (once committed, it stays committed).

**Row lock** is a way of telling a database that nobody else can change a specific row until the
current transaction is done with it. It is what prevents two postings from spending the same dollar.

**Transactional outbox** is the pattern of writing an event to a table in the same transaction as the
change it describes, and publishing it to the message broker afterwards, so the two can never
disagree.

**Idempotent consumer** is a message handler that can safely receive the same message twice, usually
by recording each message id it has handled in the same transaction as its own change.

**Dead letter topic** is where a consumer parks a message it cannot process, with the reason
attached, so one bad message does not block everything behind it.

**Circuit breaker** is a guard around calls to a dependency. After enough recent failures it stops
making the call for a while and fails immediately instead, so callers are not stuck waiting on
timeouts, and then lets a few trial calls through to see if the dependency has recovered.

**Read model** (as in CQRS) is a copy of data shaped for one kind of query and built from events,
like the insights service's spending totals. It can be deleted and rebuilt by replaying the events.

**Event carried state transfer** is putting enough data in an event that consumers do not need to
call back to the producer, which is how the recurring transfer service keeps its account replica.
