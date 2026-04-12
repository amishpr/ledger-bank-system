import type { Account } from "../api/types";
import { formatMoney } from "../money";
import { useAnimatedCents } from "../useAnimatedCents";

function StatTile({ label, valueMinor, tone }: { label: string; valueMinor: bigint; tone?: "negative" }) {
  const animated = useAnimatedCents(valueMinor);
  const negative = animated < 0n;
  return (
    <div className="stat-tile">
      <span className="stat-label">{label}</span>
      <span className={`stat-value${negative || tone === "negative" ? " negative" : ""}`}>{formatMoney(animated)}</span>
    </div>
  );
}

export function StatsBar({ accounts }: { accounts: Account[] }) {
  const sum = (predicate: (a: Account) => boolean) =>
    accounts.filter(predicate).reduce((total, a) => total + BigInt(a.balanceMinor), 0n);

  const assets = sum((a) => a.type === "ASSET");
  const liabilities = sum((a) => a.type === "LIABILITY");
  const netPosition = assets - liabilities;

  return (
    <div className="stats-bar">
      <StatTile label="Total assets" valueMinor={assets} />
      <StatTile label="Total liabilities" valueMinor={liabilities} />
      <StatTile label="Net position" valueMinor={netPosition} />
      <div className="stat-tile">
        <span className="stat-label">Accounts</span>
        <span className="stat-value">{accounts.length}</span>
      </div>
    </div>
  );
}
