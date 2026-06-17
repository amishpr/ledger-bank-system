import type { AccountType, EntryDirection } from "../api/types";
import { entryIncreasesBalance } from "../ledgerMath";
import {
  accountNotFound,
  currencyMismatch,
  DemoLedgerError,
  idempotencyConflict,
  insufficientFunds,
  unbalancedTransaction,
} from "./errors";
import {
  db,
  entriesForAccount,
  findAccount,
  nextId,
  type StoredAccount,
  type StoredEntry,
  type StoredTransaction,
} from "./store";

// An in-browser port of server/src/ledger/ledgerService.ts. Every rule the
// server enforces is enforced here, in the same order, so the demo rejects
// exactly what the real API rejects. The differences are storage-level
// only: arrays instead of Prisma, and no $transaction (see applyEntries).

const OVERDRAFT_PROTECTED_TYPES = new Set<AccountType>(["ASSET"]);

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

export type TransactionWithEntries = StoredTransaction & { entries: StoredEntry[] };

export interface PostResult {
  transaction: TransactionWithEntries;
  replayed: boolean;
  affectedAccountIds: string[];
}

export interface AccountBalance extends StoredAccount {
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

export function signedDelta(type: AccountType, direction: EntryDirection, amountMinor: bigint): bigint {
  return entryIncreasesBalance(type, direction) ? amountMinor : -amountMinor;
}

async function hashPayload(description: string, entries: PostEntryInput[]): Promise<string> {
  const normalized = {
    description,
    entries: [...entries]
      .map((e) => ({ accountId: e.accountId, direction: e.direction, amountMinor: e.amountMinor.toString() }))
      .sort((a, b) => (a.accountId + a.direction).localeCompare(b.accountId + b.direction)),
  };
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(normalized)));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function balanceOf(account: StoredAccount): bigint {
  return entriesForAccount(account.id).reduce((sum, e) => sum + signedDelta(account.type, e.direction, e.amountMinor), 0n);
}

function withEntries(transaction: StoredTransaction): TransactionWithEntries {
  return { ...transaction, entries: db.entries.filter((e) => e.transactionId === transaction.id) };
}

export function listAccounts(): AccountBalance[] {
  return db.accounts.map((a) => ({ ...a, balanceMinor: balanceOf(a) }));
}

export function getAccount(accountId: string): AccountBalance {
  const account = findAccount(accountId);
  if (!account) throw accountNotFound(accountId);
  return { ...account, balanceMinor: balanceOf(account) };
}

export function createAccount(input: { name: string; type: AccountType; currency?: string }): StoredAccount {
  const account: StoredAccount = {
    id: nextId("acc"),
    name: input.name,
    type: input.type,
    currency: input.currency ?? "USD",
    createdAt: new Date(),
  };
  db.accounts.push(account);
  db.auditLogs.push({
    id: nextId("log"),
    entityType: "Account",
    entityId: account.id,
    action: "ACCOUNT_CREATED",
    metadata: JSON.stringify({ name: account.name, type: account.type }),
    transactionId: null,
    createdAt: new Date(),
  });
  return account;
}

// The one and only path through which entries are ever written, same as on
// the server. The server wraps the balance check and the insert in a
// Prisma $transaction because two HTTP requests can interleave between
// them. Here there is one thread and no `await` between the check and the
// write, so that section cannot be interleaved at all. The invariant is
// the same; the mechanism that guarantees it is what differs.
async function applyEntries(params: {
  description: string;
  entries: PostEntryInput[];
  idempotencyKey?: string;
  requestHash?: string;
  reversalOfTransactionId?: string;
}): Promise<PostResult> {
  const { description, entries, idempotencyKey, requestHash, reversalOfTransactionId } = params;

  if (entries.length < 2) {
    throw new DemoLedgerError("A transaction needs at least two entries", "TOO_FEW_ENTRIES", 422);
  }
  for (const e of entries) {
    if (e.amountMinor <= 0n) {
      throw new DemoLedgerError("Entry amounts must be positive", "INVALID_AMOUNT", 422);
    }
  }

  const debits = entries.filter((e) => e.direction === "DEBIT").reduce((s, e) => s + e.amountMinor, 0n);
  const credits = entries.filter((e) => e.direction === "CREDIT").reduce((s, e) => s + e.amountMinor, 0n);
  if (debits !== credits) {
    throw unbalancedTransaction(debits, credits);
  }

  if (idempotencyKey) {
    const existing = db.transactions.find((t) => t.idempotencyKey === idempotencyKey);
    if (existing) {
      if (existing.requestHash !== requestHash) {
        throw idempotencyConflict(idempotencyKey);
      }
      return { transaction: withEntries(existing), replayed: true, affectedAccountIds: entries.map((e) => e.accountId) };
    }
  }

  const accountIds = [...new Set(entries.map((e) => e.accountId))];
  const accounts = accountIds.map((id) => {
    const account = findAccount(id);
    if (!account) throw accountNotFound(id);
    return account;
  });
  if (new Set(accounts.map((a) => a.currency)).size > 1) throw currencyMismatch();

  for (const account of accounts) {
    if (!OVERDRAFT_PROTECTED_TYPES.has(account.type)) continue;
    const current = balanceOf(account);
    const delta = entries
      .filter((e) => e.accountId === account.id)
      .reduce((s, e) => s + signedDelta(account.type, e.direction, e.amountMinor), 0n);
    if (current + delta < 0n) throw insufficientFunds(account.id);
  }

  const createdAt = new Date();
  const transaction: StoredTransaction = {
    id: nextId("txn"),
    description,
    status: "POSTED",
    idempotencyKey: idempotencyKey ?? null,
    requestHash: requestHash ?? null,
    createdAt,
  };
  db.transactions.push(transaction);
  for (const entry of entries) {
    db.entries.push({
      id: nextId("ent"),
      transactionId: transaction.id,
      accountId: entry.accountId,
      direction: entry.direction,
      amountMinor: entry.amountMinor,
      createdAt,
    });
  }

  db.auditLogs.push({
    id: nextId("log"),
    entityType: "Transaction",
    entityId: transaction.id,
    action: reversalOfTransactionId ? "TRANSACTION_REVERSED" : "TRANSACTION_POSTED",
    metadata: JSON.stringify({ description, reversalOfTransactionId }),
    transactionId: transaction.id,
    createdAt,
  });

  if (reversalOfTransactionId) {
    const original = db.transactions.find((t) => t.id === reversalOfTransactionId);
    if (original) original.status = "VOIDED";
  }

  return { transaction: withEntries(transaction), replayed: false, affectedAccountIds: accountIds };
}

export async function postTransaction(input: PostTransactionInput): Promise<PostResult> {
  const requestHash = input.idempotencyKey ? await hashPayload(input.description, input.entries) : undefined;
  return applyEntries({
    description: input.description,
    entries: input.entries,
    idempotencyKey: input.idempotencyKey,
    requestHash,
  });
}

export async function reverseTransaction(transactionId: string, note?: string): Promise<PostResult> {
  const original = db.transactions.find((t) => t.id === transactionId);
  if (!original) {
    throw new DemoLedgerError(`Transaction ${transactionId} not found`, "TRANSACTION_NOT_FOUND", 404);
  }
  if (original.status === "VOIDED") {
    throw new DemoLedgerError(`Transaction ${transactionId} is already voided`, "ALREADY_VOIDED", 409);
  }

  const flipped: PostEntryInput[] = db.entries
    .filter((e) => e.transactionId === original.id)
    .map((e) => ({
      accountId: e.accountId,
      direction: e.direction === "DEBIT" ? "CREDIT" : "DEBIT",
      amountMinor: e.amountMinor,
    }));

  return applyEntries({
    description: note ?? `Reversal of ${original.id}: ${original.description}`,
    entries: flipped,
    reversalOfTransactionId: original.id,
  });
}

export function getStatement(accountId: string, limit = 50): StatementLine[] {
  const account = findAccount(accountId);
  if (!account) throw accountNotFound(accountId);

  const ordered = entriesForAccount(accountId).sort(
    (a, b) => a.createdAt.getTime() - b.createdAt.getTime() || a.id.localeCompare(b.id),
  );

  let running = 0n;
  const lines = ordered.map((e) => {
    running += signedDelta(account.type, e.direction, e.amountMinor);
    return {
      entryId: e.id,
      transactionId: e.transactionId,
      description: db.transactions.find((t) => t.id === e.transactionId)!.description,
      direction: e.direction,
      amountMinor: e.amountMinor,
      runningBalanceMinor: running,
      createdAt: e.createdAt,
    };
  });

  return lines.slice(-limit).reverse();
}
