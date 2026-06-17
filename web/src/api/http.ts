import type { LedgerApi } from "./contract";
import { saveBlob } from "./download";
import { ApiRequestError } from "./errors";
import type { Account, ApiError, PostResult, RecurringTransfer, SpendingBreakdown, StatementLine } from "./types";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:4000";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiError | null;
    throw new ApiRequestError(body?.error ?? "UNKNOWN_ERROR", body?.message ?? `Request failed (${res.status})`);
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

  postTransaction: (input) => request<PostResult>("/transactions", { method: "POST", body: JSON.stringify(input) }),

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
