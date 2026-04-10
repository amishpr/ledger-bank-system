// Every dollar amount that touches the wire or the DOM passes through one
// of these two functions. Neither ever routes a value through a JS
// `number`, so a display or input round-trip can't quietly drop cents.

export function formatMoney(amountMinor: string | bigint): string {
  const n = typeof amountMinor === "bigint" ? amountMinor : BigInt(amountMinor);
  const negative = n < 0n;
  const abs = negative ? -n : n;
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const cents = (abs % 100n).toString().padStart(2, "0");
  return `${negative ? "-" : ""}$${whole}.${cents}`;
}

export function parseDollarsToCents(input: string): bigint {
  const trimmed = input.trim();
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(trimmed);
  if (!match) {
    throw new Error("Enter an amount like 12.50");
  }
  const [, whole, fraction = ""] = match;
  const cents = fraction.padEnd(2, "0");
  return BigInt(whole!) * 100n + BigInt(cents);
}
