import { beforeEach, describe, expect, it } from "vitest";
import { createAccount, postTransaction } from "./ledger";
import {
  computeNextRun,
  createRecurringTransfer,
  deleteRecurringTransfer,
  runDueRecurringTransfers,
  toggleRecurringTransfer,
} from "./recurring";
import { db, entriesForAccount, resetDb } from "./store";

// Mirrors server/src/ledger/recurringService.test.ts. The sweep takes the
// current time as an argument here for the same reason it does on the
// server: a background job that reads the clock itself cannot be tested
// without waiting on a real timer.

beforeEach(() => {
  resetDb();
});

function fundedAccounts() {
  const equity = createAccount({ name: "Equity", type: "EQUITY" });
  const checking = createAccount({ name: "Checking", type: "ASSET" });
  const savings = createAccount({ name: "Savings", type: "ASSET" });
  return { equity, checking, savings };
}

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
    const { equity, checking, savings } = fundedAccounts();
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = createRecurringTransfer({
      description: "Weekly savings sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "WEEKLY",
      startAt: due,
    });

    const results = await runDueRecurringTransfers(due);
    expect(results).toEqual([{ id: recurring.id, ok: true }]);

    const updated = db.recurringTransfers.find((r) => r.id === recurring.id)!;
    expect(updated.lastRunStatus).toBe("SUCCESS");
    expect(updated.nextRunAt.toISOString()).toBe(computeNextRun(due, "WEEKLY").toISOString());
    expect(entriesForAccount(savings.id)).toHaveLength(1);
  });

  it("does not double post when the sweep runs twice for the same occurrence", async () => {
    const { equity, checking, savings } = fundedAccounts();
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "DAILY",
      startAt: due,
    });

    // Simulate two overlapping sweeps both seeing the same due occurrence
    // before nextRunAt has been advanced, by rewinding nextRunAt and
    // sweeping the same due time again.
    await runDueRecurringTransfers(due);
    db.recurringTransfers.find((r) => r.id === recurring.id)!.nextRunAt = due;
    await runDueRecurringTransfers(due);

    expect(entriesForAccount(savings.id)).toHaveLength(1);
  });

  it("records a failure and still advances nextRunAt instead of retrying forever", async () => {
    const checking = createAccount({ name: "Checking", type: "ASSET" });
    const savings = createAccount({ name: "Savings", type: "ASSET" });
    // No opening balance: checking has $0, so the transfer fails overdraft protection.

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = createRecurringTransfer({
      description: "Underfunded sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 500n,
      interval: "DAILY",
      startAt: due,
    });

    const results = await runDueRecurringTransfers(due);
    expect(results).toEqual([{ id: recurring.id, ok: false, error: expect.stringContaining("insufficient funds") }]);

    const updated = db.recurringTransfers.find((r) => r.id === recurring.id)!;
    expect(updated.lastRunStatus).toBe("FAILED");
    expect(updated.nextRunAt.getTime()).toBeGreaterThan(due.getTime());
  });

  it("skips a paused recurring transfer", async () => {
    const { equity, checking, savings } = fundedAccounts();
    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: checking.id, direction: "DEBIT", amountMinor: 10_000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 10_000n },
      ],
    });

    const due = new Date("2026-01-01T00:00:00.000Z");
    const recurring = createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 1_000n,
      interval: "DAILY",
      startAt: due,
    });
    toggleRecurringTransfer(recurring.id);

    expect(await runDueRecurringTransfers(due)).toEqual([]);
  });
});

describe("deleteRecurringTransfer", () => {
  it("removes the row so it is never picked up again", () => {
    const checking = createAccount({ name: "Checking", type: "ASSET" });
    const savings = createAccount({ name: "Savings", type: "ASSET" });
    const recurring = createRecurringTransfer({
      description: "Sweep",
      fromAccountId: checking.id,
      toAccountId: savings.id,
      amountMinor: 500n,
      interval: "DAILY",
    });

    deleteRecurringTransfer(recurring.id);

    expect(db.recurringTransfers.find((r) => r.id === recurring.id)).toBeUndefined();
  });
});
