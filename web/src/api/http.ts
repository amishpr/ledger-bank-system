import type { LedgerApi } from "./contract";
import { saveBlob } from "./download";
import { ApiRequestError } from "./errors";
import type { Account, ApiError, PostResult, RecurringTransfer, SpendingBreakdown, StatementLine } from "./types";

// The API gateway, which routes each path to the service that owns it.
const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:4000/api/v1";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) {
    // Errors are RFC 9457 Problem Details with a stable `code`. The older
    // `error` and `message` fields are still read in case an older API
    // answers.
    const body = (await res.json().catch(() => null)) as ApiError | null;
    throw new ApiRequestError(
      body?.code ?? body?.error ?? "UNKNOWN_ERROR",
      body?.detail ?? body?.message ?? `Request failed (${res.status})`,
    );
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

export const httpApi: LedgerApi = {
  listAccounts: () => request<Account[]>("/accounts"),

  createAccount: (input) => request<Account>("/accounts", { method: "POST", body: JSON.stringify(input) }),

  getStatement: (accountId, limit = 25) =>
    request<StatementLine[]>(`/accounts/${accountId}/statement?limit=${limit}`),

  postTransaction: ({ idempotencyKey, ...body }) =>
    request<PostResult>("/transactions", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    }),

  reverseTransaction: (transactionId, note) =>
    request<PostResult>(`/transactions/${transactionId}/reverse`, { method: "POST", body: JSON.stringify({ note }) }),

  listRecurringTransfers: () => request<RecurringTransfer[]>("/recurring-transfers"),

  createRecurringTransfer: (input) =>
    request<RecurringTransfer>("/recurring-transfers", { method: "POST", body: JSON.stringify(input) }),

  toggleRecurringTransfer: (id) =>
    request<RecurringTransfer>(`/recurring-transfers/${id}/toggle-active`, { method: "POST" }),

  deleteRecurringTransfer: (id) => request<void>(`/recurring-transfers/${id}`, { method: "DELETE" }),

  getSpendingBreakdown: () => request<SpendingBreakdown>("/insights/spending"),

  async exportStatementCsv(accountId) {
    const res = await fetch(`${API_URL}/accounts/${accountId}/statement/export`);
    if (!res.ok) {
      throw new ApiRequestError("EXPORT_FAILED", `Could not export the statement (${res.status})`);
    }
    // The server names the file in Content-Disposition, so the download
    // keeps whatever filename the API decided on.
    const filename = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "")?.[1];
    saveBlob(filename ?? "statement.csv", await res.blob());
  },
};
