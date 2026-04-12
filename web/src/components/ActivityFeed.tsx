import type { LedgerEvent } from "../api/types";
import { isLargeAmount } from "../flags";
import { formatMoney } from "../money";

export function ActivityFeed({ events }: { events: LedgerEvent[] }) {
  return (
    <div className="panel">
      <h2>Live activity</h2>
      {events.length === 0 && <p className="hint">Transactions posted anywhere will appear here in real time.</p>}
      <ul className="activity-feed">
        {events.map((event) => {
          if (event.type !== "transaction.posted" && event.type !== "transaction.reversed") return null;
          const total = event.transaction.entries
            .filter((e) => e.direction === "DEBIT")
            .reduce((sum, e) => sum + BigInt(e.amountMinor), 0n);
          return (
            <li key={event.transaction.id + event.type} className="activity-item">
              <span className={`activity-tag ${event.type === "transaction.reversed" ? "reversed" : "posted"}`}>
                {event.type === "transaction.reversed" ? "Reversal" : "Posted"}
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
