import type { Account } from "../api/types";
import { formatMoney } from "../money";
import { useAnimatedCents } from "../useAnimatedCents";

function Money({ valueMinor }: { valueMinor: bigint }) {
  const animated = useAnimatedCents(valueMinor);
  return <span className={animated < 0n ? "is-negative" : undefined}>{formatMoney(animated)}</span>;
}

export function SummaryBand({ accounts, loaded }: { accounts: Account[]; loaded: boolean }) {
  if (!loaded) {
    return (
      <section className="summary" aria-label="Totals" aria-busy="true">
        <div className="summary-lead summary-skeleton">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      </section>
    );
  }

  const sum = (predicate: (a: Account) => boolean) =>
    accounts.filter(predicate).reduce((total, a) => total + BigInt(a.balanceMinor), 0n);

  const assets = sum((a) => a.type === "ASSET");
  const liabilities = sum((a) => a.type === "LIABILITY");

  return (
    <section className="summary" aria-label="Totals">
      <div className="summary-lead">
        <span className="summary-label" title="Total assets minus total liabilities">
          Net position
        </span>
        <span className="summary-value">
          <Money valueMinor={assets - liabilities} />
        </span>
      </div>
      <dl className="summary-list">
        <div>
          <dt>Assets</dt>
          <dd>
            <Money valueMinor={assets} />
          </dd>
        </div>
        <div>
          <dt>Liabilities</dt>
          <dd>
            <Money valueMinor={liabilities} />
          </dd>
        </div>
        <div>
          <dt>Accounts</dt>
          <dd>{accounts.length}</dd>
        </div>
      </dl>
    </section>
  );
}
