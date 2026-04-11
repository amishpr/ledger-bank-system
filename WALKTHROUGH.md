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

## The data model, explained plainly

There are four tables: Account, Transaction, Entry, and AuditLog.

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
matter, rather than testing every route for its own sake. There are six
tests. One confirms an unbalanced transaction gets rejected. One confirms
that trying to overdraw an asset account gets rejected without changing
its balance. One confirms that posting the same request twice with the
same idempotency key returns the original transaction instead of creating
a second one. One confirms that reusing a key with a different request
body is treated as a conflict. And two cover reversal, confirming that
reversing a transaction restores the account to its exact prior balance
and marks the original as voided, and that trying to reverse something
already voided is rejected.

If asked why there are not more tests, a fair answer is that these six
cover every invariant the system promises. Adding tests for things like
"can you create an account" would mostly be testing Prisma and Express,
not this project's own logic.

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
