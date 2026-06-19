import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account, StatementLine } from "../api/types";
import { isLargeAmount } from "../flags";
import { entryIncreasesBalance } from "../ledgerMath";
import { formatMoney } from "../money";

export function StatementPanel({
  account,
  lines,
  onChanged,
  onToast,
}: {
  account: Account | undefined;
  lines: StatementLine[];
  onChanged: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
  const [reversingId, setReversingId] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleExport(accountId: string) {
    setError(null);
    setExporting(true);
    try {
      await api.exportStatementCsv(accountId);
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Could not export the statement.";
      setError(message);
      onToast(message, "error");
    } finally {
      setExporting(false);
    }
  }

  async function handleReverse(transactionId: string, description: string) {
    setError(null);
    setReversingId(transactionId);
    try {
      await api.reverseTransaction(transactionId, "Reversed from dashboard");
      onChanged();
      onToast(`Reversed "${description}"`);
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Reverse failed.";
      setError(message);
      onToast(message, "error");
    } finally {
      setReversingId(null);
    }
  }

  if (!account) {
    return (
      <div className="panel statement-panel">
        <h2>Statement</h2>
        <p className="hint">Select an account to see its activity.</p>
      </div>
    );
  }

  return (
    <div className="panel statement-panel">
      <div className="statement-header">
        <h2>Statement — {account.name}</h2>
        <button
          className="export-link"
          disabled={exporting}
          onClick={() => handleExport(account.id)}
          title="Download this account's full history as CSV"
        >
          {exporting ? "Exporting…" : "Export CSV"}
        </button>
      </div>
      {error && <div className="form-error">{error}</div>}
      {/* The row is too dense to reflow below roughly 630px, so it scrolls
          inside this container instead of widening the whole page. A
          scrollable region has to be focusable to be reachable by keyboard. */}
      <div className="table-scroll" tabIndex={0} role="region" aria-label={`Statement for ${account.name}`}>
        <table className="statement-table">
          <thead>
            <tr>
              <th>When</th>
              <th>Description</th>
              <th>Direction</th>
              <th className="num">Amount</th>
              <th className="num">Balance</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.length === 0 && (
              <tr>
                <td colSpan={6} className="hint">
                  No activity yet.
                </td>
              </tr>
            )}
            {lines.map((line) => {
              const increase = entryIncreasesBalance(account.type, line.direction);
              return (
                <tr key={line.entryId}>
                  <td className="muted">{new Date(line.createdAt).toLocaleString()}</td>
                  <td>
                    {line.description}
                    {isLargeAmount(line.amountMinor) && (
                      <span className="flag-badge" title="Above the demo large-transaction threshold of $1,000">
                        Large
                      </span>
                    )}
                  </td>
                  <td>
                    <span className={`direction-badge ${increase ? "increase" : "decrease"}`}>{line.direction}</span>
                  </td>
                  <td className={`num ${increase ? "positive" : "negative"}`}>
                    {increase ? "+" : "-"}
                    {formatMoney(line.amountMinor)}
                  </td>
                  <td className="num">{formatMoney(line.runningBalanceMinor)}</td>
                  <td>
                    <button
                      className="link-button"
                      disabled={reversingId === line.transactionId}
                      onClick={() => handleReverse(line.transactionId, line.description)}
                      title="Post an offsetting reversal transaction"
                    >
                      {reversingId === line.transactionId ? "…" : "Reverse"}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
