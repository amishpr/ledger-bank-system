// A deliberately simple stand-in for the kind of rule a real compliance or
// fraud system would run: anything above a threshold gets flagged for a
// second look. A production version would use rolling per-account
// averages instead of one fixed number, but the UI treatment is the same.
export const LARGE_AMOUNT_THRESHOLD_MINOR = 100_000n; // $1,000.00

export function isLargeAmount(amountMinor: string | bigint): boolean {
  const value = typeof amountMinor === "bigint" ? amountMinor : BigInt(amountMinor);
  const abs = value < 0n ? -value : value;
  return abs >= LARGE_AMOUNT_THRESHOLD_MINOR;
}
