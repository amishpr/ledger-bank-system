import type { AccountType, EntryDirection, RecurrenceInterval } from "../api/types";

// The in-browser stand-in for the Postgres/SQLite tables in
// server/prisma/schema.prisma. Same five tables, same columns, same types:
// cent amounts are BigInt and timestamps are Date, exactly as Prisma hands
// them to the server's ledger code. Everything is converted to the wire
// format (strings) at the API boundary, not here.

export interface StoredAccount {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  createdAt: Date;
}

export interface StoredTransaction {
  id: string;
  description: string;
  status: "POSTED" | "VOIDED";
  idempotencyKey: string | null;
  requestHash: string | null;
  createdAt: Date;
}

export interface StoredEntry {
  id: string;
  transactionId: string;
  accountId: string;
  direction: EntryDirection;
  amountMinor: bigint;
  createdAt: Date;
}

export interface StoredRecurringTransfer {
  id: string;
  description: string;
  fromAccountId: string;
  toAccountId: string;
  amountMinor: bigint;
  interval: RecurrenceInterval;
  active: boolean;
  nextRunAt: Date;
  lastRunAt: Date | null;
  lastRunStatus: "SUCCESS" | "FAILED" | null;
  lastRunError: string | null;
  createdAt: Date;
}

export interface StoredAuditLog {
  id: string;
  entityType: string;
  entityId: string;
  action: string;
  metadata: string;
  transactionId: string | null;
  createdAt: Date;
}

export const db = {
  accounts: [] as StoredAccount[],
  transactions: [] as StoredTransaction[],
  entries: [] as StoredEntry[],
  recurringTransfers: [] as StoredRecurringTransfer[],
  auditLogs: [] as StoredAuditLog[],
};

// Prisma's `cuid()` is replaced by a zero-padded counter so ids sort in
// insertion order. The statement query orders by (createdAt, id), and the
// seed backdates rows after inserting them, so several rows can share a
// timestamp; a sortable id is what keeps that tiebreak stable.
let idCounter = 0;

export function nextId(prefix: string): string {
  idCounter += 1;
  return `${prefix}_${idCounter.toString().padStart(6, "0")}`;
}

export function resetDb(): void {
  db.accounts.length = 0;
  db.transactions.length = 0;
  db.entries.length = 0;
  db.recurringTransfers.length = 0;
  db.auditLogs.length = 0;
  idCounter = 0;
}

export function findAccount(accountId: string): StoredAccount | undefined {
  return db.accounts.find((a) => a.id === accountId);
}

export function entriesForAccount(accountId: string): StoredEntry[] {
  return db.entries.filter((e) => e.accountId === accountId);
}
