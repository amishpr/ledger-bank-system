import type { Account } from "../api/types";
import { ACCOUNT_TYPES, GROUP_LABEL, shortAccountName } from "../labels";
import { formatMoney } from "../money";
import { useAnimatedCents } from "../useAnimatedCents";
import { NewAccountForm } from "./NewAccountForm";

type Pulse = "up" | "down";
type Toaster = (message: string, kind?: "success" | "error") => void;

const SKELETON_ROWS = 8;

function AccountRow({
  account,
  selected,
  pulse,
  onSelect,
}: {
  account: Account;
  selected: boolean;
  pulse: Pulse | undefined;
  onSelect: () => void;
}) {
  const animatedBalance = useAnimatedCents(BigInt(account.balanceMinor));
  const pulseClass = pulse ? ` pulse-${pulse}` : "";

  return (
    <button
      type="button"
      className={`account-row${pulseClass}`}
      aria-current={selected ? "true" : undefined}
      onClick={onSelect}
      title={account.name}
    >
      <span className="account-row-name">{shortAccountName(account.name, account.type)}</span>
      <span className={`account-row-balance${animatedBalance < 0n ? " is-negative" : ""}`}>
        {formatMoney(animatedBalance)}
      </span>
    </button>
  );
}

// Grouped by account type, in the order a balance sheet and income
// statement list them. The group heading says the type, so the rows
// don't have to.
export function AccountsPanel({
  accounts,
  loaded,
  selectedAccountId,
  pulsingAccounts,
  onSelect,
  onCreated,
  onToast,
}: {
  accounts: Account[];
  loaded: boolean;
  selectedAccountId: string | null;
  pulsingAccounts: Map<string, Pulse>;
  onSelect: (id: string) => void;
  onCreated: () => void;
  onToast: Toaster;
}) {
  const groups = ACCOUNT_TYPES.map((type) => ({ type, accounts: accounts.filter((a) => a.type === type) })).filter(
    (group) => group.accounts.length > 0,
  );

  return (
    <section className="panel accounts" aria-labelledby="accounts-title">
      <div className="panel-head">
        <h2 id="accounts-title">Accounts</h2>
      </div>

      <div className="account-groups" aria-busy={!loaded}>
        {!loaded &&
          Array.from({ length: SKELETON_ROWS }, (_, i) => (
            <div className="account-skeleton" key={i}>
              <span className="skeleton" />
              <span className="skeleton" />
            </div>
          ))}

        {loaded && groups.length === 0 && (
          <div className="empty">
            <strong>No accounts yet</strong>
            Add one below to start posting transactions.
          </div>
        )}

        {groups.map((group) => {
          const total = group.accounts.reduce((sum, a) => sum + BigInt(a.balanceMinor), 0n);
          return (
            <div className="account-group" key={group.type}>
              <div className="account-group-head">
                <h3>{GROUP_LABEL[group.type]}</h3>
                <span className="account-group-total num">{formatMoney(total)}</span>
              </div>
              <ul className="account-list">
                {group.accounts.map((account) => (
                  <li key={account.id}>
                    <AccountRow
                      account={account}
                      selected={account.id === selectedAccountId}
                      pulse={pulsingAccounts.get(account.id)}
                      onSelect={() => onSelect(account.id)}
                    />
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </div>

      <NewAccountForm onCreated={onCreated} onToast={onToast} />
    </section>
  );
}
