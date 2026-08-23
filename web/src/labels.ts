import type { AccountType, RecurrenceInterval } from "./api/types";

export const ACCOUNT_TYPES: AccountType[] = ["ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"];

export const TYPE_LABEL: Record<AccountType, string> = {
  ASSET: "Asset",
  LIABILITY: "Liability",
  EQUITY: "Equity",
  REVENUE: "Revenue",
  EXPENSE: "Expense",
};

export const GROUP_LABEL: Record<AccountType, string> = {
  ASSET: "Assets",
  LIABILITY: "Liabilities",
  EQUITY: "Equity",
  REVENUE: "Revenue",
  EXPENSE: "Expenses",
};

export const INTERVAL_LABEL: Record<RecurrenceInterval, string> = {
  EVERY_MINUTE: "Every minute",
  DAILY: "Daily",
  WEEKLY: "Weekly",
  MONTHLY: "Monthly",
};

// Seeded account names carry their type as a prefix ("Expenses - Rent").
// Under a heading that already says Expenses the prefix is just noise, so
// it is dropped for display. Only an exact "<type> - " lead is removed,
// and the stored name never changes.
export function shortAccountName(name: string, type: AccountType): string {
  for (const prefix of [GROUP_LABEL[type], TYPE_LABEL[type]]) {
    const lead = `${prefix} - `;
    if (name.startsWith(lead) && name.length > lead.length) return name.slice(lead.length);
  }
  return name;
}
