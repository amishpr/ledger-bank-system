import { beforeEach, describe, expect, it } from "vitest";
import { createAccount, getAccount, postTransaction, reverseTransaction } from "./ledger";
import { db, resetDb } from "./store";

// These mirror server/src/ledger/ledgerService.test.ts case for case. The
// demo backend is a second implementation of the same rules, so it is
// worth pinning it to the same invariants rather than trusting that the
// port stayed faithful.

beforeEach(() => {
  resetDb();
});

describe("postTransaction", () => {
  it("rejects a transaction where debits and credits don't balance", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });

    await expect(
      postTransaction({
        description: "unbalanced",
        entries: [
          { accountId: asset.id, direction: "DEBIT", amountMinor: 1000n },
          { accountId: equity.id, direction: "CREDIT", amountMinor: 900n },
        ],
      }),
    ).rejects.toMatchObject({ code: "UNBALANCED_TRANSACTION" });
  });

  it("refuses to post an entry that would take an asset account negative", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });

    await expect(
      postTransaction({
        description: "overdraft attempt",
        entries: [
          { accountId: equity.id, direction: "DEBIT", amountMinor: 500n },
          { accountId: asset.id, direction: "CREDIT", amountMinor: 500n },
        ],
      }),
    ).rejects.toMatchObject({ code: "INSUFFICIENT_FUNDS" });

    expect(getAccount(asset.id).balanceMinor).toBe(0n);
  });

  it("replays an idempotent request instead of double-posting", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });
    const body = {
      description: "opening balance",
      entries: [
        { accountId: asset.id, direction: "DEBIT" as const, amountMinor: 1000n },
        { accountId: equity.id, direction: "CREDIT" as const, amountMinor: 1000n },
      ],
      idempotencyKey: "seed-key-1",
    };

    const first = await postTransaction(body);
    const second = await postTransaction(body);

    expect(first.replayed).toBe(false);
    expect(second.replayed).toBe(true);
    expect(second.transaction.id).toBe(first.transaction.id);

    expect(getAccount(asset.id).balanceMinor).toBe(1000n); // not 2000n, no double posting
  });

  it("rejects reusing an idempotency key with a different payload", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });

    await postTransaction({
      description: "first",
      entries: [
        { accountId: asset.id, direction: "DEBIT", amountMinor: 1000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 1000n },
      ],
      idempotencyKey: "reused-key",
    });

    await expect(
      postTransaction({
        description: "different payload, same key",
        entries: [
          { accountId: asset.id, direction: "DEBIT", amountMinor: 2000n },
          { accountId: equity.id, direction: "CREDIT", amountMinor: 2000n },
        ],
        idempotencyKey: "reused-key",
      }),
    ).rejects.toMatchObject({ code: "IDEMPOTENCY_CONFLICT" });
  });
});

describe("reverseTransaction", () => {
  it("restores the prior balance and voids the original", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });

    await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: asset.id, direction: "DEBIT", amountMinor: 5000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 5000n },
      ],
    });

    const transfer = await postTransaction({
      description: "spend",
      entries: [
        { accountId: equity.id, direction: "DEBIT", amountMinor: 1200n },
        { accountId: asset.id, direction: "CREDIT", amountMinor: 1200n },
      ],
    });

    expect(getAccount(asset.id).balanceMinor).toBe(3800n);

    const reversal = await reverseTransaction(transfer.transaction.id);
    expect(getAccount(asset.id).balanceMinor).toBe(5000n);

    expect(db.transactions.find((t) => t.id === transfer.transaction.id)!.status).toBe("VOIDED");
    expect(reversal.transaction.status).toBe("POSTED");
  });

  it("refuses to reverse an already-voided transaction", async () => {
    const asset = createAccount({ name: "Checking", type: "ASSET" });
    const equity = createAccount({ name: "Equity", type: "EQUITY" });
    const tx = await postTransaction({
      description: "opening balance",
      entries: [
        { accountId: asset.id, direction: "DEBIT", amountMinor: 1000n },
        { accountId: equity.id, direction: "CREDIT", amountMinor: 1000n },
      ],
    });

    await reverseTransaction(tx.transaction.id);

    await expect(reverseTransaction(tx.transaction.id)).rejects.toMatchObject({ code: "ALREADY_VOIDED" });
  });
});
