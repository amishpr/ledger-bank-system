import type { Account } from "../api/types";
import { formatMoney } from "../money";

const TYPE_LABEL: Record<Account["type"], string> = {
  ASSET: "Asset",
  LIABILITY: "Liability",
  EQUITY: "Equity",
  REVENUE: "Revenue",
  EXPENSE: "Expense",
};

export function AccountsPanel({
  accounts,
  selectedAccountId,
  onSelect,
}: {
  accounts: Account[];
  selectedAccountId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <div className="panel">
      <h2>Accounts</h2>
      <div className="account-list">
        {accounts.map((account) => {
          const negative = account.balanceMinor.startsWith("-");
          return (
            <button
              key={account.id}
              className={`account-card${account.id === selectedAccountId ? " selected" : ""}`}
              onClick={() => onSelect(account.id)}
            >
              <div className="account-card-top">
                <span className="account-name">{account.name}</span>
                <span className={`account-type account-type-${account.type.toLowerCase()}`}>
                  {TYPE_LABEL[account.type]}
                </span>
              </div>
              <div className={`account-balance${negative ? " negative" : ""}`}>{formatMoney(account.balanceMinor)}</div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
