import type { StatementLine } from "./types.js";

export function minorToDollarString(amountMinor: bigint): string {
  const negative = amountMinor < 0n;
  const abs = negative ? -amountMinor : amountMinor;
  const whole = abs / 100n;
  const cents = (abs % 100n).toString().padStart(2, "0");
  return `${negative ? "-" : ""}${whole}.${cents}`;
}

function escapeCsvField(value: string): string {
  if (/[",\n]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

// Exported oldest-first, the order a downloaded record is naturally read
// in, even though the API and the on-screen table show newest-first.
export function buildStatementCsv(lines: StatementLine[]): string {
  const header = ["Date", "Description", "Direction", "Amount", "Running Balance"];
  const rows = [...lines].reverse().map((line) => [
    new Date(line.createdAt).toISOString(),
    line.description,
    line.direction,
    minorToDollarString(line.amountMinor),
    minorToDollarString(line.runningBalanceMinor),
  ]);
  return [header, ...rows].map((row) => row.map(escapeCsvField).join(",")).join("\r\n");
}
