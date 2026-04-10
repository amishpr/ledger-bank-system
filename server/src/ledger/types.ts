import type { AccountType, EntryDirection } from "@prisma/client";

export interface PostEntryInput {
  accountId: string;
  direction: EntryDirection;
  amountMinor: bigint;
}

export interface PostTransactionInput {
  description: string;
  entries: PostEntryInput[];
  idempotencyKey?: string;
}

export interface AccountBalance {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  createdAt: Date;
  balanceMinor: bigint;
}

export interface StatementLine {
  entryId: string;
  transactionId: string;
  description: string;
  direction: EntryDirection;
  amountMinor: bigint;
  runningBalanceMinor: bigint;
  createdAt: Date;
}

// Debits increase ASSET/EXPENSE accounts and decrease LIABILITY/EQUITY/REVENUE
// accounts; credits do the opposite. This is textbook double-entry
// accounting and is what makes "balance" well-defined per account type.
const DEBIT_NORMAL_TYPES = new Set<AccountType>(["ASSET", "EXPENSE"]);

export function signedDelta(type: AccountType, direction: EntryDirection, amountMinor: bigint): bigint {
  const isDebitNormal = DEBIT_NORMAL_TYPES.has(type);
  const isDebit = direction === "DEBIT";
  const increases = isDebit === isDebitNormal;
  return increases ? amountMinor : -amountMinor;
}
