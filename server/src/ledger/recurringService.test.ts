import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "../db.js";
import { createAccount, postTransaction } from "./ledgerService.js";
import {
  computeNextRun,
  createRecurringTransfer,
  deleteRecurringTransfer,
  runDueRecurringTransfers,
  toggleRecurringTransfer,
} from "./recurringService.js";

beforeEach(async () => {
  await prisma.auditLog.deleteMany();
  await prisma.recurringTransfer.deleteMany();
  await prisma.entry.deleteMany();
  await prisma.transaction.deleteMany();
  await prisma.account.deleteMany();
});

describe("computeNextRun", () => {
  it("advances a monthly interval by calendar month, not a fixed minute count", () => {
    const from = new Date("2026-01-31T12:00:00.000Z");
    const next = computeNextRun(from, "MONTHLY");
    // JS Date rolls Jan 31 + 1 month into early March, which is exactly the
    // kind of calendar-math edge case a fixed 43,200-minute step would get
    // wrong in the other direction (it would land in February).
    expect(next.getUTCMonth()).toBe(2);
  });

  it("advances weekly by seven days", () => {
    const from = new Date("2026-03-01T00:00:00.000Z");
    const next = computeNextRun(from, "WEEKLY");
    expect(next.toISOString()).toBe("2026-03-08T00:00:00.000Z");
  });
});

describe("runDueRecurringTransfers", () => {
  it("executes a due transfer and advances nextRunAt", async () => {
    const equity = await createAccount({ name: "Equity", type: "EQUITY" });
    const checking = await createAccount({ name: "Checking", type: "ASSET" });
    const savings = await createAccount({ name: "Savings", type: "ASSET" });
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = await createRecurringTransfer({
      description: "Weekly savings sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "WEEKLY",
      startAt: due,
    });

    const results = await runDueRecurringTransfers(due);
    expect(results).toEqual([{ id: recurring.id, ok: true }]);

    const updated = await prisma.recurringTransfer.findUniqueOrThrow({ where: { id: recurring.id } });
    expect(updated.lastRunStatus).toBe("SUCCESS");
    expect(updated.nextRunAt.toISOString()).toBe(computeNextRun(due, "WEEKLY").toISOString());

    const savingsBalance = await prisma.entry.findMany({ where: { accountId: savings.id } });
    expect(savingsBalance).toHaveLength(1);
  });

  it("does not double post when the sweep runs twice for the same occurrence", async () => {
    const equity = await createAccount({ name: "Equity", type: "EQUITY" });
    const checking = await createAccount({ name: "Checking", type: "ASSET" });
    const savings = await createAccount({ name: "Savings", type: "ASSET" });
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    await createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "DAILY",
      startAt: due,
    });

    // Simulate two overlapping sweeps both seeing the same due occurrence
    // before nextRunAt has been advanced, by running the sweep body twice
    // against the same due time.
    await runDueRecurringTransfers(due);
    await prisma.recurringTransfer.updateMany({ data: { nextRunAt: due } });
    await runDueRecurringTransfers(due);

    const entries = await prisma.entry.findMany({ where: { accountId: savings.id } });
    expect(entries).toHaveLength(1);
  });

  it("records a failure and still advances nextRunAt instead of retrying forever", async () => {
    const checking = await createAccount({ name: "Checking", type: "ASSET" });
    const savings = await createAccount({ name: "Savings", type: "ASSET" });
    // No opening balance: checking has $0, so the transfer will fail overdraft protection.

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = await createRecurringTransfer({
      description: "Underfunded sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 500n,
      interval: "DAILY",
      startAt: due,
    });

    const results = await runDueRecurringTransfers(due);
    expect(results).toEqual([{ id: recurring.id, ok: false, error: expect.stringContaining("insufficient funds") }]);

    const updated = await prisma.recurringTransfer.findUniqueOrThrow({ where: { id: recurring.id } });
    expect(updated.lastRunStatus).toBe("FAILED");
    expect(updated.nextRunAt.getTime()).toBeGreaterThan(due.getTime());
  });

  it("skips a paused recurring transfer", async () => {
    const equity = await createAccount({ name: "Equity", type: "EQUITY" });
    const checking = await createAccount({ name: "Checking", type: "ASSET" });
    const savings = await createAccount({ name: "Savings", type: "ASSET" });
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = await createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "DAILY",
      startAt: due,
    });
    await toggleRecurringTransfer(recurring.id);

    const results = await runDueRecurringTransfers(due);
    expect(results).toEqual([]);
  });
});

describe("deleteRecurringTransfer", () => {
  it("removes the row so it is never picked up again", async () => {
    const checking = await createAccount({ name: "Checking", type: "ASSET" });
    const savings = await createAccount({ name: "Savings", type: "ASSET" });
    const recurring = await createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 500n,
      interval: "DAILY",
    });

    await deleteRecurringTransfer(recurring.id);

    const found = await prisma.recurringTransfer.findUnique({ where: { id: recurring.id } });
    expect(found).toBeNull();
  });
});
