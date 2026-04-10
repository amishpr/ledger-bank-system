import type { Account, ApiError, PostResult, StatementLine } from "./types";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:4000";

export class ApiRequestError extends Error {
  code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "ApiRequestError";
    this.code = code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiError | null;
    throw new ApiRequestError(body?.error ?? "UNKNOWN_ERROR", body?.message ?? `Request failed (${res.status})`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  listAccounts: () => request<Account[]>("/accounts"),

  createAccount: (input: { name: string; type: Account["type"]; currency?: string }) =>
    request<Account>("/accounts", { method: "POST", body: JSON.stringify(input) }),

  getStatement: (accountId: string, limit = 25) =>
    request<StatementLine[]>(`/accounts/${accountId}/statement?limit=${limit}`),

  postTransaction: (input: {
    description: string;
    entries: { accountId: string; direction: "DEBIT" | "CREDIT"; amountMinor: string }[];
    idempotencyKey: string;
  }) => request<PostResult>("/transactions", { method: "POST", body: JSON.stringify(input) }),

  reverseTransaction: (transactionId: string, note?: string) =>
    request<PostResult>(`/transactions/${transactionId}/reverse`, { method: "POST", body: JSON.stringify({ note }) }),
};
