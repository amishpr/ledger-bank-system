import type { RecurrenceInterval } from "@prisma/client";
import { prisma } from "../db.js";
import { broadcast } from "../realtime/ws.js";
import { AccountNotFoundError, LedgerError } from "./errors.js";
import { postTransaction } from "./ledgerService.js";

// Real calendar math, not a fixed minute count: "every month" means the
// same day next calendar month (JS Date handles the varying month length
// on its own), not exactly 43,200 minutes, which would slowly drift away
// from the calendar date a user actually picked.
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

export async function listRecurringTransfers() {
  return prisma.recurringTransfer.findMany({
    orderBy: { createdAt: "asc" },
    include: { fromAccount: true, toAccount: true },
  });
}

export async function createRecurringTransfer(input: {
  description: string;
  fromAccountId: string;
  toAccountId: string;
  amountMinor: bigint;
  interval: RecurrenceInterval;
  startAt?: Date;
}) {
  if (input.fromAccountId === input.toAccountId) {
    throw new LedgerError("From and to accounts must be different", "INVALID_RECURRING_TRANSFER", 422);
  }
  if (input.amountMinor <= 0n) {
    throw new LedgerError("Amount must be greater than zero", "INVALID_AMOUNT", 422);
  }

  const [from, to] = await Promise.all([
    prisma.account.findUnique({ where: { id: input.fromAccountId } }),
    prisma.account.findUnique({ where: { id: input.toAccountId } }),
  ]);
  if (!from) throw new AccountNotFoundError(input.fromAccountId);
  if (!to) throw new AccountNotFoundError(input.toAccountId);
  if (from.type !== "ASSET" || to.type !== "ASSET") {
    throw new LedgerError(
      "Recurring transfers only run between asset accounts, the same as a manual transfer",
      "INVALID_RECURRING_TRANSFER",
      422,
    );
  }

  return prisma.recurringTransfer.create({
    data: {
      description: input.description,
      fromAccountId: input.fromAccountId,
      toAccountId: input.toAccountId,
      amountMinor: input.amountMinor,
      interval: input.interval,
      nextRunAt: input.startAt ?? new Date(),
    },
    include: { fromAccount: true, toAccount: true },
  });
}

export async function toggleRecurringTransfer(id: string) {
  const existing = await prisma.recurringTransfer.findUnique({ where: { id } });
  if (!existing) throw new LedgerError(`Recurring transfer ${id} not found`, "RECURRING_TRANSFER_NOT_FOUND", 404);
  return prisma.recurringTransfer.update({
    where: { id },
    data: { active: !existing.active },
    include: { fromAccount: true, toAccount: true },
  });
}

export async function deleteRecurringTransfer(id: string) {
  const existing = await prisma.recurringTransfer.findUnique({ where: { id } });
  if (!existing) throw new LedgerError(`Recurring transfer ${id} not found`, "RECURRING_TRANSFER_NOT_FOUND", 404);
  await prisma.recurringTransfer.delete({ where: { id } });
}

// The scheduler's sweep. Exported on its own (rather than only reachable
// through a setInterval) so it can be driven directly and deterministically
// from a test, instead of a test having to wait on a real timer.
export async function runDueRecurringTransfers(now: Date = new Date()) {
  const due = await prisma.recurringTransfer.findMany({
    where: { active: true, nextRunAt: { lte: now } },
  });

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

      await prisma.recurringTransfer.update({
        where: { id: item.id },
        data: {
          lastRunAt: now,
          lastRunStatus: "SUCCESS",
          lastRunError: null,
          nextRunAt: computeNextRun(item.nextRunAt, item.interval),
        },
      });

      broadcast({
        type: "recurring.executed",
        recurringTransferId: item.id,
        transaction: result.transaction,
        affectedAccountIds: result.affectedAccountIds,
      });
      results.push({ id: item.id, ok: true });
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unknown error";
      // Skip this occurrence rather than retry every sweep - the same
      // failure (most often insufficient funds) would just repeat forever
      // otherwise. It tries again on the next scheduled occurrence.
      await prisma.recurringTransfer.update({
        where: { id: item.id },
        data: {
          lastRunAt: now,
          lastRunStatus: "FAILED",
          lastRunError: message,
          nextRunAt: computeNextRun(item.nextRunAt, item.interval),
        },
      });

      broadcast({ type: "recurring.failed", recurringTransferId: item.id, error: message });
      results.push({ id: item.id, ok: false, error: message });
    }
  }

  return results;
}
