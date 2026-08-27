import { Pulse } from "@phosphor-icons/react";
import type { LedgerEvent } from "../api/types";
import { isLargeAmount } from "../flags";
import { formatMoney } from "../money";

const TAG: Record<string, { label: string; className: string }> = {
  "transaction.posted": { label: "Posted", className: "feed-tag" },
  "transaction.reversed": { label: "Reversal", className: "feed-tag feed-tag-reversal" },
  "recurring.executed": { label: "Scheduled", className: "feed-tag" },
};

export function ActivityFeed({ events }: { events: LedgerEvent[] }) {
  const renderable = events.filter(
    (e) => e.type === "transaction.posted" || e.type === "transaction.reversed" || e.type === "recurring.executed",
  );

  return (
    <section className="panel feed" aria-labelledby="feed-title">
      <div className="panel-head">
        <h2 id="feed-title">Live activity</h2>
      </div>

      {renderable.length === 0 ? (
        <div className="empty">
          <Pulse size={20} aria-hidden />
          <strong>Waiting for activity</strong>
          Post a transfer and it shows up here the moment it lands, along with anything the scheduler runs.
        </div>
      ) : (
        <ul className="feed-list">
          {renderable.map((event) => {
            const total = event.transaction.entries
              .filter((e) => e.direction === "DEBIT")
              .reduce((sum, e) => sum + BigInt(e.amountMinor), 0n);
            const tag = TAG[event.type]!;
            return (
              <li key={event.transaction.id + event.type} className="feed-item">
                <span className={tag.className}>{tag.label}</span>
                <span className="feed-desc" title={event.transaction.description}>
                  {event.transaction.description}
                  {isLargeAmount(total) && (
                    <span className="flag" title="Above the demo large-transaction threshold of $1,000">
                      Large
                    </span>
                  )}
                </span>
                <span className="feed-amount">{formatMoney(total)}</span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
