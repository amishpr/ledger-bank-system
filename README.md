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
without touching the original record. Under the hood, every one of those
actions goes through the same core function that checks the accounting math
before anything is written to the database.

## Features

- Double entry accounting enforced on every transaction, not just assumed
- All money stored and transmitted as whole cents using BigInt, so there is
  no floating point rounding anywhere in the system
- Idempotent transaction posting using client supplied keys, so retrying a
  request after a timeout never creates a duplicate transfer
- Overdraft protection on checking and savings style accounts
- Reversals instead of edits or deletes, so the ledger stays a true history
- Live updates over WebSockets, so every connected browser tab sees new
  activity the moment it is posted
- A REST API with input validation and clear error codes
- An automated test suite covering the accounting rules directly

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
│  │  ├─ schema.prisma        Account, Transaction, Entry, AuditLog models
│  │  ├─ migrations/          SQL migration history
│  │  └─ seed.ts              Creates demo accounts and sample activity
│  ├─ src/
│  │  ├─ ledger/              Core accounting logic and its tests
│  │  ├─ api/                 Express routes and request validation
│  │  ├─ realtime/            WebSocket broadcast
│  │  ├─ db.ts                Prisma client
│  │  └─ index.ts             App entry point
│  └─ package.json
├─ web/                       React dashboard
│  ├─ src/
│  │  ├─ components/          Accounts panel, transfer form, statement, feed
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

There are four tables.

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

A WebSocket server runs alongside the API at `ws://localhost:4000/ws` and
broadcasts a message any time a transaction is posted or reversed, which is
what lets the dashboard update without polling.

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

This creates a set of demo accounts belonging to two fictional people,
funds them with opening balances, and posts a handful of sample
transactions, including one reversal, so you can see what a corrected
transaction looks like right away.

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

More detail on these decisions, along with the reasoning behind the rest
of the project, is in [WALKTHROUGH.md](WALKTHROUGH.md).
