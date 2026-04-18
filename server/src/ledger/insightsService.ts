import { prisma } from "../db.js";

export interface SpendingByCategory {
  accountId: string;
  accountName: string;
  totalMinor: bigint;
}

export interface SpendingByMonth {
  month: string; // "YYYY-MM"
  totalMinor: bigint;
}

// "Spending" is defined the same way the ledger itself defines an increase
// to an expense account: a debit entry against an EXPENSE-type account.
// This is a read-only report built from the same entries that already
// exist, not a separate figure that could drift from the ledger's own
// history.
export async function getSpendingBreakdown(): Promise<{ byCategory: SpendingByCategory[]; byMonth: SpendingByMonth[] }> {
  const entries = await prisma.entry.findMany({
    where: { direction: "DEBIT", account: { type: "EXPENSE" } },
    include: { account: true, transaction: true },
  });

  const byCategory = new Map<string, SpendingByCategory>();
  const byMonth = new Map<string, bigint>();

  for (const entry of entries) {
    const category = byCategory.get(entry.accountId) ?? {
      accountId: entry.accountId,
      accountName: entry.account.name,
      totalMinor: 0n,
    };
    category.totalMinor += entry.amountMinor;
    byCategory.set(entry.accountId, category);

    const month = entry.transaction.createdAt.toISOString().slice(0, 7);
    byMonth.set(month, (byMonth.get(month) ?? 0n) + entry.amountMinor);
  }

  return {
    byCategory: [...byCategory.values()].sort((a, b) => (a.totalMinor < b.totalMinor ? 1 : a.totalMinor > b.totalMinor ? -1 : 0)),
    byMonth: [...byMonth.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, totalMinor]) => ({ month, totalMinor })),
  };
}
