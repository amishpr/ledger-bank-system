import type { Account, PostResult, RecurrenceInterval, RecurringTransfer, SpendingBreakdown, StatementLine } from "./types";

// Everything the dashboard is allowed to ask a backend for. There are two
// implementations: the HTTP client that talks to the Express API, and the
// in-browser demo backend used by the hosted build. No component knows
// which one it has, which is the only reason a static host can run this
// app at all.
export interface LedgerApi {
  listAccounts(): Promise<Account[]>;

  createAccount(input: { name: string; type: Account["type"]; currency?: string }): Promise<Account>;

  getStatement(accountId: string, limit?: number): Promise<StatementLine[]>;

  postTransaction(input: {
    description: string;
    entries: { accountId: string; direction: "DEBIT" | "CREDIT"; amountMinor: string }[];
    idempotencyKey: string;
  }): Promise<PostResult>;

  reverseTransaction(transactionId: string, note?: string): Promise<PostResult>;

  listRecurringTransfers(): Promise<RecurringTransfer[]>;

  createRecurringTransfer(input: {
    description: string;
    fromAccountId: string;
    toAccountId: string;
    amountMinor: string;
    interval: RecurrenceInterval;
  }): Promise<RecurringTransfer>;

  toggleRecurringTransfer(id: string): Promise<RecurringTransfer>;

  deleteRecurringTransfer(id: string): Promise<void>;

  getSpendingBreakdown(): Promise<SpendingBreakdown>;

  // Builds the CSV and hands it to the browser as a download. The HTTP
  // implementation asks the server for it so the export covers an
  // account's whole history rather than the rows the table happens to
  // have loaded.
  exportStatementCsv(accountId: string): Promise<void>;
}
