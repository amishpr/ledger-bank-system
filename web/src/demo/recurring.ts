import type { RecurrenceInterval } from "../api/types";
import { broadcast } from "./bus";
import { accountNotFound, DemoLedgerError } from "./errors";
import { postTransaction } from "./ledger";
import { db, findAccount, nextId, type StoredRecurringTransfer } from "./store";

// Mirrors server/src/ledger/recurringService.ts, including the property
// that matters most: each occurrence posts through the same
// postTransaction path a manual transfer uses, with an idempotency key
// derived from the row id and the exact due time, so a repeated sweep
// cannot post the same occurrence twice.

// Real calendar math, not a fixed minute count: "every month" means the
// same day next calendar month, not exactly 43,200 minutes, which would
// slowly drift away from the calendar date a user actually picked.
export function computeNextRun(from: Date, interval: RecurrenceInterval): Date {
  const next = new Date(from);
  switch (interval) {
    case "EVERY_MINUTE":
      next.setMinutes(next.getMinutes() + 1);
      break;
    case "DAILY":
      next.setDate(next.getDate() + 1);
      break;
    case "WEEKLY":
      next.setDate(next.getDate() + 7);
      break;
    case "MONTHLY":
      next.setMonth(next.getMonth() + 1);
      break;
  }
  return next;
}

function withAccounts(item: StoredRecurringTransfer) {
  return {
    ...item,
    fromAccount: findAccount(item.fromAccountId),
    toAccount: findAccount(item.toAccountId),
  };
}

export function listRecurringTransfers() {
  return db.recurringTransfers.map(withAccounts);
}

export function createRecurringTransfer(input: {
  description: string;
  fromAccountId: string;
  toAccountId: string;
  amountMinor: bigint;
  interval: RecurrenceInterval;
  startAt?: Date;
}) {
  if (input.fromAccountId === input.toAccountId) {
    throw new DemoLedgerError("From and to accounts must be different", "INVALID_RECURRING_TRANSFER", 422);
  }
  if (input.amountMinor <= 0n) {
    throw new DemoLedgerError("Amount must be greater than zero", "INVALID_AMOUNT", 422);
  }

  const from = findAccount(input.fromAccountId);
  const to = findAccount(input.toAccountId);
  if (!from) throw accountNotFound(input.fromAccountId);
  if (!to) throw accountNotFound(input.toAccountId);
  if (from.type !== "ASSET" || to.type !== "ASSET") {
    throw new DemoLedgerError(
      "Recurring transfers only run between asset accounts, the same as a manual transfer",
      "INVALID_RECURRING_TRANSFER",
      422,
    );
  }

  const item: StoredRecurringTransfer = {
    id: nextId("rec"),
    description: input.description,
    fromAccountId: input.fromAccountId,
    toAccountId: input.toAccountId,
    amountMinor: input.amountMinor,
    interval: input.interval,
    active: true,
    nextRunAt: input.startAt ?? new Date(),
    lastRunAt: null,
    lastRunStatus: null,
    lastRunError: null,
    createdAt: new Date(),
  };
  db.recurringTransfers.push(item);
  return withAccounts(item);
}

function requireRecurringTransfer(id: string): StoredRecurringTransfer {
  const existing = db.recurringTransfers.find((r) => r.id === id);
  if (!existing) {
    throw new DemoLedgerError(`Recurring transfer ${id} not found`, "RECURRING_TRANSFER_NOT_FOUND", 404);
  }
  return existing;
}

export function toggleRecurringTransfer(id: string) {
  const existing = requireRecurringTransfer(id);
  existing.active = !existing.active;
  return withAccounts(existing);
}

export function deleteRecurringTransfer(id: string): void {
  const existing = requireRecurringTransfer(id);
  db.recurringTransfers.splice(db.recurringTransfers.indexOf(existing), 1);
}

// The scheduler's sweep. Exported on its own (rather than only reachable
// through a setInterval) so it can be driven directly and deterministically
// from a test, instead of a test having to wait on a real timer.
export async function runDueRecurringTransfers(now: Date = new Date()) {
  const due = db.recurringTransfers.filter((r) => r.active && r.nextRunAt.getTime() <= now.getTime());
  const results: { id: string; ok: boolean; error?: string }[] = [];

  for (const item of due) {
    const idempotencyKey = `recurring:${item.id}:${item.nextRunAt.toISOString()}`;
    try {
      const result = await postTransaction({
        description: item.description,
        idempotencyKey,
        entries: [
          { accountId: item.fromAccountId, direction: "CREDIT", amountMinor: item.amountMinor },
          { accountId: item.toAccountId, direction: "DEBIT", amountMinor: item.amountMinor },
        ],
      });

      item.lastRunAt = now;
      item.lastRunStatus = "SUCCESS";
      item.lastRunError = null;
      item.nextRunAt = computeNextRun(item.nextRunAt, item.interval);

      broadcast({
        type: "recurring.executed",
        recurringTransferId: item.id,
        transaction: result.transaction,
        affectedAccountIds: result.affectedAccountIds,
      });
      results.push({ id: item.id, ok: true });
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unknown error";
      // Skip this occurrence rather than retry every sweep. The same
      // failure (most often insufficient funds) would otherwise repeat
      // forever. It tries again on the next scheduled occurrence.
      item.lastRunAt = now;
      item.lastRunStatus = "FAILED";
      item.lastRunError = message;
      item.nextRunAt = computeNextRun(item.nextRunAt, item.interval);

      broadcast({ type: "recurring.failed", recurringTransferId: item.id, error: message });
      results.push({ id: item.id, ok: false, error: message });
    }
  }

  return results;
}
