export class LedgerError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly httpStatus: number,
  ) {
    super(message);
    this.name = "LedgerError";
  }
}

export class UnbalancedTransactionError extends LedgerError {
  constructor(debits: bigint, credits: bigint) {
    super(
      `Transaction does not balance: debits=${debits} credits=${credits}`,
      "UNBALANCED_TRANSACTION",
      422,
    );
  }
}

export class AccountNotFoundError extends LedgerError {
  constructor(accountId: string) {
    super(`Account ${accountId} not found`, "ACCOUNT_NOT_FOUND", 404);
  }
}

export class InsufficientFundsError extends LedgerError {
  constructor(accountId: string) {
    super(`Account ${accountId} has insufficient funds`, "INSUFFICIENT_FUNDS", 409);
  }
}

export class CurrencyMismatchError extends LedgerError {
  constructor() {
    super("All entries in a transaction must share one currency", "CURRENCY_MISMATCH", 422);
  }
}

export class IdempotencyConflictError extends LedgerError {
  constructor(key: string) {
    super(
      `Idempotency key ${key} was already used with a different request body`,
      "IDEMPOTENCY_CONFLICT",
      409,
    );
  }
}
