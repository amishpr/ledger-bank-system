# Project walkthrough

This document is not for users of the app. It is for you, so that when
someone in an interview asks you to talk through a project, you have more
than "I built a banking app" to say. It covers what the project actually
proves you understand, why it is built the way it is, why each tool was
chosen, and the kinds of questions you might get asked about it along with
how to answer them.

## The one minute version

If someone asks you to describe this project in a sentence or two, here is
a version you can say in your own words: this is a double entry ledger,
the same kind of system that sits underneath a bank account or a product
like Ramp or Brex. It has a Node and TypeScript API on top of Postgres
(SQLite locally), and a React frontend that updates in real time over a
WebSocket. The interesting part is not the CRUD, it is that every
transaction is checked for balance before it is written, money is never
represented as a floating point number, retried requests cannot create a
duplicate transfer, and corrections are done by reversing a transaction
rather than editing it.

## Why build a ledger instead of something else

A lot of portfolio projects are a stock price predictor or a crypto price
tracker. Those show you can call an API and plot a chart, but they do not
show much about how you think about correctness. A ledger is different
because almost every fintech company has one somewhere in their stack,
whether that is a bank's core system, a corporate card company tracking
spend, or a payments company tracking who owes whom. It is also a common
system design interview topic at these companies, so having actually built
one gives you something concrete to point to instead of describing it in
the abstract.

## Walking through a transfer, end to end

This is the kind of trace that is useful to have ready, because it shows
you understand your own system rather than having memorized a description
of it.

Someone fills out the transfer form in the browser and picks a from
account, a to account, and an amount like ten dollars. The frontend first
converts that amount from a string like "10.00" into an integer number of
cents using a small parsing function, so the number 1000 is what actually
gets sent, never the string or a float. It also generates a random
idempotency key using the browser's built in `crypto.randomUUID`.

That request hits `POST /transactions` on the API. Express passes it
through a Zod schema that checks the shape of the body and converts the
amount into a JavaScript BigInt. From there it calls into the core ledger
function, which does the real work. It checks that there are at least two
entries, that every amount is positive, and that the total of the debit
entries equals the total of the credit entries. If the idempotency key was
already used, it compares a hash of this request to the hash it stored the
first time. If they match, it returns the original transaction instead of
creating a new one. If they do not match, it rejects the request as a
conflict.

Assuming the transaction is new and balanced, the function opens a
database transaction. Inside it, it loads the accounts involved, confirms
none of them are missing, confirms they share one currency, and for any
account that is an asset (like a checking account) it recalculates the
current balance and makes sure this transaction would not push it below
zero. Only then does it actually write the Transaction row and its Entry
rows, plus an audit log entry, and commit.

Back in the route handler, the server broadcasts a small JSON message over
the WebSocket to every connected browser tab, saying a transaction was
posted and which accounts it touched. Every open dashboard, including the
one that made the request, picks up that message and refetches the
affected account balances and statement, which is why the balance on
screen updates without anyone refreshing the page.

## How the recurring transfer scheduler works

This is the part of the project that shows you can reason about a
background job instead of only a request and response, so it is worth
having a clean trace ready for it too.

A recurring transfer is created through a form much like the regular
transfer form, except it also asks how often to repeat: every minute (a
demo only option, so you can actually watch it work without waiting a
week), daily, weekly, or monthly. Creating one does not move any money by
itself. It just writes a row that says which accounts, how much, how
often, and when it is next due, with that due time defaulting to right
now so a freshly created one fires almost immediately.

Inside the same server process, a single `setInterval` wakes up every
fifteen seconds (configurable) and asks the database for every recurring
transfer that is active and due. For each one it finds, it builds an
idempotency key out of the recurring transfer's id and its exact due time,
for example `recurring:abc123:2026-01-01T00:00:00.000Z`, and posts a
transfer with that key through the exact same `postTransaction` function a
manual transfer uses. That one detail is what makes the whole thing safe.
If the sweep somehow ran twice for the same due time, or if the server
crashed after posting but before it recorded that it had, the second
attempt would compute the identical idempotency key and get back the
already-posted transaction instead of posting a second one. The schedule
does not have to be perfectly reliable for the money to be handled
correctly, because the same guarantee that protects a retried manual
transfer protects a retried scheduled one for free.

After a successful post, the job advances the row's next due time forward
by one interval from the time it was due, not from whatever time it
happened to actually run, so a slightly late sweep does not drift the
schedule. If the post fails, most often because the account does not have
enough money, the job records the failure and still advances the next due
time rather than retrying the same failure every fifteen seconds forever.
It gets another chance on its next scheduled occurrence. Either way, the
server broadcasts the result over the same WebSocket the manual transfer
flow uses, so a scheduled transfer shows up in the live activity feed and
updates account balances on screen exactly like a manual one would,
without anyone needing to refresh or even know a background job exists.

## The data model, explained plainly

There are five tables: Account, Transaction, Entry, RecurringTransfer, and
AuditLog.

An Account has a type, which is one of asset, liability, equity, revenue,
or expense. This is standard accounting terminology and it matters because
it decides which direction of entry increases the balance. For an asset or
an expense account, a debit increases the balance and a credit decreases
it. For a liability, equity, or revenue account, it is the reverse, a
credit increases it and a debit decreases it. This one rule, implemented
once in a function called `signedDelta`, is what makes the whole system
consistent. A checking account is an asset, so paying rent out of it is a
credit. The equity account that funded its opening balance is credited by
a debit somewhere else, since equity increases with a credit. Once you say
that sentence out loud a couple of times it stops feeling backwards.

A Transaction is one event, like a transfer or a fee. It has a
description, an idempotency key if the client sent one, and a status of
either posted or voided.

An Entry is one leg of a transaction. A simple transfer between two
accounts always produces exactly two entries, a debit on one side and a
credit on the other, for the same amount. This is what "double entry"
means, every movement of money touches at least two accounts and the
total debits always equal the total credits.

A RecurringTransfer is not part of the ledger's own history. It never has
entries of its own. It only holds instructions: which two accounts, how
much, how often, and when it is next due. The background job reads these
rows, and when one is due, posts a real transaction through the same
function a manual transfer uses. If you deleted every RecurringTransfer
row tomorrow, the ledger's history would not change at all, since nothing
about a past transaction depends on the schedule that caused it.

An AuditLog row gets written any time an account is created or a
transaction is posted or reversed. It exists separately from the entries
themselves so there is a plain, readable trail of what happened, useful
for the kind of question an auditor or a support engineer would ask.

One detail worth mentioning if asked: balances are never stored anywhere.
An account's balance is always calculated by summing its entries at read
time. That was a deliberate choice, because it means there is exactly one
place the ledger's state comes from, its own history, instead of a
balance column that could theoretically drift out of sync with the
entries that are supposed to explain it.

## Design decisions and why they were made

**Money is stored as BigInt cents, never a float.** A binary floating
point number cannot represent most decimal fractions exactly, which is why
`0.1 + 0.2` in JavaScript does not equal `0.3`. That is a real bug in a
lot of amateur finance code. Every amount in this project, from the
database column to the JSON sent over the wire, is a whole number of
cents. Ten dollars is the integer 1000, not the float 10.0. This also
shows up in a small custom JSON replacer on the server, since `BigInt`
cannot be serialized by `JSON.stringify` on its own, so it gets converted
to a string before it leaves the API.

**Every transaction must balance before it is written.** This is enforced
in code, not just assumed by whoever calls the function. If you tried to
post a transaction where the debits and credits do not add up to the same
total, the function throws before anything touches the database. This is
the core rule of double entry accounting and the whole point of building
this instead of a simpler system that just increments and decrements a
number on an account.

**Idempotency keys prevent duplicate transfers on retry.** In any real
system, a client can send a request, lose the response due to a network
timeout, and not know if it succeeded. If it naively retries, you risk
charging someone twice. This project follows the same pattern Stripe's API
uses, a client sends a key along with the request, and if the same key
shows up again the server returns the original result instead of doing
the work twice. If the same key shows up with a different request body,
that is treated as a mistake and rejected, since silently returning a
mismatched result would be worse than an error.

**Nothing is ever edited or deleted, corrections are reversals.** If a
transaction needs to be corrected, the system posts a brand new
transaction with every entry flipped from debit to credit or credit to
debit, and marks the original as voided without removing it. This mirrors
how a real bank statement works, a mistake shows up as a new line that
cancels the old one, not as a silently edited row. It also means the audit
trail is complete no matter what happens later.

**Overdraft protection is checked inside the same database transaction as
the write.** The check and the write have to happen together, otherwise
two requests could both read a balance of ten dollars, both decide a nine
dollar withdrawal is fine, and both succeed, leaving the account at
negative eight dollars. Wrapping the balance check and the insert in one
Prisma transaction is what prevents that.

**The account statement recalculates running balances by replaying entries
instead of storing them.** This was a conscious tradeoff for the size of
this project. It is simple and obviously correct, since the running
balance is always derived the same way the account balance is. It would
not hold up at the scale of an account with years of history, where a
production system would maintain a running balance that updates as each
entry is written instead of recalculating from scratch on every request.
This is a good thing to be upfront about if asked, since pretending a demo
project handles every production concern is less convincing than
explaining exactly where the line was drawn and why.

**The scheduler is one in-process interval, not a job queue, and that
limit is written down rather than hidden.** A production system handling
real scheduled payments would use something like BullMQ with Redis, or a
managed cron service, especially the moment you run more than one copy of
the server. Two copies of this project's simple scheduler would both sweep
for due transfers at the same moment. The idempotency key means they could
not both succeed in posting the same occurrence twice, but that is a safety
net, not a design for the problem. A real production setup would want one
clear owner of the schedule, through a database lock or an actual queue,
instead of relying on every instance racing safely. Building the simple
version first and being able to explain exactly where it stops being
enough is a more convincing signal than pretending a fifteen line
`setInterval` is production infrastructure.

**Monthly and weekly intervals use calendar math, not a fixed number of
minutes.** A tempting shortcut is to say "monthly equals 43,200 minutes"
and add that fixed number each time. That drifts. Months are not all the
same length, so a transfer scheduled for the 31st would slide earlier and
earlier over the year. Instead, the next run date is computed by asking
JavaScript's own `Date` object to add one to the month field directly,
which correctly rolls January 31st into early March rather than landing
in a nonexistent February 31st. This is a small detail, but it is the
kind of correctness question that separates code that happens to work in
a demo from code that would misbehave the first time someone scheduled a
payment on the 31st.

**The spending chart and the CSV export were both built without adding a
new dependency.** Neither a charting library nor a CSV library was pulled
in. The chart is plain HTML bars sized by percentage inside a fixed track,
using one categorical color per expense category from a small validated
palette, and one consistent color for the single series in the by-month
view. The CSV is a small function that escapes commas, quotes, and
newlines by hand and joins rows with the standard CRLF line ending. Both
are simple enough that a dependency would have cost more in bundle size
and unfamiliar API surface than it saved in code written. That is a
judgment call, not a rule, and a real production dashboard with a dozen
chart types would reasonably reach for a charting library instead.

**The demo data is a year of history generated with a seeded random
number generator, not `Math.random()`.** The seed script (`seed.ts`)
builds roughly 250 transactions spread across real calendar dates over
the past year: biweekly paychecks, weekly groceries, monthly rent, and
so on, with amounts drawn from a small seedable pseudo-random generator
(a `mulberry32` implementation) instead of true randomness. That one
choice means running `npm run seed` twice produces the exact same year
of activity both times, which is what let the amount ranges be tuned
once against a known sequence and trusted not to accidentally push an
account negative on some future run. It is also just a better property
for a demo fixture to have: reproducible data is easier to reason about,
describe, and debug than data that is different every time you look at it.

**Backdating the demo data happens outside the core ledger function, not
inside it.** `postTransaction` never accepts a caller-supplied timestamp,
on purpose, since that is what stops any client from ever being able to
lie about when something really happened. The seed script still needs a
year of realistically-dated history, so it posts every transaction
through the normal function first, exactly like a real transfer would be,
and only afterward reaches directly into the database with Prisma to set
that row's `createdAt` to the intended historical date. The invariant the
public function protects is never weakened. Only a script that already
has direct database access, and is clearly not something a client could
ever call, gets to backdate anything.

**The balance chart and the statement table deliberately stopped sharing
one data window once the seed data grew.** Earlier, the chart was built
to reuse the exact same handful of rows as the statement table below it,
specifically so every value the chart's tooltip could show was also a
visible row in that table, which matters for accessibility: a tooltip
should enhance a value that is reachable another way, not be the only way
to reach it. A year of history broke that pairing, since showing all of
it in the table would make the table unusable, but a chart with hundreds
of points on it renders fine. The fix was to let them diverge on purpose:
the table stays capped at forty rows so it reads as "recent activity,"
the chart fetches a much larger window so a full year is visible, and the
CSV export, which already contains a whole account's history, becomes the
thing that keeps every charted value reachable outside the chart. Same
principle, different mechanism, once the scale of the data changed
what the first mechanism could reasonably do.

**A sixth expense category would have silently broken the chart's colors,
so it gets folded into "Other" instead of cycling.** The spending-by-category
chart assigns one color per category from a small palette validated for
colorblind separation, with five defined slots. The seed data ends up with
six expense categories once rent, groceries, and a one-off shopping account
are added. Cycling back to the first color for a sixth category would have
made two unrelated categories share a color, which defeats the entire point
of a validated categorical palette. Instead, once there are more categories
than colors, the chart keeps the top four by total and sums everything past
that into a single muted "Other" bar. This was already a latent bug before
the seed data changed, reachable any time a real user created a sixth
expense account by hand; the seed data just made it obvious enough to fix.

## Why each library was chosen

**Express** for the API framework, mainly because it is the tool most
people reviewing this project will already know, and because this project
does not need anything more opinionated. A handful of routes and one
error handling middleware did not call for a heavier framework.

**Prisma** as the database layer, because it gives you a real, typed
schema file that doubles as documentation of the data model, generates
migrations automatically, and produces a fully typed client so a typo in a
field name is a compile error instead of a runtime one. It also supports
SQLite and Postgres from the same schema with only the provider line
changing, which is exactly what let this project run locally with zero
setup while staying honest about what a production deployment would use.

**SQLite for local development, with a schema that is Postgres ready.**
The honest reason is that the machine this was built on did not have
Docker or Postgres installed. Rather than treat that as a blocker, it
became a real design decision, use only column types that behave the same
way on both engines, so switching providers later is a one line change and
not a rewrite. If asked, this is worth explaining exactly as it happened,
since it is a realistic example of working around an environment
constraint without cutting a corner in the actual design.

**Zod** for request validation, because it lets you describe the shape of
an incoming request once and get both the runtime check and the
TypeScript type from that single definition, instead of writing validation
logic and a matching interface separately and having them drift apart.

**The `ws` library** for the WebSocket server, because the real time
feature here is simple, broadcast a small event to every connected client
when something changes. That does not need a heavier messaging system,
just a plain WebSocket connection and a loop over connected clients.

**Vitest** for testing, because it is fast, has a syntax almost identical
to Jest so it is easy for anyone to read, and integrates cleanly with a
Vite based frontend and TypeScript backend without extra configuration.

**React with Vite, and no UI library.** The dashboard is small enough that
adding a component library or a state management library would have added
more explaining than value. A handful of `useState` and `useEffect` hooks,
a small WebSocket hook with reconnect logic, and one fetch based API
client cover everything the app needs.

**TypeScript everywhere**, backend and frontend, so that the shape of a
transaction, an entry, or an account is defined once and the compiler
catches it if the frontend and backend ever disagree about what a field is
called or what type it is.

## What the tests actually prove

The test suite is intentionally small and aimed directly at the rules that
matter, rather than testing every route for its own sake. There are
thirteen tests across two files. Six of them are described above, covering
the core ledger: an unbalanced transaction gets rejected, an overdraw
attempt gets rejected without changing the balance, a repeated idempotency
key returns the original result instead of posting twice, a reused key
with a different body is a conflict, and a reversal restores the exact
prior balance and voids the original.

The other seven cover the recurring transfer scheduler, and they are worth
knowing well because they test a background job without ever waiting on a
real timer. The sweep function, `runDueRecurringTransfers`, takes the
current time as a plain argument instead of always reading the system
clock, so a test can hand it any moment it wants and get a deterministic
result. One test hands it a due transfer and confirms it posts and the
next due date moves forward correctly. One test calls the sweep twice for
the exact same due time, simulating two overlapping sweeps or a crash
between posting and recording that it posted, and confirms only one
transaction was actually created. One test points a recurring transfer at
an account with no money and confirms the occurrence is recorded as
failed without crashing the sweep or retrying forever. One test confirms
a paused recurring transfer is skipped entirely. Two more confirm the
calendar math directly: adding a month to January 31st lands in March,
not February, and adding a week lands exactly seven days later.

If asked why there are not more tests, a fair answer is that these
thirteen cover every invariant the system promises, for both the ledger
and the scheduler. Adding tests for things like "can you create an
account" would mostly be testing Prisma and Express, not this project's
own logic.

There was also a manual verification pass done with a headless browser,
actually driving the React form, watching a live balance update over the
WebSocket, triggering the overdraft error and confirming it displayed
correctly, and checking the layout in both light and dark mode. That is
worth mentioning too, since automated tests alone do not prove a frontend
actually renders and behaves correctly.

## What was left out, and why that is a defensible choice

There is no authentication or login. Adding a user model and a login flow
would not touch the accounting logic at all, it would just add an
`ownerId` column and a layer of middleware in front of the existing
routes. Leaving it out kept the project focused on the part that was
actually the point.

There is no support for one transaction spanning multiple currencies. A
real implementation of that needs an explicit foreign exchange rate and a
separate pair of entries to represent the conversion, which is a
reasonable next feature but is really its own small project rather than a
missing piece of this one.

The statement calculation recalculates from full history on every request
rather than maintaining a running balance column. This was already covered
above, but it is worth having ready as its own answer, since "what would
you change before this went to production" is a common follow up
question, and this is a specific, technical answer rather than a vague
one.

## Questions you might get asked, and how to answer them

**Why did you build this instead of something else?**
Because it demonstrates correctness under constraints that actually matter
in finance, not just that you can call an API and show data on a page.

**Walk me through what happens if two requests try to spend the last
dollar in an account at the same time.**
Both requests would try to check the balance and write their entries
inside a database transaction. Locally, SQLite serializes all writers, so
this cannot actually happen concurrently in this project as it stands. In
production on Postgres, the balance check and the write need to happen
under a row lock, using `SELECT ... FOR UPDATE` on the accounts involved,
ordered consistently by id to avoid a deadlock between two transfers that
touch the same two accounts in opposite order. That is called out directly
in the code as the one thing a Postgres deployment needs to add.

**Why not just use a decimal or float type for money?**
Floating point numbers cannot represent most decimal fractions exactly in
binary, so small rounding errors accumulate. Using a decimal type avoids
that specific problem but still adds rounding rules you have to think
about for division. Storing money as an integer count of the smallest unit
of currency, cents for dollars, sidesteps the whole category of problem.
It is also the same approach real payment platforms use.

**How do you prevent someone from being charged twice if their request
times out and they retry?**
That is what the idempotency key is for. The client generates a unique key
per attempt at an action, not per network request, and sends it along.
If the same key comes back, the server recognizes it already handled that
exact request and returns the original result instead of doing the work
again.

**What was the hardest part?**
A fair, specific answer is getting the balance direction rule right for
every account type. It is easy to write the debit and credit logic for a
checking account since it behaves the way most people expect money to
behave, but equity, revenue, and liability accounts increase with a
credit, which feels backwards until you have it explained. Getting one
function to correctly express that rule for every account type, and then
proving it with a test that posts money into an equity account and checks
the sign came out right, took more care than it looks like from the
outside.

**How would this scale to a real bank's transaction volume?**
The database would move to Postgres with proper row locking as described
above. The statement endpoint would stop recalculating from full history
and instead maintain a running balance that updates as each entry is
written. Reads would likely be served from a replica separate from the
one handling writes. None of that changes the core rule, that a
transaction is a balanced set of entries and the balance is derived from
history, it only changes how efficiently that rule is enforced at scale.

**Why no authentication?**
The project's purpose was to demonstrate the ledger's own correctness, not
to build a full product. Authentication is a well understood, separate
concern that would not change anything about how the accounting invariants
work, so it was left out to keep the project focused.

**How does the recurring transfer scheduler avoid posting the same
transfer twice?**
Every scheduled occurrence gets its own idempotency key built from the
recurring transfer's id and its exact due timestamp. The sweep posts
through the same function a manual transfer uses, which already checks
that key before writing anything. So even if the sweep runs twice for the
same due time, or the process restarts between posting the transaction
and recording that it had, the second attempt recognizes the key and
returns the original result instead of posting again. The scheduling part
does not have to be perfectly reliable, because the posting part already
is.

**How would you run that scheduler if you had multiple servers?**
As written, it would not be safe to just run more than one copy, since
every copy would sweep for due transfers at the same moment. The
idempotency key stops them from double-posting the same occurrence, but
that is a safety net, not a real design. The honest fix is to give the
schedule one clear owner, either a distributed lock so only one instance
sweeps at a time, or moving the whole thing to a real job queue like
BullMQ, which is built to hand each job to exactly one worker.

**Why did you build the chart yourself instead of using a charting
library?**
The dashboard needed exactly two small bar charts, and plain HTML divs
sized by percentage were enough to build them correctly, including a
category color scheme picked from a small palette validated for
colorblind-safe contrast. A charting library would have added a real
amount of bundle size and a new API to learn for something this simple.
That tradeoff flips for a dashboard with many chart types, and it is worth
saying that out loud rather than implying every chart should always be
hand rolled.

**Why does the CSV export return a file from the server instead of
building it in the browser?**
The browser already has the statement data it needs to show the table, so
building the CSV client side would have worked too. The server does it
instead so the export is not limited to whatever page of results the
browser happened to have loaded, and so the download gets a real
`Content-Disposition` header and filename, which is what makes a browser
treat it as a file to save instead of text to navigate to.

## A short glossary, in case it helps

**Double entry accounting** means every transaction is recorded as at
least two entries, a debit and a credit of equal size, so the books always
balance by construction rather than by hoping nobody made a mistake.

**Debit and credit** do not mean "subtract" and "add" the way people use
those words casually. Whether a debit increases or decreases a balance
depends entirely on the type of account. It increases assets and expenses,
and decreases liabilities, equity, and revenue. Credit is the reverse.

**Idempotency** means that doing the same operation more than once has the
same effect as doing it once. An idempotency key is how a client tells a
server "this is attempt number two of the same request, not a new one."

**ACID** is the set of guarantees a database transaction is supposed to
give you, that it is atomic (all or nothing), consistent (it leaves the
data in a valid state), isolated (concurrent transactions do not see each
other's half finished work), and durable (once it is committed, it stays
committed). The balance check and the entry insert in this project happen
inside one ACID transaction specifically so they cannot be split apart by
a crash or a race with another request.

**Row lock** is a way of telling a database "nobody else can read or write
this specific row until I am done with it," which is what prevents the
double spend scenario described above from happening in production.
