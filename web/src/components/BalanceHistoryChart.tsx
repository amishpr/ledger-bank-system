import { useId, useMemo, useState } from "react";
import type { Account, StatementLine } from "../api/types";
import { formatMoney } from "../money";
import { useContainerWidth } from "../useContainerWidth";

const HEIGHT = 188;
const MARGIN = { top: 12, right: 8, bottom: 24, left: 52 };
const TICK_COUNT = 4;
const HALF_YEAR_MS = 183 * 86_400_000;

// Axis labels only, so "$15K" instead of "$15,000.00". Every exact value
// is still in the tooltip, the statement and the CSV export.
const compactUsd = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  notation: "compact",
  minimumFractionDigits: 0,
  maximumFractionDigits: 1,
});

// A "nice" round step (1/2/5 x a power of ten) so axis labels read like
// $5K / $10K / $15K instead of whatever odd number the data happens to
// span - the same rule any charting library's linear scale would apply.
function niceTicks(min: number, max: number, count: number): number[] {
  if (min === max) return [min];
  const rawStep = (max - min) / count;
  const magnitude = 10 ** Math.floor(Math.log10(rawStep));
  const residual = rawStep / magnitude;
  const step = residual > 5 ? 10 * magnitude : residual > 2 ? 5 * magnitude : residual > 1 ? 2 * magnitude : magnitude;
  const niceMin = Math.floor(min / step) * step;
  const niceMax = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let t = niceMin; t <= niceMax + step * 0.001; t += step) ticks.push(t);
  return ticks;
}

interface Point {
  date: Date;
  description: string;
  balanceMinor: string;
  balance: number; // display-only, for positioning - never used for arithmetic
}

export function BalanceHistoryChart({ account, lines }: { account: Account; lines: StatementLine[] }) {
  const [containerRef, width] = useContainerWidth<HTMLDivElement>();
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  // Used inside url(#...), which does not tolerate the punctuation useId
  // can produce.
  const gradientId = `balance-fill-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;

  const points: Point[] = useMemo(
    () =>
      [...lines].reverse().map((l) => ({
        date: new Date(l.createdAt),
        description: l.description,
        balanceMinor: l.runningBalanceMinor,
        balance: Number(l.runningBalanceMinor) / 100,
      })),
    [lines],
  );

  if (points.length < 2) {
    return (
      <div className="chart">
        <p className="hint">Not enough history to chart yet. Post a couple of transfers and it will fill in.</p>
      </div>
    );
  }

  const plotWidth = Math.max(width - MARGIN.left - MARGIN.right, 80);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

  const balances = points.map((p) => p.balance);
  const ticks = niceTicks(Math.min(...balances), Math.max(...balances), TICK_COUNT);
  const domainMin = Math.min(...balances, ticks[0]!);
  const domainMax = Math.max(...balances, ticks[ticks.length - 1]!);
  const range = domainMax - domainMin || 1;

  const xFor = (i: number) => MARGIN.left + (i / (points.length - 1)) * plotWidth;
  const yFor = (v: number) => MARGIN.top + plotHeight - ((v - domainMin) / range) * plotHeight;

  const first = points[0]!;
  const last = points[points.length - 1]!;
  const trendingUp = last.balance >= first.balance;
  const lineColor = trendingUp ? "var(--positive)" : "var(--negative)";

  const linePath = points.map((p, i) => `${i === 0 ? "M" : "L"}${xFor(i).toFixed(1)},${yFor(p.balance).toFixed(1)}`).join(" ");
  const baseline = (MARGIN.top + plotHeight).toFixed(1);
  const areaPath = `${linePath} L${xFor(points.length - 1).toFixed(1)},${baseline} L${xFor(0).toFixed(1)},${baseline} Z`;

  const xLabelCount = Math.min(width < 480 ? 4 : 6, points.length);
  const xLabelIndices = Array.from({ length: xLabelCount }, (_, i) =>
    Math.round((i / Math.max(1, xLabelCount - 1)) * (points.length - 1)),
  );

  // A fresh demo session often has every point on one calendar day, where
  // a date axis would repeat "Sep 16" six times, so that case shows times.
  // Past half a year, "Sep 25" is ambiguous about the year, so it shows
  // the month and a short year instead.
  const spanMs = last.date.getTime() - first.date.getTime();
  const sameDay = first.date.toDateString() === last.date.toDateString();
  function xLabelFor(i: number): string {
    const d = points[i]!.date;
    if (sameDay) return d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    if (spanMs > HALF_YEAR_MS) {
      return `${d.toLocaleDateString(undefined, { month: "short" })} '${String(d.getFullYear()).slice(2)}`;
    }
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  function handleMove(e: React.MouseEvent<SVGRectElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / plotWidth));
    setHoverIndex(Math.round(ratio * (points.length - 1)));
  }

  const hovered = hoverIndex !== null ? points[hoverIndex] : undefined;

  // Keep the tooltip from running off either edge of the chart.
  const tooltipWidth = 200;
  const rawTooltipX = hoverIndex !== null ? xFor(hoverIndex) + 12 : 0;
  const tooltipX =
    rawTooltipX + tooltipWidth > width ? Math.max(MARGIN.left, xFor(hoverIndex ?? 0) - tooltipWidth - 12) : rawTooltipX;

  return (
    <div className="chart" ref={containerRef}>
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        aria-label={`${account.name} balance over the last ${points.length} changes, from ${formatMoney(first.balanceMinor)} to ${formatMoney(last.balanceMinor)}`}
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={lineColor} stopOpacity={0.22} />
            <stop offset="1" stopColor={lineColor} stopOpacity={0} />
          </linearGradient>
        </defs>

        {ticks.map((t) => (
          <g key={t}>
            <line x1={MARGIN.left} x2={width - MARGIN.right} y1={yFor(t)} y2={yFor(t)} className="chart-gridline" />
            <text x={MARGIN.left - 10} y={yFor(t)} className="chart-axis-label" textAnchor="end" dominantBaseline="middle">
              {compactUsd.format(t)}
            </text>
          </g>
        ))}

        {xLabelIndices.map((i, n) => (
          <text
            key={i}
            x={xFor(i)}
            y={HEIGHT - 6}
            className="chart-axis-label"
            textAnchor={n === 0 ? "start" : n === xLabelIndices.length - 1 ? "end" : "middle"}
          >
            {xLabelFor(i)}
          </text>
        ))}

        <path d={areaPath} fill={`url(#${gradientId})`} stroke="none" />
        <path d={linePath} fill="none" stroke={lineColor} strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={xFor(points.length - 1)} cy={yFor(last.balance)} r={3.5} fill={lineColor} stroke="var(--surface)" strokeWidth={2} />

        {hoverIndex !== null && hovered && (
          <>
            <line
              x1={xFor(hoverIndex)}
              x2={xFor(hoverIndex)}
              y1={MARGIN.top}
              y2={MARGIN.top + plotHeight}
              className="chart-crosshair"
            />
            <circle cx={xFor(hoverIndex)} cy={yFor(hovered.balance)} r={4.5} fill={lineColor} stroke="var(--surface)" strokeWidth={2} />
          </>
        )}

        <rect
          className="chart-hit-area"
          x={MARGIN.left}
          y={MARGIN.top}
          width={plotWidth}
          height={plotHeight}
          fill="transparent"
          onMouseMove={handleMove}
          onMouseLeave={() => setHoverIndex(null)}
        />
      </svg>

      {hoverIndex !== null && hovered && (
        <div className="chart-tooltip" style={{ left: tooltipX, top: MARGIN.top }}>
          <div className="chart-tooltip-value">{formatMoney(hovered.balanceMinor)}</div>
          <div className="chart-tooltip-date">
            {hovered.date.toLocaleString(undefined, {
              month: "short",
              day: "numeric",
              year: "numeric",
              hour: "numeric",
              minute: "2-digit",
            })}
          </div>
          <div className="chart-tooltip-desc">{hovered.description}</div>
        </div>
      )}

      <p className="chart-caption">Last {points.length} balance changes. The CSV export has the full history.</p>
    </div>
  );
}
