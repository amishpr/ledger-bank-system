import { useState } from "react";
import type { SpendingBreakdown, SpendingByCategory } from "../api/types";
import { formatMoney } from "../money";

const CATEGORY_COLOR_VARS = ["--cat-1", "--cat-2", "--cat-3", "--cat-4", "--cat-5"];

function monthLabel(month: string): string {
  const [year, m] = month.split("-").map(Number);
  return new Date(year!, m! - 1, 1).toLocaleDateString(undefined, { month: "short", year: "numeric" });
}

// The categorical palette has 5 validated slots (see index.css) - cycling
// back to slot 1 for a 6th category would make two different categories
// share a color, so anything past the palette size gets folded into Other
// instead of cycling. byCategory already arrives
// sorted by total descending, so folding the smallest entries is just
// keeping the top 4 and summing whatever's left.
function withOtherFold(categories: SpendingByCategory[]): (SpendingByCategory & { isOther?: boolean })[] {
  if (categories.length <= CATEGORY_COLOR_VARS.length) return categories;
  const top = categories.slice(0, CATEGORY_COLOR_VARS.length - 1);
  const rest = categories.slice(CATEGORY_COLOR_VARS.length - 1);
  const otherTotal = rest.reduce((sum, c) => sum + BigInt(c.totalMinor), 0n);
  return [
    ...top,
    { accountId: "other", accountName: `Other (${rest.length})`, totalMinor: otherTotal.toString(), isOther: true },
  ];
}

export function SpendingChart({ breakdown }: { breakdown: SpendingBreakdown }) {
  const [view, setView] = useState<"category" | "month">("category");

  const hasData = breakdown.byCategory.length > 0;

  return (
    <div className="panel">
      <div className="chart-header">
        <h2>Spending breakdown</h2>
        <div className="chart-tabs">
          <button className={view === "category" ? "active" : ""} onClick={() => setView("category")}>
            By category
          </button>
          <button className={view === "month" ? "active" : ""} onClick={() => setView("month")}>
            By month
          </button>
        </div>
      </div>

      {!hasData && <p className="hint">No expense activity yet.</p>}

      {hasData && view === "category" && (
        <div className="chart-rows" role="img" aria-label="Total spending by category">
          {(() => {
            const rows = withOtherFold(breakdown.byCategory);
            const max = Math.max(...rows.map((c) => Number(c.totalMinor)));
            return rows.map((cat, i) => {
              const pct = max > 0 ? (Number(cat.totalMinor) / max) * 100 : 0;
              const color = cat.isOther ? "var(--muted)" : `var(${CATEGORY_COLOR_VARS[i]})`;
              return (
                <div className="chart-row" key={cat.accountId}>
                  <span className="chart-row-label">{cat.accountName}</span>
                  <div className="chart-row-track">
                    <div className="chart-row-bar" style={{ width: `${pct}%`, background: color }} title={formatMoney(cat.totalMinor)} />
                  </div>
                  <span className="chart-row-value">{formatMoney(cat.totalMinor)}</span>
                </div>
              );
            });
          })()}
        </div>
      )}

      {hasData && view === "month" && (
        <div className="chart-columns" role="img" aria-label="Total spending by month">
          {(() => {
            const max = Math.max(...breakdown.byMonth.map((m) => Number(m.totalMinor)));
            return breakdown.byMonth.map((m) => {
              const pct = max > 0 ? (Number(m.totalMinor) / max) * 100 : 0;
              return (
                <div className="chart-column" key={m.month}>
                  <span className="chart-column-value">{formatMoney(m.totalMinor)}</span>
                  <div className="chart-column-track">
                    <div className="chart-column-bar" style={{ height: `${pct}%` }} title={formatMoney(m.totalMinor)} />
                  </div>
                  <span className="chart-column-label">{monthLabel(m.month)}</span>
                </div>
              );
            });
          })()}
        </div>
      )}
    </div>
  );
}
