import { useState } from "react";
import { ArrowUUpLeft } from "@phosphor-icons/react";
import { api, ApiRequestError } from "../api/client";
import type { Account, StatementLine } from "../api/types";
import { isLargeAmount } from "../flags";
import { formatShortDateTime } from "../formatDate";
import { entryIncreasesBalance } from "../ledgerMath";
import { formatMoney } from "../money";

// A classic ledger layout: each entry sits in its Debit or Credit column.
// The amount is colored and signed by what it did to this account's
// balance, not by the debit/credit word, because which side counts as
// "up" depends on the account type. The sign means color is never the
// only signal.
export function StatementTable({
  account,
  lines,
  onChanged,
  onToast,
  onError,
}: {
  account: Account;
  lines: StatementLine[];
  onChanged: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
  onError: (message: string | null) => void;
}) {
  const [reversingId, setReversingId] = useState<string | null>(null);

  async function handleReverse(transactionId: string, description: string) {
    onError(null);
    setReversingId(transactionId);
    try {
      await api.reverseTransaction(transactionId, "Reversed from dashboard");
      onChanged();
      onToast(`Reversed "${description}"`);
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Reverse failed.";
      onError(message);
      onToast(message, "error");
    } finally {
      setReversingId(null);
    }
  }

  const now = new Date();

  return (
    // The rows scroll inside this box rather than growing the page. A
    // scrollable region has to be focusable to be reachable by keyboard.
    <div className="table-scroll" tabIndex={0} role="region" aria-label={`Statement for ${account.name}`}>
      <table className="statement">
        <thead>
          <tr>
            <th scope="col" className="col-date">
              Date
            </th>
            <th scope="col">Description</th>
            <th scope="col" className="num col-debit">
              Debit
            </th>
            <th scope="col" className="num col-credit">
              Credit
            </th>
            <th scope="col" className="num col-amount">
              Amount
            </th>
            <th scope="col" className="num">
              Balance
            </th>
            <th scope="col" className="col-action">
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.length === 0 && (
            <tr>
              <td colSpan={7}>
                <div className="empty">
                  <strong>No activity yet</strong>
                  Transfers into or out of this account will show up here.
                </div>
              </td>
            </tr>
          )}
          {lines.map((line) => {
            const increase = entryIncreasesBalance(account.type, line.direction);
            const amount = (
              <span className={increase ? "positive" : "negative"}>
                {increase ? "+" : "-"}
                {formatMoney(line.amountMinor)}
              </span>
            );
            const when = formatShortDateTime(new Date(line.createdAt), now);
            return (
              <tr key={line.entryId}>
                <td className="col-date">{when}</td>
                <td className="col-desc">
                  {line.description}
                  {isLargeAmount(line.amountMinor) && (
                    <span className="flag" title="Above the demo large-transaction threshold of $1,000">
                      Large
                    </span>
                  )}
                  {/* Phones drop the Date column and show it here instead. */}
                  <span className="desc-date" aria-hidden>
                    {when}
                  </span>
                </td>
                <td className="num col-debit">{line.direction === "DEBIT" && amount}</td>
                <td className="num col-credit">{line.direction === "CREDIT" && amount}</td>
                <td className="num col-amount">{amount}</td>
                <td className="num col-balance">{formatMoney(line.runningBalanceMinor)}</td>
                <td className="col-action">
                  <button
                    type="button"
                    className="btn btn-icon btn-ghost row-action"
                    disabled={reversingId === line.transactionId}
                    onClick={() => handleReverse(line.transactionId, line.description)}
                    aria-label={`Reverse "${line.description}"`}
                    title="Post an offsetting reversal transaction"
                  >
                    <ArrowUUpLeft size={16} aria-hidden />
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
