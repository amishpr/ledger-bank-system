import { useMemo, useState } from "react";
import type { Account, StatementLine } from "../api/types";
import { formatMoney } from "../money";
import { useContainerWidth } from "../useContainerWidth";

const HEIGHT = 240;
const MARGIN = { top: 16, right: 16, bottom: 26, left: 68 };
const TICK_COUNT = 4;

// A "nice" round step (1/2/5 x a power of ten) so axis labels read like
// $500 / $1,000 / $1,500 instead of whatever odd number the data happens
// to span - the same rule any charting library's linear scale would apply.
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

function dollarsToMinorString(dollars: number): string {
  return Math.round(dollars * 100).toString();
}

interface Point {
  date: Date;
  description: string;
  balanceMinor: string;
  balance: number; // display-only, for positioning - never used for arithmetic
}

export function BalanceHistoryChart({ account, lines }: { account: Account | undefined; lines: StatementLine[] }) {
  const [containerRef, width] = useContainerWidth<HTMLDivElement>();
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);

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

  if (!account) {
    return (
      <div className="panel">
        <h2>Balance history</h2>
        <p className="hint">Select an account to chart its balance over time.</p>
      </div>
    );
  }

  if (points.length < 2) {
    return (
      <div className="panel">
        <h2>Balance history — {account.name}</h2>
        <p className="hint">Not enough history yet to chart.</p>
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

  const xFor = (i: number) => MARGIN.left + (points.length === 1 ? 0 : (i / (points.length - 1)) * plotWidth);
  const yFor = (v: number) => MARGIN.top + plotHeight - ((v - domainMin) / range) * plotHeight;

  const trendingUp = points[points.length - 1]!.balance >= points[0]!.balance;
  const lineColor = trendingUp ? "var(--positive)" : "var(--negative)";

  const linePath = points.map((p, i) => `${i === 0 ? "M" : "L"}${xFor(i).toFixed(1)},${yFor(p.balance).toFixed(1)}`).join(" ");
  const areaPath = `${linePath} L${xFor(points.length - 1).toFixed(1)},${(MARGIN.top + plotHeight).toFixed(1)} L${xFor(0).toFixed(1)},${(MARGIN.top + plotHeight).toFixed(1)} Z`;

  const xLabelCount = Math.min(6, points.length);
  const xLabelIndices = Array.from({ length: xLabelCount }, (_, i) =>
    Math.round((i / Math.max(1, xLabelCount - 1)) * (points.length - 1)),
  );

  // A fresh demo session often has every point on the same calendar day,
  // where a date-only axis would just repeat "Sep 16" six times. Falling
  // back to a time label when the whole visible range is one day keeps
  // the axis actually informative in that common case.
  const sameDay = points[0]!.date.toDateString() === points[points.length - 1]!.date.toDateString();
  function xLabelFor(i: number): string {
    const d = points[i]!.date;
    return sameDay
      ? d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })
      : d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  function handleMove(e: React.MouseEvent<SVGRectElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - rect.left + MARGIN.left;
    const ratio = Math.min(1, Math.max(0, (px - MARGIN.left) / plotWidth));
    setHoverIndex(Math.round(ratio * (points.length - 1)));
  }

  const hovered = hoverIndex !== null ? points[hoverIndex] : undefined;
  const last = points[points.length - 1]!;

  // Keep the tooltip from running off either edge of the chart.
  const tooltipWidth = 190;
  const rawTooltipX = hoverIndex !== null ? xFor(hoverIndex) + 12 : 0;
  const tooltipX = Math.min(Math.max(rawTooltipX, MARGIN.left), width - tooltipWidth - 4);

  return (
    <div className="panel">
      <div className="balance-chart-header">
        <h2>Balance history — {account.name}</h2>
        <span className="balance-chart-end" style={{ color: lineColor }}>
          {formatMoney(last.balanceMinor)}
        </span>
      </div>
      <div className="balance-chart-wrap" ref={containerRef}>
        <svg width={width} height={HEIGHT} role="img" aria-label={`${account.name} balance over time, ending at ${formatMoney(last.balanceMinor)}`}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={MARGIN.left} x2={width - MARGIN.right} y1={yFor(t)} y2={yFor(t)} className="chart-gridline" />
              <text x={MARGIN.left - 8} y={yFor(t)} className="chart-axis-label" textAnchor="end" dominantBaseline="middle">
                {formatMoney(dollarsToMinorString(t))}
              </text>
            </g>
          ))}

          {xLabelIndices.map((i) => (
            <text key={i} x={xFor(i)} y={HEIGHT - 8} className="chart-axis-label" textAnchor="middle">
              {xLabelFor(i)}
            </text>
          ))}

          <path d={areaPath} fill={lineColor} opacity={0.1} stroke="none" />
          <path d={linePath} fill="none" stroke={lineColor} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />

          <circle cx={xFor(points.length - 1)} cy={yFor(last.balance)} r={4} fill={lineColor} stroke="var(--panel-bg)" strokeWidth={2} />

          {hoverIndex !== null && hovered && (
            <>
              <line
                x1={xFor(hoverIndex)}
                x2={xFor(hoverIndex)}
                y1={MARGIN.top}
                y2={MARGIN.top + plotHeight}
                className="chart-crosshair"
              />
              <circle cx={xFor(hoverIndex)} cy={yFor(hovered.balance)} r={5} fill={lineColor} stroke="var(--panel-bg)" strokeWidth={2} />
            </>
          )}

          <rect
            className="balance-chart-hit-area"
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
          <div className="balance-chart-tooltip" style={{ left: tooltipX, top: MARGIN.top }}>
            <div className="balance-chart-tooltip-value" style={{ color: lineColor }}>
              {formatMoney(hovered.balanceMinor)}
            </div>
            <div className="balance-chart-tooltip-date">{hovered.date.toLocaleString()}</div>
            <div className="balance-chart-tooltip-desc">{hovered.description}</div>
          </div>
        )}
      </div>
      <p className="hint balance-chart-footnote">
        Showing the {points.length} most recent balance points. Every value here also appears in the statement table
        above.
      </p>
    </div>
  );
}
