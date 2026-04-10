# Ledger

A small double-entry core-banking ledger: a Node/TypeScript API backed by
Postgres-ready Prisma, and a React dashboard that updates in real time over
WebSockets. The point of this project isn't the CRUD — it's the invariants
a real ledger has to hold, and the failure modes most toy finance projects
skip.

![Dashboard screenshot](docs/screenshot.png)

## Why a ledger, not another stock tracker

Every fintech company — a bank, Ramp/Brex-style corporate cards, a
payments processor — has some version of this system at its core: an
append-only record of money movements that must never lose a cent, never
double-post a retried request, and never be edited after the fact. That's
also a common systems-design interview topic at these companies. Building
one is a more direct signal of "I understand how money-handling code has
to behave" than another price-prediction model.

## The invariants this enforces

- **Every transaction balances.** A transaction is a set of entries; it is
  rejected unless `sum(debits) == sum(credits)`. See
  [`ledgerService.ts`](server/src/ledger/ledgerService.ts).
- **No floats, anywhere.** Money is stored and transmitted as `BigInt`
  minor units (cents), both in the database and across the API boundary
  (JSON numbers get replaced with strings for amounts). A JS `number`
  cannot represent `$0.1 + $0.2` exactly — this ledger never gives it the
  chance to try.
- **Nothing is ever mutated or deleted.** Correcting a transaction means
  posting a new, opposite transaction (`reverseTransaction`) and marking
  the original `VOIDED`. The original entries stay in the table forever —
  the same reason a real bank statement shows a reversal as a new line,
  not an edited one.
- **Retries are safe.** Every transaction can carry a client-supplied
  `idempotencyKey`. Replaying the same request returns the original result
  instead of double-posting; reusing the key with a *different* payload is
  rejected as a conflict (the same pattern Stripe's API uses).
- **An account can't go negative it isn't allowed to.** Asset accounts
  (checking/savings) are overdraft-protected — posting an entry that would
  take one below zero is rejected with `INSUFFICIENT_FUNDS`, checked
  inside the same DB transaction that writes the entries.

## Architecture

```
ledger-app/
├─ server/     Express + TypeScript + Prisma
│  ├─ prisma/schema.prisma   Account / Transaction / Entry / AuditLog
│  ├─ src/ledger/            domain logic (the interesting code)
│  ├─ src/api/                HTTP routes + zod validation
│  └─ src/realtime/ws.ts      broadcasts posted/reversed transactions
└─ web/        React + TypeScript (Vite)
   ├─ src/components/         accounts grid, transfer form, statement, live feed
   └─ src/useLedgerSocket.ts  WebSocket hook with reconnect/backoff
```

**Data model.** `Account` has a `type` (asset/liability/equity/revenue/
expense) that determines its normal balance side — textbook double-entry
accounting. `Transaction` is the atomic unit; `Entry` rows are its legs.
Balances are never stored — they're always derived by summing an
account's entries, so there is exactly one path through which the ledger's
state can drift from its history: none.

**Concurrency.** All the invariant checks and writes for one transaction
happen inside a single `prisma.$transaction`. Locally this runs on SQLite,
which serializes all writers behind one lock, so it's race-free as-is. The
one thing a production Postgres deployment should add on top: `SELECT ...
FOR UPDATE` on the affected accounts (ordered by `id`, to avoid deadlocks)
before recomputing balances in `applyEntries`.

**Why SQLite in dev.** The schema uses only types that map cleanly onto
both engines (`String`, `BigInt`, `DateTime`, enums), so switching
`provider` in `schema.prisma` to `"postgresql"` and running migrations
against the `docker-compose.yml` Postgres instance is a one-line change —
nothing in the domain logic depends on the engine. SQLite just means this
repo runs with zero external dependencies.

## Running it

```bash
npm install --prefix server
npm install --prefix web
npm install # root, for the combined dev script

npm run --prefix server prisma:migrate   # create dev.db
npm run seed                             # demo accounts + activity

npm run dev                              # runs API (:4000) + web (:5173)
```

Then open http://localhost:5173. The dashboard shows live balances, a
transfer form, a per-account statement with running balances, a "Reverse"
action on any posted transaction, and a live activity feed fed by the
server's WebSocket broadcasts.

To run against real Postgres instead: `docker compose up -d`, change
`provider = "postgresql"` in `server/prisma/schema.prisma`, point
`DATABASE_URL` at `postgresql://ledger:ledger@localhost:5432/ledger`, and
re-run `prisma migrate dev`.

## Tests

```bash
npm test --prefix server
```

Covers the invariants above directly: unbalanced transactions are
rejected, an overdraft is rejected without touching the balance, an
idempotent replay doesn't double-post, a reused idempotency key with a
different payload is a conflict, and a reversal restores the prior
balance while voiding the original.

## What's deliberately out of scope

- **Auth.** There's no login wall — this is a demo dashboard, not a
  multi-tenant product. Adding it would mean an `Account.ownerId` and a
  auth middleware; the ledger's invariants don't change either way.
- **Multi-currency transactions.** Entries in one transaction must share a
  currency; converting between currencies would need an explicit FX-rate
  entry pair, which is a good follow-up but adds a second set of
  invariants this version doesn't need to prove the concept.
- **A materialized running balance.** `getStatement` replays an account's
  full entry history to compute running balances, which is fine at demo
  scale and wrong at production scale — a real system would maintain a
  running-balance column (or use a window function) instead of recomputing
  it per request.
