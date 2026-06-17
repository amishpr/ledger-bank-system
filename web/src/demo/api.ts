import type { LedgerApi } from "../api/contract";
import { saveBlob } from "../api/download";
import { ApiRequestError } from "../api/errors";
import type { Account, PostResult, RecurringTransfer, SpendingBreakdown, StatementLine } from "../api/types";
import { broadcast } from "./bus";
import { buildStatementCsv } from "./csv";
import { DemoLedgerError } from "./errors";
import { getSpendingBreakdown } from "./insights";
import * as ledger from "./ledger";
import * as recurring from "./recurring";
import { startDemoScheduler } from "./scheduler";
import { seedDemoDb } from "./seed";
import { toWire } from "./wire";

// The demo's version of the Express layer: it validates and converts the
// request the same way the Zod schemas do, calls the same service
// functions, broadcasts the same events the routes broadcast, serializes
// the response the same way the app's `json replacer` does, and turns a
// ledger error into the same ApiRequestError the HTTP client would throw.

// A real request over localhost takes a few milliseconds. Keeping a small
// delay here means the dashboard's pending and disabled states actually
// get exercised in the demo instead of every action resolving instantly.
const SIMULATED_LATENCY_MS = 80;

let ready: Promise<void> | undefined;

function ensureReady(): Promise<void> {
  ready ??= seedDemoDb().then(startDemoScheduler);
  return ready;
}

async function handle<T>(work: () => T | Promise<T>): Promise<T> {
  await ensureReady();
  await new Promise((resolve) => setTimeout(resolve, SIMULATED_LATENCY_MS));
  try {
    return await work();
  } catch (err) {
    if (err instanceof DemoLedgerError) {
      throw new ApiRequestError(err.code, err.message);
    }
    throw err;
  }
}

export const demoApi: LedgerApi = {
  listAccounts: () => handle(() => toWire<Account[]>(ledger.listAccounts())),

  createAccount: (input) => handle(() => toWire<Account>(ledger.createAccount(input))),

  getStatement: (accountId, limit = 25) =>
    handle(() => toWire<StatementLine[]>(ledger.getStatement(accountId, limit))),

  postTransaction: (input) =>
    handle(async () => {
      const result = await ledger.postTransaction({
        description: input.description,
        idempotencyKey: input.idempotencyKey,
        entries: input.entries.map((e) => ({
          accountId: e.accountId,
          direction: e.direction,
          amountMinor: BigInt(e.amountMinor),
        })),
      });
      if (!result.replayed) {
        broadcast({
          type: "transaction.posted",
          transaction: result.transaction,
          affectedAccountIds: result.affectedAccountIds,
        });
      }
      return toWire<PostResult>(result);
    }),

  reverseTransaction: (transactionId, note) =>
    handle(async () => {
      const result = await ledger.reverseTransaction(transactionId, note);
      broadcast({
        type: "transaction.reversed",
        transaction: result.transaction,
        affectedAccountIds: result.affectedAccountIds,
      });
      return toWire<PostResult>(result);
    }),

  listRecurringTransfers: () => handle(() => toWire<RecurringTransfer[]>(recurring.listRecurringTransfers())),

  createRecurringTransfer: (input) =>
    handle(() =>
      toWire<RecurringTransfer>(
        recurring.createRecurringTransfer({
          description: input.description,
          fromAccountId: input.fromAccountId,
          toAccountId: input.toAccountId,
          amountMinor: BigInt(input.amountMinor),
          interval: input.interval,
        }),
      ),
    ),

  toggleRecurringTransfer: (id) =>
    handle(() => toWire<RecurringTransfer>(recurring.toggleRecurringTransfer(id))),

  deleteRecurringTransfer: (id) => handle(() => recurring.deleteRecurringTransfer(id)),

  getSpendingBreakdown: () => handle(() => toWire<SpendingBreakdown>(getSpendingBreakdown())),

  exportStatementCsv: (accountId) =>
    handle(() => {
      const account = ledger.getAccount(accountId);
      const csv = buildStatementCsv(ledger.getStatement(accountId, Number.MAX_SAFE_INTEGER));
      const slug =
        account.name
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, "-")
          .replace(/(^-|-$)/g, "") || "account";
      saveBlob(`${slug}-statement.csv`, new Blob([csv], { type: "text/csv;charset=utf-8" }));
    }),
};
