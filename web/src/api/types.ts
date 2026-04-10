export type AccountType = "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
export type EntryDirection = "DEBIT" | "CREDIT";

export interface Account {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  createdAt: string;
  // Cent amounts travel as strings end-to-end so a JS number never has to
  // represent them - see formatMoney/parseDollarsToCents.
  balanceMinor: string;
}

export interface Entry {
  id: string;
  transactionId: string;
  accountId: string;
  direction: EntryDirection;
  amountMinor: string;
  createdAt: string;
}

export interface Transaction {
  id: string;
  description: string;
  status: "POSTED" | "VOIDED";
  idempotencyKey: string | null;
  createdAt: string;
  entries: Entry[];
}

export interface PostResult {
  transaction: Transaction;
  replayed: boolean;
  affectedAccountIds: string[];
}

export interface StatementLine {
  entryId: string;
  transactionId: string;
  description: string;
  direction: EntryDirection;
  amountMinor: string;
  runningBalanceMinor: string;
  createdAt: string;
}

export interface ApiError {
  error: string;
  message: string;
}

export type LedgerEvent =
  | { type: "connected" }
  | { type: "transaction.posted"; transaction: Transaction; affectedAccountIds: string[] }
  | { type: "transaction.reversed"; transaction: Transaction; affectedAccountIds: string[] };
