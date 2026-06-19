# Project walkthrough

This document is meant to help explain the project out loud, for example in an interview. It
covers what the app does, why it was built the way it was, the reasoning behind each library
choice, the interesting technical problems that came up, and the bugs that got caught along the
way.

## The short version

Ledger is a double entry ledger, the same kind of system that sits underneath a bank account or a
product like Ramp or Brex. It has a Node and TypeScript API on top of a database (SQLite locally,
with a schema that is ready for Postgres) and a React dashboard that updates in real time over a
WebSocket.

The interesting part is not the CRUD. Every transaction is checked for balance before it is
written, money is never represented as a floating point number, a retried request cannot create a
duplicate transfer, and corrections are made by reversing a transaction instead of editing it. On
top of that, recurring transfers run from a background job, and the dashboard has an interactive
balance history chart, a spending breakdown, and CSV export.

## Why a ledger

Many portfolio projects are a stock price predictor or a crypto price tracker. Those show that
someone can call an API and plot a chart, but they say little about how the author thinks about
correctness. A ledger is different. Almost every fintech company has one somewhere in its stack,
whether that is a bank's core system, a corporate card company tracking spend, or a payments
company tracking who owes whom. It is also a common system design interview topic, so having built
one gives a concrete example to point to instead of describing it in the abstract.

## A transfer from start to finish

This trace covers what happens when someone moves money between two accounts.

The user fills out the transfer form and picks a from account, a to account, and an amount like ten
dollars. The frontend converts the amount from a string like "10.00" into an integer number of
cents with a small parsing function, so the number 1000 is what gets sent, never a string or a
float. It also generates a random idempotency key with the browser's built in `crypto.randomUUID`.

The request hits `POST /transactions`. Express passes it through a Zod schema that checks the shape
of the body and converts the amount into a JavaScript BigInt. From there it calls the core ledger
function, which does the real work. It checks that there are at least two entries, that every
amount is positive, and that the total of the debit entries equals the total of the credit entries.
If the idempotency key was already used, it compares a hash of this request to the hash stored the
first time. If they match, it returns the original transaction instead of creating a new one. If
they do not match, it rejects the request as a conflict.

If the transaction is new and balanced, the function opens a database transaction. Inside it, the
function loads the accounts involved, confirms none of them are missing, and confirms they share
one currency. For any account that is an asset, like a checking account, it recalculates the
current balance and makes sure this transaction would not push it below zero. Only then does it
write the Transaction row and its Entry rows, plus an audit log entry, and commit.

Back in the route handler, the server broadcasts a small JSON message over the WebSocket to every
connected browser tab, saying a transaction was posted and which accounts it touched. Every open
dashboard, including the one that made the request, receives the message and refetches the affected
balances and statement. That is why the balance on screen updates without a page refresh.

## How the recurring transfer scheduler works

This is the part of the project that shows a background job, not only a request and response.

A recurring transfer is created through a form much like the regular transfer form, except it also
asks how often to repeat: every minute (a demo only option, so the behavior can be watched without
waiting a week), daily, weekly, or monthly. Creating one does not move any money. It writes a row
that says which accounts, how much, how often, and when it is next due. The due time defaults to
right now, so a new recurring transfer fires almost immediately.

Inside the same server process, a single `setInterval` wakes up every fifteen seconds
(configurable) and asks the database for every recurring transfer that is active and due. For each
one, it builds an idempotency key out of the recurring transfer's id and its exact due time, for
example `recurring:abc123:2026-01-01T00:00:00.000Z`, and posts a transfer with that key through the
same `postTransaction` function a manual transfer uses.

That detail is what makes the job safe. If the sweep ran twice for the same due time, or the server
crashed after posting but before recording that it had, the second attempt would compute the same
idempotency key and get back the already posted transaction instead of posting a second one. The
schedule does not have to be perfectly reliable for the money to be handled correctly, because the
guarantee that protects a retried manual transfer also protects a retried scheduled one.

After a successful post, the job moves the next due time forward by one interval from the time it
was due, not from the time it happened to run, so a slightly late sweep does not make the schedule
drift. If the post fails, most often because the account does not have enough money, the job
records the failure and still advances the next due time instead of retrying the same failure every
fifteen seconds. It gets another chance at its next scheduled occurrence. Either way, the server
broadcasts the result over the same WebSocket the manual flow uses, so a scheduled transfer shows up
in the live activity feed and updates balances exactly like a manual one.

## The data model

There are five tables: Account, Transaction, Entry, RecurringTransfer, and AuditLog.

An Account has a type, which is one of asset, liability, equity, revenue, or expense. This is
standard accounting terminology, and it matters because it decides which direction of entry
increases the balance. For an asset or an expense account, a debit increases the balance and a
credit decreases it. For a liability, equity, or revenue account it is the reverse. This one rule,
implemented once in a function called `signedDelta`, keeps the whole system consistent.

For example, a checking account is an asset. Paying rent out of it is a credit to checking (its
balance goes down) and a debit to the rent expense account (its balance goes up). An opening balance
works the other way around: checking is debited and the equity account that funds it is credited,
and that credit increases the equity balance. It feels backwards at first, but it stops feeling that
way once the rule is applied consistently.

A Transaction is one event, like a transfer or a fee. It has a description, an idempotency key if
the client sent one, and a status of either posted or voided.

An Entry is one leg of a transaction. A simple transfer between two accounts always produces exactly
two entries, a debit on one side and a credit on the other, for the same amount. That is what
"double entry" means: every movement of money touches at least two accounts, and total debits always
equal total credits.

A RecurringTransfer is not part of the ledger's own history and never has entries of its own. It
only holds instructions: which two accounts, how much, how often, and when it is next due. The
background job reads these rows and, when one is due, posts a real transaction through the same
function a manual transfer uses. If every RecurringTransfer row were deleted, the ledger's history
would not change, since nothing about a past transaction depends on the schedule that caused it.

An AuditLog row is written any time an account is created or a transaction is posted or reversed.
It exists separately from the entries so there is a plain, readable trail of what happened, which is
useful for the kind of question an auditor or support engineer would ask.

Balances are never stored anywhere. An account's balance is always calculated by summing its entries
at read time. That was deliberate. It means there is exactly one source of truth for the ledger's
state, its own history, instead of a balance column that could drift out of sync with the entries
that are supposed to explain it.

## Design decisions and why they were made

**Money is stored as BigInt cents, never a float.** A binary floating point number cannot represent
most decimal fractions exactly, which is why `0.1 + 0.2` in JavaScript does not equal `0.3`. That is
a real bug in a lot of amateur finance code. Every amount in this project, from the database column
to the JSON sent over the wire, is a whole number of cents. Ten dollars is the integer 1000, not the
float 10.0. This also shows up as a small custom JSON replacer on the server, since `BigInt` cannot
be serialized by `JSON.stringify` on its own and gets converted to a string before it leaves the
API. Floats appear only where they are harmless, such as positioning points on a chart or animating
a count up.

**Every transaction must balance before it is written.** This is enforced in code, not assumed. If a
transaction is posted where the debits and credits do not add up to the same total, the function
throws before anything touches the database. This is the core rule of double entry accounting and
the reason to build this instead of a simpler system that increments and decrements a number on an
account.

**Idempotency keys prevent duplicate transfers on retry.** A client can send a request, lose the
response to a network timeout, and not know whether it succeeded. If it naively retries, someone
could be charged twice. This project follows the pattern Stripe's API uses: the client sends a key
with the request, and if the same key shows up again the server returns the original result instead
of doing the work twice. If the same key shows up with a different request body, that is treated as
a mistake and rejected, since silently returning a mismatched result would be worse than an error.

**Nothing is ever edited or deleted. Corrections are reversals.** To correct a transaction, the
system posts a new transaction with every entry flipped from debit to credit or credit to debit, and
marks the original as voided without removing it. This mirrors a real bank statement, where a
mistake shows up as a new line that cancels the old one instead of a silently edited row. It also
means the audit trail stays complete no matter what happens later.

**Overdraft protection is checked inside the same database transaction as the write.** The check and
the write have to happen together. Otherwise two requests could both read a balance of ten dollars,
both decide a nine dollar withdrawal is fine, and both succeed, leaving the account at negative
eight dollars. Wrapping the balance check and the insert in one Prisma transaction prevents that.

**The account statement recalculates running balances by replaying entries instead of storing
them.** This was a conscious tradeoff for the size of the project. It is simple and obviously
correct, since the running balance is derived the same way the account balance is. It would not hold
up for an account with years of heavy history, where a production system would maintain a running
balance that updates as each entry is written. This is worth stating plainly, because a demo project
is more convincing when it says exactly where the line was drawn and why.

**The scheduler is one in-process interval, not a job queue, and that limit is written down.** A
production system handling real scheduled payments would use something like BullMQ with Redis, or a
managed cron service, especially once more than one copy of the server runs. Two copies of this
scheduler would both sweep for due transfers at the same moment. The idempotency key means they
could not both post the same occurrence, but that is a safety net, not a design for the problem. A
production setup would want one clear owner of the schedule, through a database lock or a real
queue, instead of relying on every instance racing safely.

**Monthly and weekly intervals use calendar math, not a fixed number of minutes.** A tempting
shortcut is to say a month equals 43,200 minutes and add that each time. That drifts, because months
are not all the same length, and a transfer scheduled for the 31st would slide earlier and earlier
over the year. Instead, the next run date is computed by asking JavaScript's `Date` object to add
one to the month field, which rolls January 31st into early March instead of landing on a
nonexistent February 31st. It is a small detail, but it is the kind of correctness question that
separates code that works in a demo from code that misbehaves the first time someone schedules a
payment on the 31st.

**The charts and the CSV export were built without adding a dependency.** No charting library or CSV
library was pulled in. The spending breakdown is plain HTML bars sized by percentage inside a fixed
track. The balance history chart is a hand written SVG line and area chart with rounded axis ticks, a
crosshair, and a tooltip that follows the pointer. The CSV is a small function that escapes commas,
quotes, and newlines by hand and joins rows with the standard CRLF line ending. Each of these is
simple enough that a dependency would have cost more in bundle size and API surface than it saved in
code. That is a judgment call, not a rule, and a dashboard with a dozen chart types would reasonably
use a charting library.

**The demo data is a year of history generated with a seeded random number generator, not
`Math.random()`.** The seed script builds roughly 250 transactions spread across real calendar dates
over the past year: biweekly paychecks, weekly groceries, monthly rent, and so on. Amounts come from
a small seedable generator (a `mulberry32` implementation) instead of true randomness, so running
`npm run seed` twice produces the exact same year of activity. That let the amount ranges be tuned
once against a known sequence and trusted not to push an account negative on some later run.
Reproducible data is also easier to describe and debug than data that changes every time it is
generated.

**Backdating the demo data happens outside the core ledger function, not inside it.**
`postTransaction` never accepts a caller supplied timestamp, on purpose, because that stops any
client from lying about when something happened. The seed script still needs realistically dated
history, so it posts every transaction through the normal function first, exactly as a real transfer
would be, and only afterward uses Prisma directly to set that row's `createdAt` to the intended
date. The invariant the public function protects is never weakened. Only a script with direct
database access, which no client can call, gets to backdate anything.

**The balance chart and the statement table deliberately stopped sharing one data window once the
seed data grew.** The chart was first built to reuse the same handful of rows as the statement table
below it, so that every value in the chart's tooltip was also a visible table row. That matters for
accessibility, since a tooltip should enhance a value that is reachable another way, not be the only
way to reach it. A year of history broke that pairing, because showing all of it in the table would
make the table unusable while a chart handles hundreds of points fine. So they diverge on purpose:
the table stays capped at forty rows and reads as recent activity, the chart fetches a much larger
window so the full year is visible, and the CSV export, which contains an account's whole history,
keeps every charted value reachable outside the chart.

**A sixth expense category would have silently broken the chart colors, so extras fold into
"Other".** The spending chart assigns one color per category from a small palette validated for
colorblind separation, with five slots. The seed data has six expense categories. Cycling back to the
first color for the sixth would make two unrelated categories look identical, which defeats the point
of a validated palette. Instead, once there are more categories than colors, the chart keeps the top
four by total and sums the rest into one muted "Other" bar. This was already a latent bug before the
seed data changed, since any user could create a sixth expense account by hand. The seed data just
made it obvious enough to fix.

**The dashboard talks to an interface, which is what makes a free hosted demo possible.** The API is
a long running Node process with a WebSocket server, a background scheduler, and a database, so a
static host cannot run it, and a free tier that can run it goes to sleep and takes most of a minute
to answer the first request. That is a bad first impression for anyone opening the link. Because
every component already talked to a small `LedgerApi` interface rather than calling `fetch`
directly, a second implementation of that interface could be written that keeps its tables in
memory in the browser: the same balance check, overdraft protection, idempotency keys, reversals,
calendar math, and the same recurring transfer sweep running on an interval inside the tab. One line
picks which implementation the app gets, based on a build flag. No component knows which one it
has. The demo even seeds itself with the same seeded random number generator the real seed script
uses, so the hosted page shows the same year of activity a local checkout does.

**Two implementations of the same rules can drift, so both are pinned to the same tests.** The
honest weakness of the in-browser backend is that it is a port, not shared code. The invariants now
exist twice, and a fix applied to one could be forgotten in the other. Extracting the rules into a
package both sides import would be the real answer, but the server's version is closely tied to
Prisma's query and transaction API, so that refactor is larger than it looks. The cheaper mitigation
was to copy the server's test suite case for case against the demo backend. If either side stops
rejecting an unbalanced transaction, stops blocking an overdraft, or stops replaying an idempotency
key, a test fails. That does not make the duplication free, but it means the duplication cannot
quietly change behavior.

**The demo says it is a demo.** A dashboard showing a green "Live" indicator, real looking balances,
and a year of transaction history could easily be mistaken for a deployed product. The demo build
carries a banner saying the API, the database, and the scheduler are all running in the browser tab
and that nothing is saved. Being clear about what someone is looking at matters more than looking
impressive for a moment, especially for a project whose whole point is that the accounting is
trustworthy.

**One place where the in-browser version is genuinely simpler, and the reason is worth knowing.**
The server wraps the balance check and the entry insert in a database transaction because two HTTP
requests can interleave between those two steps, and without that wrapper both could read the same
balance and both decide an overdraft is fine. The browser version has no equivalent wrapper and does
not need one: JavaScript runs one thing at a time, and there is no `await` anywhere between the
check and the write, so nothing can run in between. The invariant is identical. The reason it holds
is completely different, which is a good illustration that concurrency control is about the
execution model, not about the code looking careful.

## Frontend and visual design decisions

The dashboard is styled like a trading terminal or order management system: sharp corners, dense
tables with zebra rows, monospace labels, and uppercase system labels. All colors are CSS custom
properties, with the dark navy theme as the default and a light theme defined as an override under
`data-theme="light"`. The chosen theme is stored in `localStorage`.

Two color rules keep the interface readable. First, the positive and negative colors mean a balance
went up or down, not that an entry was a debit or a credit. Since a credit increases a liability or
revenue account, coloring by literal debit and credit would show a good change as a bad one for
those account types. Second, anything a user can click carries the accent color even at rest, with
the negative color reserved for destructive actions like canceling a recurring transfer. Purely
informational elements, such as status badges, tiles, and table rows, stay neutral so the two
groups are easy to tell apart at a glance.

## Why each library was chosen

**Express** for the API framework, mainly because most people reviewing this project already know
it, and the project does not need anything more opinionated. A handful of routes and one error
handling middleware did not call for a heavier framework.

**Prisma** as the database layer, because it provides a typed schema file that doubles as
documentation of the data model, generates migrations, and produces a fully typed client, so a typo
in a field name is a compile error instead of a runtime one. It also supports SQLite and Postgres
from the same schema with only the provider line changing, which let the project run locally with no
setup while staying honest about what a production deployment would use.

**SQLite for local development, with a schema that is Postgres ready.** The practical reason is that
the machine this was built on did not have Docker or Postgres installed. Instead of treating that as
a blocker, it became a design decision: use only column types that behave the same on both engines,
so switching providers later is a one line change and not a rewrite. A `docker-compose.yml` for
Postgres is included.

**Zod** for request validation, because it describes the shape of an incoming request once and
provides both the runtime check and the TypeScript type from that single definition, instead of
writing validation logic and a matching interface separately and letting them drift apart.

**The `ws` library** for the WebSocket server, because the real time feature is simple: broadcast a
small event to every connected client when something changes. That does not need a heavier messaging
system, just a plain WebSocket connection and a loop over connected clients.

**Vitest** for testing, because it is fast, has a syntax almost identical to Jest so it is easy to
read, and works with a Vite based frontend and a TypeScript backend without extra configuration.

**React with Vite, and no UI library.** The dashboard is small enough that a component library or a
state management library would have added more to explain than it was worth. A handful of `useState`
and `useEffect` hooks, a small WebSocket hook with reconnect logic, and one fetch based API client
cover everything the app needs.

**TypeScript everywhere**, backend and frontend, so the shape of a transaction, an entry, or an
account is defined once and the compiler catches it if the two sides disagree about a field name or
type.

## Bugs and problems caught along the way

These are worth knowing because each one says something about how the project was checked.

**Statement badges were colored by literal debit and credit.** The first version of the statement
colored a debit one way and a credit the other. That is wrong for liability, equity, and revenue
accounts, where a credit increases the balance. The fix was to color by whether the entry increases
or decreases that account's balance, using the same rule as `signedDelta`. The live balance flash
uses the same rule, so an increase flashes green and a decrease flashes red for every account type.

**The sixth expense category reused the first category's color.** Covered above. It was found by
loading a year of seed data with more categories than palette slots, and it turned out to be a latent
bug that existed before that data did.

**The seed script described a transfer in the wrong direction.** A seeded transaction was described
as going from Jordan to Alex when the entries moved money from Alex to Jordan. The numbers were right
and the label was wrong, which is the kind of mismatch that is easy to miss in a ledger demo. The
label was corrected to match the entries.

**Account card hover looked the same as selected.** After the interactive color pass, hovering an
account card produced the same style as the selected card, so a user could not tell which one was
actually chosen. Hover now uses a lighter tint than the selected state.

**A danger button hover broke in the light theme.** The first version of the destructive button's
hover mixed its color toward white, which worked on the navy background and nearly disappeared on the
light one. It now lowers opacity instead, which behaves the same in both themes.

**The chart tooltip looked broken, but the test was.** A headless browser check reported that the
tooltip never appeared on the dense chart. The product was fine. The test viewport was shorter than
the page, so the chart was below the fold, and a raw mouse move does not scroll. Scrolling the chart
into view first made the check pass. The lesson was to confirm which side a failure belongs to before
changing the code.

**The balance chart never resized, and a layout bug is what exposed it.** The statement table was
pushing the whole page wider than the screen on a narrow window, because the row is too dense to
reflow and the timestamp column is pinned to one line. Wrapping the table in its own scrolling
container fixed that, but the page still overflowed, which meant something else was doing it too.
The something else was the balance chart, which measures its container with a `ResizeObserver` so
the SVG can be sized in real pixels and keep the hover math exact. The measurement never arrived.
The hook set up the observer in an effect with an empty dependency list, and on first render the
component has no account yet, so it returns a placeholder and the element being measured does not
exist. The effect ran once against a null ref, gave up, and never got a second chance. The chart sat
at its 600 pixel fallback forever, which overflowed a phone and left dead space on a wide screen,
where the panel is 780 pixels. Switching to a callback ref fixed it, since that fires exactly when
the element appears. Two things are worth taking from this. Fixing the first cause of an overflow
does not mean the overflow is fixed, so it is worth re-measuring instead of assuming. And a chart
built by hand means owning the parts a charting library would have handled, which is the real cost
behind that earlier tradeoff.

**A JSX arrow rendered as literal text.** An arrow written as `→` inside JSX text showed up on
screen as the six characters instead of an arrow, because JSX text does not process escapes the way a
JavaScript string does. Wrapping it as a string expression fixed it.

**Setup problems.** npm blocked install scripts, so the Prisma engines did not download until the
scripts were approved explicitly. Running `npm audit fix` downgraded Prisma, which needed the
approval step again. TypeScript's `erasableSyntaxOnly` setting rejected constructor parameter
properties, so the API error class was rewritten with an explicit field.

## What the tests cover

The test suite is small and aimed at the rules that matter, not at testing every route for its own
sake. There are thirteen tests on the server, across two files.

Six cover the core ledger: an unbalanced transaction is rejected, an overdraw attempt is rejected
without changing the balance, a repeated idempotency key returns the original result instead of
posting twice, a reused key with a different body is a conflict, a reversal restores the exact prior
balance and voids the original, and a transaction that was already voided cannot be reversed again.

The other seven cover the recurring transfer scheduler, and they test a background job without ever
waiting on a real timer. The sweep function, `runDueRecurringTransfers`, takes the current time as an
argument instead of reading the system clock, so a test can hand it any moment and get a
deterministic result.

- One test hands it a due transfer and confirms it posts and the next due date moves forward.
- One test calls the sweep twice for the same due time, simulating overlapping sweeps or a crash
  between posting and recording, and confirms only one transaction was created.
- One test points a recurring transfer at an account with no money and confirms the occurrence is
  recorded as failed without crashing the sweep or retrying forever.
- One test confirms a paused recurring transfer is skipped.
- Two tests check the calendar math directly: adding a month to January 31st lands in March, and
  adding a week lands exactly seven days later.
- One test confirms that canceling a recurring transfer removes its row, so it is never picked up
  again.

Adding tests for things like creating an account would mostly test Prisma and Express, not this
project's own logic, so the suite stays focused on the invariants the system promises.

The frontend has sixteen more tests that run those same thirteen cases against the in-browser demo
backend, so the two implementations of the rules cannot drift apart unnoticed. Three of them cover
the generated demo data itself: that it spans a full year, that it produces more expense categories
than the chart has colors (which is the case that makes the chart fold the extras into "Other"), and
that the running balance on the main checking account never goes negative at any point in the year.
That last one matters because the seed amounts were tuned against one specific pseudo-random
sequence, and a test is the only thing that keeps that tuning honest.

Automated tests alone do not prove a frontend renders and behaves correctly, so there was also a
manual pass with a headless browser. It drove the React forms, watched a live balance update over the
WebSocket, triggered the overdraft error and confirmed it displayed correctly, checked the layout in
both light and dark mode, and hovered the balance chart to confirm the crosshair and tooltip track
correctly with a year of data.

## What was left out, and why

**Authentication.** A user model and login flow would not touch the accounting logic. It would add an
`ownerId` column and a layer of middleware in front of the existing routes. Leaving it out kept the
project focused on the part that was the point.

**Multiple currencies in one transaction.** A real implementation needs an explicit exchange rate and
a separate pair of entries to represent the conversion. That is a reasonable next feature, but it is
its own small project and not a missing piece of this one.

**A stored running balance.** The statement recalculates from full history on every request. This is
covered above, and it is the specific answer to "what would change before production."

**A real job queue.** The scheduler is one in-process interval. The limit is described above.

## Questions that might come up

**Why build a ledger instead of something else?**
Because it demonstrates correctness under constraints that matter in finance, not just that an app
can call an API and show data on a page.

**What happens if two requests try to spend the last dollar in an account at the same time?**
Both requests check the balance and write their entries inside a database transaction. Locally,
SQLite serializes all writers, so this cannot happen concurrently in this project as it stands. On
Postgres, the balance check and the write need to happen under a row lock, using `SELECT ... FOR
UPDATE` on the accounts involved, ordered consistently by id to avoid a deadlock between two
transfers that touch the same two accounts in opposite order. The code calls this out as the one
thing a Postgres deployment needs to add.

**Why not use a decimal or float type for money?**
Floating point numbers cannot represent most decimal fractions exactly in binary, so small rounding
errors accumulate. A decimal type avoids that specific problem but still adds rounding rules to think
about for division. Storing money as an integer count of the smallest unit, cents for dollars,
avoids the whole category of problem. It is also the approach real payment platforms use.

**How is a customer prevented from being charged twice if a request times out and is retried?**
That is what the idempotency key is for. The client generates a unique key per attempt at an action,
not per network request, and sends it along. If the same key comes back, the server recognizes that
it already handled that exact request and returns the original result instead of repeating the work.

**What was the hardest part?**
Getting the balance direction rule right for every account type. The debit and credit logic for a
checking account matches how most people expect money to behave, but equity, revenue, and liability
accounts increase with a credit, which feels backwards until it is explained. Getting one function to
express that rule for every account type took more care than it looks like from the outside. The same
rule later came back in the UI, where the statement colors had to follow the balance direction and
not the literal debit or credit.

**How would this scale to a real bank's transaction volume?**
The database would move to Postgres with proper row locking, as described above. The statement
endpoint would stop recalculating from full history and instead maintain a running balance that
updates as each entry is written. Reads would likely be served from a replica separate from the one
handling writes. None of that changes the core rule, that a transaction is a balanced set of entries
and the balance is derived from history. It only changes how efficiently the rule is enforced at
scale.

**Why is there no authentication?**
The purpose of the project is to demonstrate the ledger's own correctness, not to build a full
product. Authentication is a well understood, separate concern that would not change how the
accounting invariants work.

**How does the recurring transfer scheduler avoid posting the same transfer twice?**
Every scheduled occurrence gets its own idempotency key built from the recurring transfer's id and
its exact due timestamp. The sweep posts through the same function a manual transfer uses, which
checks that key before writing anything. So even if the sweep runs twice for the same due time, or
the process restarts between posting the transaction and recording it, the second attempt recognizes
the key and returns the original result. The scheduling part does not have to be perfectly reliable
because the posting part already is.

**How would the scheduler run with multiple servers?**
As written, it would not be safe to run more than one copy, since every copy would sweep for due
transfers at the same moment. The idempotency key stops double posting of the same occurrence, but
that is a safety net, not a real design. The fix is to give the schedule one clear owner, either a
distributed lock so only one instance sweeps at a time, or a real job queue like BullMQ, which hands
each job to exactly one worker.

**Why were the charts built by hand instead of with a charting library?**
The dashboard needs two charts: a bar breakdown of spending and a line chart of balance history.
Plain HTML bars and a small SVG chart were enough to build both correctly, including a category
palette validated for colorblind safe contrast and a crosshair tooltip with rounded axis ticks. A
charting library would have added bundle size and a new API to learn for something this contained.
That tradeoff flips for a dashboard with many chart types, and it is fair to say so directly.

**Why does the CSV export come from the server instead of being built in the browser?**
The browser already has the statement data it shows in the table, so building the CSV client side
would have worked too. The server does it so the export is not limited to whichever rows the browser
happened to load. The dashboard fetches that endpoint and saves the response as a file, taking the
filename from the `Content-Disposition` header the server sets, which means the API stays in charge
of what the download is called. That header also has to be listed in the CORS configuration's
exposed headers, because a cross origin fetch cannot read it otherwise, and the filename would
silently fall back to a generic one.

**Is the hosted demo the real application?**
No, and the page says so in a banner. The React dashboard is exactly the real one, but the backend
it talks to is a second implementation that runs in the browser tab, because a static host cannot
run a Node process with a WebSocket server, a scheduler, and a database. Everything visible behaves
the way the real API behaves, including rejected overdrafts, idempotent retries, reversals, and the
recurring transfer job posting on its own. What it is not is a deployment of the Express API, and
nothing is stored anywhere or shared between visitors. Running the real stack locally is one clone
and two commands.

**How do you keep the in-browser backend from drifting away from the real one?**
That is the honest weakness of the approach: the rules exist twice. The mitigation is that the
server's test suite was copied case for case against the demo backend, so if either one stops
rejecting an unbalanced transaction or stops blocking an overdraft, a test fails. The better answer
would be to extract the rules into a package both sides import, which is not done here because the
server's version is closely tied to Prisma's query and transaction API, and that refactor is bigger
than it first looks.

**How would you deploy the real version?**
The frontend is static and can go anywhere. The API needs a host that keeps a process alive and
supports WebSockets, so something like Render or Koyeb rather than a serverless function platform,
with Postgres from a provider like Neon or Supabase. The application changes are small: switch the
Prisma datasource provider to `postgresql`, run the migrations, point `VITE_API_URL` and
`VITE_WS_URL` at the API, and set `CORS_ORIGIN` to the frontend's origin. The one real code change
is adding the row locking described above, because SQLite serializes writers and Postgres does not,
so the overdraft check needs `SELECT ... FOR UPDATE` to stay safe under concurrent requests.

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

**ACID** is the set of guarantees a database transaction is supposed to give: atomic (all or
nothing), consistent (it leaves the data in a valid state), isolated (concurrent transactions do not
see each other's half finished work), and durable (once committed, it stays committed). The balance
check and the entry insert in this project happen inside one ACID transaction so a crash or a race
with another request cannot split them apart.

**Row lock** is a way of telling a database that nobody else can read or write a specific row until
the current transaction is done with it. It is what prevents the double spend scenario described
above in production.
