import { useId, useState } from "react";
import type { SpendingBreakdown, SpendingByCategory } from "../api/types";
import { shortAccountName } from "../labels";
import { formatMoney } from "../money";

type View = "category" | "month";

// Bar length carries the value and each bar is labeled, so one hue is
// enough. Past this many categories the smallest ones fold into "Other"
// so the list stays short.
const MAX_CATEGORY_ROWS = 5;

function monthLabel(month: string, style: "short" | "long"): string {
  const [year, m] = month.split("-").map(Number);
  const date = new Date(year!, m! - 1, 1);
  return style === "short"
    ? date.toLocaleDateString(undefined, { month: "short" })
    : date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

// byCategory already arrives sorted by total descending, so folding the
// smallest entries is just keeping the top ones and summing the rest.
function withOtherFold(categories: SpendingByCategory[]): (SpendingByCategory & { isOther?: boolean })[] {
  if (categories.length <= MAX_CATEGORY_ROWS) return categories;
  const top = categories.slice(0, MAX_CATEGORY_ROWS - 1);
  const rest = categories.slice(MAX_CATEGORY_ROWS - 1);
  const otherTotal = rest.reduce((sum, c) => sum + BigInt(c.totalMinor), 0n);
  return [...top, { accountId: "other", accountName: `Other (${rest.length})`, totalMinor: otherTotal.toString(), isOther: true }];
}

export function SpendingChart({ breakdown }: { breakdown: SpendingBreakdown }) {
  const [view, setView] = useState<View>("category");
  const [focusMonth, setFocusMonth] = useState<string | null>(null);
  const id = useId();

  const hasData = breakdown.byCategory.length > 0;
  const total = breakdown.byCategory.reduce((sum, c) => sum + BigInt(c.totalMinor), 0n);
  const focused = breakdown.byMonth.find((m) => m.month === focusMonth);

  function tab(value: View, label: string) {
    const selected = view === value;
    return (
      <button
        type="button"
        role="tab"
        id={`${id}-${value}`}
        className="tab"
        aria-selected={selected}
        aria-controls={`${id}-panel`}
        tabIndex={selected ? 0 : -1}
        onClick={() => setView(value)}
        // Two tabs, so either arrow key just moves to the other one.
        onKeyDown={(e) => {
          if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
          const other = value === "category" ? "month" : "category";
          setView(other);
          document.getElementById(`${id}-${other}`)?.focus();
        }}
      >
        {label}
      </button>
    );
  }

  return (
    <section className="panel spend" aria-labelledby={`${id}-title`}>
      <div className="panel-head">
        <h2 id={`${id}-title`}>Spending</h2>
        <div className="tabs" role="tablist" aria-label="Group spending by">
          {tab("category", "By category")}
          {tab("month", "By month")}
        </div>
      </div>

      <div id={`${id}-panel`} role="tabpanel" aria-labelledby={`${id}-${view}`}>
        {!hasData && (
          <div className="empty">
            <strong>No spending yet</strong>
            Payments into expense accounts are totalled here.
          </div>
        )}

        {hasData && view === "category" && (
          <ul className="bars">
            {(() => {
              const rows = withOtherFold(breakdown.byCategory);
              const max = Math.max(...rows.map((c) => Number(c.totalMinor)));
              return rows.map((cat) => {
                const pct = max > 0 ? (Number(cat.totalMinor) / max) * 100 : 0;
                return (
                  <li className="bar-row" key={cat.accountId}>
                    <span className="bar-label" title={cat.accountName}>
                      {shortAccountName(cat.accountName, "EXPENSE")}
                    </span>
                    <span>
                      <span className={`bar${cat.isOther ? " bar-other" : ""}`} style={{ width: `${pct}%` }} />
                    </span>
                    <span className="bar-value">{formatMoney(cat.totalMinor)}</span>
                  </li>
                );
              });
            })()}
          </ul>
        )}

        {hasData && view === "month" && (
          <div className="columns" onMouseLeave={() => setFocusMonth(null)}>
            {(() => {
              const max = Math.max(...breakdown.byMonth.map((m) => Number(m.totalMinor)));
              return breakdown.byMonth.map((m) => {
                const pct = max > 0 ? (Number(m.totalMinor) / max) * 100 : 0;
                return (
                  <div
                    className="column"
                    key={m.month}
                    tabIndex={0}
                    onMouseEnter={() => setFocusMonth(m.month)}
                    onFocus={() => setFocusMonth(m.month)}
                    onBlur={() => setFocusMonth(null)}
                    aria-label={`${monthLabel(m.month, "long")}: ${formatMoney(m.totalMinor)}`}
                  >
                    <div className="column-track">
                      <div className="column-bar" style={{ height: `${pct}%` }} />
                    </div>
                    <span className="column-label" aria-hidden>
                      {monthLabel(m.month, "short")}
                    </span>
                  </div>
                );
              });
            })()}
          </div>
        )}
      </div>

      {hasData && (
        <div className="spend-foot">
          <span>{view === "month" && focused ? monthLabel(focused.month, "long") : "Total"}</span>
          <span className="num">{formatMoney(view === "month" && focused ? focused.totalMinor : total)}</span>
        </div>
      )}
    </section>
  );
}
