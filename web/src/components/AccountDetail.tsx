import { useState } from "react";
import { DownloadSimple } from "@phosphor-icons/react";
import { api, ApiRequestError } from "../api/client";
import type { Account, StatementLine } from "../api/types";
import { TYPE_LABEL } from "../labels";
import { formatMoney } from "../money";
import { useAnimatedCents } from "../useAnimatedCents";
import { BalanceHistoryChart } from "./BalanceHistoryChart";
import { StatementTable } from "./StatementTable";

type Toaster = (message: string, kind?: "success" | "error") => void;

interface DetailProps {
  lines: StatementLine[];
  chartLines: StatementLine[];
  onChanged: () => void;
  onToast: Toaster;
}

// Everything about the selected account in one place: its balance, how
// that balance got there (the chart), and the entries behind it.
export function AccountDetail({ account, loaded, ...rest }: DetailProps & { account: Account | undefined; loaded: boolean }) {
  if (!account) {
    return (
      <section className="panel detail" aria-label="Account detail" aria-busy={!loaded}>
        {loaded ? (
          <div className="empty">
            <strong>No account selected</strong>
            Pick an account to see its balance history and statement.
          </div>
        ) : (
          <div className="summary-skeleton">
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        )}
      </section>
    );
  }

  // Keyed by account so switching accounts starts fresh instead of
  // tweening from the previous account's balance.
  return <SelectedAccount key={account.id} account={account} {...rest} />;
}

function SelectedAccount({ account, lines, chartLines, onChanged, onToast }: DetailProps & { account: Account }) {
  const balance = useAnimatedCents(BigInt(account.balanceMinor));
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleExport() {
    setError(null);
    setExporting(true);
    try {
      await api.exportStatementCsv(account.id);
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Could not export the statement.";
      setError(message);
      onToast(message, "error");
    } finally {
      setExporting(false);
    }
  }

  return (
    <section className="panel detail" aria-labelledby="detail-title">
      <div className="detail-head">
        <div>
          <h2 id="detail-title">{account.name}</h2>
          <p className="detail-type">{TYPE_LABEL[account.type]} account</p>
        </div>
        <div className="detail-balance">
          <span className={`detail-balance-value${balance < 0n ? " is-negative" : ""}`}>{formatMoney(balance)}</span>
          <button
            type="button"
            className="btn"
            disabled={exporting}
            onClick={handleExport}
            title="Download this account's full history as CSV"
          >
            <DownloadSimple size={16} aria-hidden />
            {exporting ? "Exporting…" : "Export CSV"}
          </button>
        </div>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <BalanceHistoryChart account={account} lines={chartLines} />
      <StatementTable account={account} lines={lines} onChanged={onChanged} onToast={onToast} onError={setError} />
    </section>
  );
}
