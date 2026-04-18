import type { Account } from "../api/types";
import { formatMoney } from "../money";
import { useAnimatedCents } from "../useAnimatedCents";
import { AccountTypeIcon } from "./AccountTypeIcon";

const TYPE_LABEL: Record<Account["type"], string> = {
  ASSET: "Asset",
  LIABILITY: "Liability",
  EQUITY: "Equity",
  REVENUE: "Revenue",
  EXPENSE: "Expense",
};

function AccountCard({
  account,
  selected,
  pulse,
  onSelect,
}: {
  account: Account;
  selected: boolean;
  pulse: "up" | "down" | undefined;
  onSelect: () => void;
}) {
  const animatedBalance = useAnimatedCents(BigInt(account.balanceMinor));
  const negative = animatedBalance < 0n;
  const pulseClass = pulse ? ` pulse-${pulse}` : "";

  return (
    <button className={`account-card${selected ? " selected" : ""}${pulseClass}`} onClick={onSelect}>
      <div className="account-card-top">
        <span className="account-name">
          <AccountTypeIcon type={account.type} />
          {account.name}
        </span>
        <span className={`account-type account-type-${account.type.toLowerCase()}`}>{TYPE_LABEL[account.type]}</span>
      </div>
      <div className={`account-balance${negative ? " negative" : ""}`}>{formatMoney(animatedBalance)}</div>
    </button>
  );
}

export function AccountsPanel({
  accounts,
  selectedAccountId,
  pulsingAccounts,
  onSelect,
}: {
  accounts: Account[];
  selectedAccountId: string | null;
  pulsingAccounts: Map<string, "up" | "down">;
  onSelect: (id: string) => void;
}) {
  return (
    <div className="panel">
      <h2>Accounts</h2>
      <div className="account-list">
        {accounts.map((account) => (
          <AccountCard
            key={account.id}
            account={account}
            selected={account.id === selectedAccountId}
            pulse={pulsingAccounts.get(account.id)}
            onSelect={() => onSelect(account.id)}
          />
        ))}
      </div>
    </div>
  );
}
