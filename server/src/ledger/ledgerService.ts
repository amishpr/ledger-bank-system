import { createHash } from "node:crypto";
import type { Account, EntryDirection, Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import {
  AccountNotFoundError,
  CurrencyMismatchError,
  IdempotencyConflictError,
  InsufficientFundsError,
  LedgerError,
  UnbalancedTransactionError,
} from "./errors.js";
import { signedDelta, type AccountBalance, type PostEntryInput, type PostTransactionInput, type StatementLine } from "./types.js";

type PrismaTx = Prisma.TransactionClient;

// Accounts we treat as spendable balances (checking-account-like): the
// ledger will not post an entry that would push one of these negative.
// Everything else (LIABILITY/EQUITY/REVENUE/EXPENSE) is allowed to go
// negative, the same way "expenses so far" or "revenue so far" naturally can.
const OVERDRAFT_PROTECTED_TYPES = new Set(["ASSET"]);

function hashPayload(description: string, entries: PostEntryInput[]): string {
  const normalized = {
    description,
    entries: [...entries]
      .map((e) => ({ accountId: e.accountId, direction: e.direction, amountMinor: e.amountMinor.toString() }))
      .sort((a, b) => (a.accountId + a.direction).localeCompare(b.accountId + b.direction)),
  };
  return createHash("sha256").update(JSON.stringify(normalized)).digest("hex");
}

async function balanceOf(tx: PrismaTx, account: Account): Promise<bigint> {
  const entries = await tx.entry.findMany({
    where: { accountId: account.id },
    select: { direction: true, amountMinor: true },
  });
  return entries.reduce((sum, e) => sum + signedDelta(account.type, e.direction, e.amountMinor), 0n);
}

export async function computeBalance(accountId: string): Promise<bigint> {
  const account = await prisma.account.findUnique({ where: { id: accountId } });
  if (!account) throw new AccountNotFoundError(accountId);
  return balanceOf(prisma as unknown as PrismaTx, account);
}

export async function listAccounts(): Promise<AccountBalance[]> {
  const accounts = await prisma.account.findMany({ orderBy: { createdAt: "asc" } });
  const balances = await Promise.all(accounts.map((a) => balanceOf(prisma as unknown as PrismaTx, a)));
  return accounts.map((a, i) => ({ ...a, balanceMinor: balances[i]! }));
}

export async function getAccount(accountId: string): Promise<AccountBalance> {
  const account = await prisma.account.findUnique({ where: { id: accountId } });
  if (!account) throw new AccountNotFoundError(accountId);
  const balanceMinor = await balanceOf(prisma as unknown as PrismaTx, account);
  return { ...account, balanceMinor };
}

export async function createAccount(input: { name: string; type: Account["type"]; currency?: string }) {
  const account = await prisma.account.create({
    data: { name: input.name, type: input.type, currency: input.currency ?? "USD" },
  });
  await prisma.auditLog.create({
    data: {
      entityType: "Account",
      entityId: account.id,
      action: "ACCOUNT_CREATED",
      metadata: JSON.stringify({ name: account.name, type: account.type }),
    },
  });
  return account;
}

interface PostResult {
  transaction: Awaited<ReturnType<typeof prisma.transaction.findUniqueOrThrow>> & {
    entries: Awaited<ReturnType<typeof prisma.entry.findMany>>;
  };
  replayed: boolean;
  affectedAccountIds: string[];
}

// The one and only path through which entries are ever written. Both a
// normal transfer and a reversal funnel through here, so the balance
// invariant and the insufficient-funds check only need to live in one place.
async function applyEntries(params: {
  description: string;
  entries: PostEntryInput[];
  idempotencyKey?: string;
  requestHash?: string;
  reversalOfTransactionId?: string;
}): Promise<PostResult> {
  const { description, entries, idempotencyKey, requestHash, reversalOfTransactionId } = params;

  if (entries.length < 2) {
    throw new LedgerError("A transaction needs at least two entries", "TOO_FEW_ENTRIES", 422);
  }
  for (const e of entries) {
    if (e.amountMinor <= 0n) {
      throw new LedgerError("Entry amounts must be positive", "INVALID_AMOUNT", 422);
    }
  }

  const debits = entries.filter((e) => e.direction === "DEBIT").reduce((s, e) => s + e.amountMinor, 0n);
  const credits = entries.filter((e) => e.direction === "CREDIT").reduce((s, e) => s + e.amountMinor, 0n);
  if (debits !== credits) {
    throw new UnbalancedTransactionError(debits, credits);
  }

  if (idempotencyKey) {
    const existing = await prisma.transaction.findUnique({
      where: { idempotencyKey },
      include: { entries: true },
    });
    if (existing) {
      if (existing.requestHash !== requestHash) {
        throw new IdempotencyConflictError(idempotencyKey);
      }
      return { transaction: existing, replayed: true, affectedAccountIds: entries.map((e) => e.accountId) };
    }
  }

  // SQLite (dev) serializes all writers behind a single lock, so this
  // transaction is race-free as-is. Against Postgres in production, add
  // `SELECT ... FOR UPDATE` on the affected accounts (ordered by id, to
  // avoid deadlocks) right before recomputing balances below.
  const result = await prisma.$transaction(async (tx) => {
    const accountIds = [...new Set(entries.map((e) => e.accountId))];
    const accounts = await tx.account.findMany({ where: { id: { in: accountIds } } });
    const byId = new Map(accounts.map((a) => [a.id, a]));

    for (const accountId of accountIds) {
      if (!byId.has(accountId)) throw new AccountNotFoundError(accountId);
    }
    const currencies = new Set(accounts.map((a) => a.currency));
    if (currencies.size > 1) throw new CurrencyMismatchError();

    for (const accountId of accountIds) {
      const account = byId.get(accountId)!;
      if (!OVERDRAFT_PROTECTED_TYPES.has(account.type)) continue;
      const current = await balanceOf(tx, account);
      const delta = entries
        .filter((e) => e.accountId === accountId)
        .reduce((s, e) => s + signedDelta(account.type, e.direction, e.amountMinor), 0n);
      if (current + delta < 0n) throw new InsufficientFundsError(accountId);
    }

    const transaction = await tx.transaction.create({
      data: {
        description,
        idempotencyKey,
        requestHash,
        entries: {
          create: entries.map((e) => ({
            accountId: e.accountId,
            direction: e.direction as EntryDirection,
            amountMinor: e.amountMinor,
          })),
        },
      },
      include: { entries: true },
    });

    await tx.auditLog.create({
      data: {
        entityType: "Transaction",
        entityId: transaction.id,
        action: reversalOfTransactionId ? "TRANSACTION_REVERSED" : "TRANSACTION_POSTED",
        transactionId: transaction.id,
        metadata: JSON.stringify({ description, reversalOfTransactionId }),
      },
    });

    if (reversalOfTransactionId) {
      await tx.transaction.update({
        where: { id: reversalOfTransactionId },
        data: { status: "VOIDED" },
      });
    }

    return transaction;
  });

  return { transaction: result, replayed: false, affectedAccountIds: [...new Set(entries.map((e) => e.accountId))] };
}

export async function postTransaction(input: PostTransactionInput): Promise<PostResult> {
  const requestHash = input.idempotencyKey ? hashPayload(input.description, input.entries) : undefined;
  return applyEntries({
    description: input.description,
    entries: input.entries,
    idempotencyKey: input.idempotencyKey,
    requestHash,
  });
}

export async function reverseTransaction(transactionId: string, note?: string): Promise<PostResult> {
  const original = await prisma.transaction.findUnique({ where: { id: transactionId }, include: { entries: true } });
  if (!original) throw new LedgerError(`Transaction ${transactionId} not found`, "TRANSACTION_NOT_FOUND", 404);
  if (original.status === "VOIDED") {
    throw new LedgerError(`Transaction ${transactionId} is already voided`, "ALREADY_VOIDED", 409);
  }

  const flipped: PostEntryInput[] = original.entries.map((e) => ({
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

export async function getStatement(accountId: string, limit = 50): Promise<StatementLine[]> {
  const account = await prisma.account.findUnique({ where: { id: accountId } });
  if (!account) throw new AccountNotFoundError(accountId);

  // Demo-scale approach: replay every entry in order to derive running
  // balances. A production system would keep a materialized running-balance
  // column (or use a window function) instead of recomputing this per request.
  const allEntries = await prisma.entry.findMany({
    where: { accountId },
    include: { transaction: true },
    orderBy: [{ createdAt: "asc" }, { id: "asc" }],
  });

  let running = 0n;
  const lines: StatementLine[] = allEntries.map((e) => {
    running += signedDelta(account.type, e.direction, e.amountMinor);
    return {
      entryId: e.id,
      transactionId: e.transactionId,
      description: e.transaction.description,
      direction: e.direction,
      amountMinor: e.amountMinor,
      runningBalanceMinor: running,
      createdAt: e.createdAt,
    };
  });

  return lines.slice(-limit).reverse();
}
