import { db, findAccount } from "./store";

// Mirrors server/src/ledger/insightsService.ts. "Spending" is defined the
// same way the ledger defines an increase to an expense account: a debit
// entry against an EXPENSE-type account. It is derived from the same
// entries the statement is built from, never tracked separately.

export interface SpendingByCategory {
  accountId: string;
  accountName: string;
  totalMinor: bigint;
}

export interface SpendingByMonth {
  month: string;
  totalMinor: bigint;
}

export function getSpendingBreakdown(): { byCategory: SpendingByCategory[]; byMonth: SpendingByMonth[] } {
  const byCategory = new Map<string, SpendingByCategory>();
  const byMonth = new Map<string, bigint>();

  for (const entry of db.entries) {
    if (entry.direction !== "DEBIT") continue;
    const account = findAccount(entry.accountId);
    if (account?.type !== "EXPENSE") continue;

    const category = byCategory.get(entry.accountId) ?? {
      accountId: entry.accountId,
      accountName: account.name,
      totalMinor: 0n,
    };
    category.totalMinor += entry.amountMinor;
    byCategory.set(entry.accountId, category);

    const transaction = db.transactions.find((t) => t.id === entry.transactionId)!;
    const month = transaction.createdAt.toISOString().slice(0, 7);
    byMonth.set(month, (byMonth.get(month) ?? 0n) + entry.amountMinor);
  }

  return {
    byCategory: [...byCategory.values()].sort((a, b) =>
      a.totalMinor < b.totalMinor ? 1 : a.totalMinor > b.totalMinor ? -1 : 0,
    ),
    byMonth: [...byMonth.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([month, totalMinor]) => ({ month, totalMinor })),
  };
}
