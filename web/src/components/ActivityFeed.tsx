import type { LedgerEvent } from "../api/types";
import { isLargeAmount } from "../flags";
import { formatMoney } from "../money";

const TAG_LABEL: Record<string, string> = {
  "transaction.posted": "Posted",
  "transaction.reversed": "Reversal",
  "recurring.executed": "Auto",
};

export function ActivityFeed({ events }: { events: LedgerEvent[] }) {
  const renderable = events.filter(
    (e) => e.type === "transaction.posted" || e.type === "transaction.reversed" || e.type === "recurring.executed",
  );

  return (
    <div className="panel">
      <h2>Live activity</h2>
      {renderable.length === 0 && <p className="hint">Transactions posted anywhere will appear here in real time.</p>}
      <ul className="activity-feed">
        {renderable.map((event) => {
          const total = event.transaction.entries
            .filter((e) => e.direction === "DEBIT")
            .reduce((sum, e) => sum + BigInt(e.amountMinor), 0n);
          return (
            <li key={event.transaction.id + event.type} className="activity-item">
              <span className={`activity-tag ${event.type === "transaction.reversed" ? "reversed" : event.type === "recurring.executed" ? "auto" : "posted"}`}>
                {TAG_LABEL[event.type]}
              </span>
              <span className="activity-description">
                {event.transaction.description}
                {isLargeAmount(total) && (
                  <span className="flag-badge" title="Above the demo large-transaction threshold of $1,000">
                    Large
                  </span>
                )}
              </span>
              <span className="activity-amount">{formatMoney(total)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
