import type { ReactNode } from "react";
import type { AccountType } from "../api/types";

const PATHS: Record<AccountType, ReactNode> = {
  ASSET: (
    <>
      <rect x="3" y="6" width="18" height="13" rx="2" />
      <path d="M3 10h18" />
      <path d="M7 15h3" />
    </>
  ),
  LIABILITY: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M8 4v16M16 4v16" />
    </>
  ),
  EQUITY: (
    <>
      <path d="M12 3l9 5-9 5-9-5 9-5z" />
      <path d="M3 13l9 5 9-5" />
    </>
  ),
  REVENUE: (
    <>
      <path d="M4 17l6-6 4 4 6-8" />
      <path d="M15 7h5v5" />
    </>
  ),
  EXPENSE: (
    <>
      <path d="M6 3h9l3 3v15H6z" />
      <path d="M9 8h6M9 12h6M9 16h4" />
    </>
  ),
};

export function AccountTypeIcon({ type }: { type: AccountType }) {
  return (
    <svg
      className={`type-icon type-icon-${type.toLowerCase()}`}
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {PATHS[type]}
    </svg>
  );
}
