// Mirrors server/src/ledger/errors.ts. The demo API layer turns one of
// these into the same { error, message } body and HTTP status the Express
// error middleware would have produced, so the dashboard sees identical
// failures either way.

export class DemoLedgerError extends Error {
  code: string;
  httpStatus: number;

  constructor(message: string, code: string, httpStatus: number) {
    super(message);
    this.name = "DemoLedgerError";
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

export function unbalancedTransaction(debits: bigint, credits: bigint): DemoLedgerError {
  return new DemoLedgerError(
    `Transaction does not balance: debits=${debits} credits=${credits}`,
    "UNBALANCED_TRANSACTION",
    422,
  );
}

export function accountNotFound(accountId: string): DemoLedgerError {
  return new DemoLedgerError(`Account ${accountId} not found`, "ACCOUNT_NOT_FOUND", 404);
}

export function insufficientFunds(accountId: string): DemoLedgerError {
  return new DemoLedgerError(`Account ${accountId} has insufficient funds`, "INSUFFICIENT_FUNDS", 409);
}

export function currencyMismatch(): DemoLedgerError {
  return new DemoLedgerError("All entries in a transaction must share one currency", "CURRENCY_MISMATCH", 422);
}

export function idempotencyConflict(key: string): DemoLedgerError {
  return new DemoLedgerError(
    `Idempotency key ${key} was already used with a different request body`,
    "IDEMPOTENCY_CONFLICT",
    409,
  );
}
