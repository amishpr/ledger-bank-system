# Ledger

Ledger is a small core banking system built to show how a real double entry
ledger works under the hood. It has a Node and TypeScript API backed by a
Prisma database, and a React dashboard that updates in real time as money
moves between accounts. The project is not trying to be a full bank. It is
trying to get the hard parts right: every transaction has to balance, money
is never represented as a float, retried requests never double post, and
nothing already posted is ever edited or deleted.

![Dashboard screenshot](docs/screenshot.png)

## What this project actually does

You can create accounts (checking, savings, equity, revenue, expense), move
money between them with a transfer form, watch balances update live in a
second browser tab without refreshing, look at a running statement for any
account, and reverse a past transaction to see how a correction is handled
without touching the original record. You can also schedule a transfer to
repeat on its own, download an account's statement as a CSV file, and see
a chart of where money has been going by category and by month. Under the
hood, every money movement, whether typed into the transfer form or posted
automatically by the scheduler, goes through the same core function that
checks the accounting math before anything is written to the database.

## Features

- Double entry accounting enforced on every transaction, not just assumed
- All money stored and transmitted as whole cents using BigInt, so there is
  no floating point rounding anywhere in the system
- Idempotent transaction posting using client supplied keys, so retrying a
  request after a timeout never creates a duplicate transfer
- Overdraft protection on checking and savings style accounts
- Reversals instead of edits or deletes, so the ledger stays a true history
- Recurring transfers posted by a background job on the server, on a
  schedule you pick, with the same idempotency guarantee protecting each
  occurrence
- A spending breakdown chart, by category and by month, built from the
  same entries the ledger already records
- An interactive balance history chart per account, with axis labels, a
  hover crosshair, and a tooltip, built as plain SVG with no charting
  library
- CSV export of any account's full statement
- Live updates over WebSockets, so every connected browser tab sees new
  activity the moment it is posted, including transfers the scheduler
  posts on its own
- A REST API with input validation and clear error codes
- An automated test suite covering the accounting rules and the scheduler
  directly

## Tech stack

**Backend:** Node.js, TypeScript, Express, Prisma, SQLite for local
development with a Postgres ready schema, Zod for request validation, the
`ws` library for WebSockets, and Vitest for testing.

**Frontend:** React, TypeScript, and Vite, styled with plain CSS. No UI
component library and no state management library were used on purpose,
since the app is small enough that a few React hooks are enough.

If you want the reasoning behind each of these choices, that is covered in
detail in [WALKTHROUGH.md](WALKTHROUGH.md).

## Project structure

```
ledger-app/
├─ server/                    Express API
│  ├─ prisma/
│  │  ├─ schema.prisma        Account, Transaction, Entry, RecurringTransfer, AuditLog
│  │  ├─ migrations/          SQL migration history
│  │  └─ seed.ts              Creates demo accounts and sample activity
│  ├─ src/
│  │  ├─ ledger/              Core accounting logic, recurring transfers, insights, and their tests
│  │  ├─ api/                 Express routes and request validation
│  │  ├─ realtime/            WebSocket broadcast
│  │  ├─ scheduler.ts         Background job that posts due recurring transfers
│  │  ├─ db.ts                Prisma client
│  │  └─ index.ts             App entry point
│  └─ package.json
├─ web/                       React dashboard
│  ├─ src/
│  │  ├─ components/          Accounts panel, transfer form, statement, recurring transfers, spending chart, feed
│  │  ├─ api/                 Fetch client and shared types
│  │  ├─ money.ts             Formatting and parsing for cent amounts
│  │  ├─ ledgerMath.ts         Client side mirror of the balance direction rule
│  │  └─ useLedgerSocket.ts   WebSocket hook with reconnect
│  └─ package.json
├─ docker-compose.yml         Postgres for a production style local run
├─ package.json               Root scripts that run both apps together
└─ README.md
```

## How the data model works

There are five tables.

**Account** has a name, a type (asset, liability, equity, revenue, or
expense), and a currency. The type matters because it decides which
direction increases the account's balance. This is standard double entry
accounting: a debit increases an asset or expense account and decreases a
liability, equity, or revenue account, while a credit does the opposite.

**Transaction** is the record of something happening, like a transfer or a
fee. It has a description, a status of posted or voided, and an optional
idempotency key.

**Entry** is one leg of a transaction. A transfer between two accounts is
always two entries: a debit on one account and a credit on the other, for
the same amount. A transaction can have more than two entries if it touches
more than two accounts, as long as the debits and credits still add up.

**RecurringTransfer** is a standing instruction to post the same transfer
on a schedule (every minute for demo purposes, daily, weekly, or monthly).
It does not touch the ledger itself. It only tells the background job in
`scheduler.ts` what to post and when, and the job posts it through the
exact same function a manual transfer uses.

**AuditLog** records every account creation and every transaction posted or
reversed, so there is a plain trail of what happened and when, separate
from the entries themselves.

Balances are never stored as a column anywhere. A balance is always the sum
of an account's entries, calculated on request. This means there is exactly
one source of truth for how much money is in an account: its history.

## API reference

All amounts are sent and received as strings representing whole cents, for
example `"1050"` for ten dollars and fifty cents. The base URL in local
development is `http://localhost:4000`.

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Basic health check |
| GET | `/accounts` | List every account with its current balance |
| POST | `/accounts` | Create an account. Body: `name`, `type`, optional `currency` |
| GET | `/accounts/:id` | Get one account and its balance |
| GET | `/accounts/:id/statement?limit=25` | Recent entries for an account with a running balance |
| POST | `/transactions` | Post a transaction. Body: `description`, `entries` (array of `accountId`, `direction`, `amountMinor`), optional `idempotencyKey` |
| POST | `/transactions/:id/reverse` | Reverse a posted transaction. Body: optional `note` |
| GET | `/accounts/:id/statement/export` | Download the account's full statement as a CSV file |
| GET | `/recurring-transfers` | List every scheduled transfer |
| POST | `/recurring-transfers` | Schedule one. Body: `description`, `fromAccountId`, `toAccountId`, `amountMinor`, `interval` (`EVERY_MINUTE`, `DAILY`, `WEEKLY`, or `MONTHLY`), optional `startAt` |
| POST | `/recurring-transfers/:id/toggle-active` | Pause it if it is running, or resume it if it is paused |
| DELETE | `/recurring-transfers/:id` | Cancel it. Transfers it already posted are not undone |
| GET | `/insights/spending` | Total spending grouped by expense account and by month |

A WebSocket server runs alongside the API at `ws://localhost:4000/ws` and
broadcasts a message any time a transaction is posted or reversed, whether
that happened from the transfer form or from the scheduler, which is what
lets the dashboard update without polling.

A background job inside the same server process checks every 15 seconds
(configurable, see below) for any recurring transfer that is due, and
posts it through the normal transaction path, invariants and all. If a
scheduled transfer would overdraw its account, that occurrence is skipped
and recorded as failed rather than retried forever, and it tries again on
its next scheduled occurrence.

Errors come back as JSON with an `error` code and a human readable
`message`, for example `INSUFFICIENT_FUNDS`, `UNBALANCED_TRANSACTION`, or
`IDEMPOTENCY_CONFLICT`, along with an appropriate HTTP status code.

## Prerequisites

You need Node.js. This was built and tested on Node 24, but anything 20 or
newer should work fine. npm comes bundled with Node, so no separate install
is needed there. Docker is only needed if you want to run the database on
Postgres instead of the default SQLite setup, and is entirely optional.

## Setup and installation

Clone or download the project, then from the `ledger-app` folder install
dependencies for all three package.json files. They are kept separate on
purpose so the API and the frontend can be deployed independently later.

```bash
npm install --prefix server
npm install --prefix web
npm install
```

The last command installs the root's only dependency, a small tool called
`concurrently` that lets you start both the API and the frontend with one
command.

Next, set up the environment files. Each app has a `.env.example` you can
copy directly, since the defaults already point the frontend at the local
API.

```bash
cp server/.env.example server/.env
cp web/.env.example web/.env
```

Create the local database and apply the schema:

```bash
npm run prisma:migrate --prefix server
```

This creates a SQLite file at `server/dev.db` and generates the Prisma
client. You will be prompted for a migration name the first time only if
one does not already exist in the repository.

Load some demo data so the dashboard is not empty:

```bash
npm run seed
```

This creates a set of demo accounts belonging to two fictional people and
generates a full year of realistic activity for them: biweekly paychecks,
weekly groceries, monthly rent and subscriptions, dining out, a handful
of large one-off purchases, and a monthly transfer to savings, all spread
across real calendar dates ending today. It's generated with a seeded
random number generator, so re-running the seed always produces the same
year rather than a different one each time. It also includes one
reversal so you can see what a corrected transaction looks like, and
schedules one recurring transfer that is already due, so if you start
the app right after seeding, you can watch the background job post its
first occurrence within its first sweep instead of having to take it on
faith.

The year of history is what gives the balance chart and the spending
breakdown something real to show. The statement table only displays the
most recent 40 entries, since a year of activity would make it
unreadable, but the balance chart pulls a much larger window on its own
so the full year is visible there, and the CSV export always has
everything regardless of what either view is currently showing.

## Running the app

From the `ledger-app` root:

```bash
npm run dev
```

This starts the API on port 4000 and the frontend on port 5173 at the same
time, with labeled, color coded output for each. Open
`http://localhost:5173` in your browser.

If you would rather run them separately, in one terminal run
`npm run dev --prefix server` and in another run `npm run dev --prefix web`.

If you're using VS Code, the project ships with `.vscode/tasks.json` and
`.vscode/launch.json`. Open the Run and Debug panel and pick "Debug Full
Stack (server + browser)" to start the API under the Node debugger and
open the dashboard in Chrome with breakpoints working on both sides, or
use the Command Palette's "Tasks: Run Task" for things like `server: dev`,
`web: dev`, `server: test`, or `db: seed` without leaving the editor.

## Running the tests

```bash
npm test
```

This runs the backend's Vitest suite, which sets up its own separate SQLite
database so it never touches your development data. The tests check the
core accounting rules directly: a transaction that does not balance gets
rejected, an entry that would overdraw an account gets rejected without
changing the balance, a retried request with the same idempotency key
returns the original result instead of posting twice, reusing a key with a
different payload is treated as a conflict, and reversing a transaction
correctly restores the prior balance and marks the original as voided.

## Building for production

```bash
npm run build
```

This compiles the API's TypeScript to `server/dist` and builds the
frontend into `web/dist` as static files ready to be served by any static
host.

## Switching from SQLite to Postgres

The schema only uses types that exist in both databases, so moving to
Postgres does not require changing any application code, only the
datasource configuration.

1. Start Postgres with `docker compose up -d`, which reads the included
   `docker-compose.yml` and starts a Postgres container with a database
   called `ledger`.
2. In `server/prisma/schema.prisma`, change the datasource provider from
   `sqlite` to `postgresql`.
3. In `server/.env`, set `DATABASE_URL` to
   `postgresql://ledger:ledger@localhost:5432/ledger`.
4. Run `npm run prisma:migrate --prefix server` again to apply the schema
   to the new database.

## Environment variables

**server/.env**

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `file:./dev.db` | Where Prisma connects. Point this at Postgres for production |
| `PORT` | `4000` | Port the API and WebSocket server listen on |
| `CORS_ORIGIN` | `http://localhost:5173` | The single origin allowed to call the API from a browser |
| `SCHEDULER_INTERVAL_MS` | `15000` | How often the background job checks for due recurring transfers |

**web/.env**

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_URL` | `http://localhost:4000` | Base URL the dashboard uses for REST calls |
| `VITE_WS_URL` | `ws://localhost:4000/ws` | URL the dashboard connects to for live updates |

## What is intentionally left out

A few things were left out on purpose rather than by accident, and it is
worth being upfront about them.

There is no login or authentication. This is a demo of the ledger itself,
not a multi user product, and adding accounts owned by specific users would
not change anything about how the accounting logic works.

There is no support for a single transaction spanning multiple currencies.
Every entry in a transaction has to share one currency. Real currency
conversion needs its own pair of entries against an FX account and its own
set of rules, which felt like a separate project rather than an extension
of this one.

The account statement is calculated by replaying an account's full entry
history every time it is requested. That is fine at the scale of a demo
and would not be fine at the scale of a real bank account with years of
history. A production version of this would keep a running balance that
updates as entries are written, instead of recalculating it from scratch
on every request.

The scheduler is a single `setInterval` inside the same process as the
API, not a separate worker or job queue. That is fine as long as only one
copy of the server is running, which is true here. It would not be fine
the moment you run two copies of the server for reliability, since both
would sweep for due transfers at the same time. The idempotency key on
each occurrence means they could not both post it twice, but a real
production setup would still want one clear owner of the schedule, using
something like a database lock or a proper job queue, rather than relying
on every instance racing safely.

More detail on these decisions, along with the reasoning behind the rest
of the project, is in [WALKTHROUGH.md](WALKTHROUGH.md).
