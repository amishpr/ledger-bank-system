import { beforeAll, describe, expect, it } from "vitest";
import { getSpendingBreakdown } from "./insights";
import { getStatement, listAccounts } from "./ledger";
import { seedDemoDb } from "./seed";

// The seed amounts were tuned against this exact pseudo-random sequence to
// keep the main checking account positive for a whole year. That only
// holds as long as the sequence and the amounts stay in step, so it is
// worth asserting rather than assuming.

describe("seedDemoDb", () => {
  beforeAll(async () => {
    await seedDemoDb();
  });

  it("builds a year of activity", () => {
    const checking = listAccounts().find((a) => a.name === "Checking - Alex")!;
    const lines = getStatement(checking.id, Number.MAX_SAFE_INTEGER);

    expect(lines.length).toBeGreaterThan(200);
    const oldest = lines[lines.length - 1]!.createdAt;
    const newest = lines[0]!.createdAt;
    const daysCovered = (newest.getTime() - oldest.getTime()) / 86_400_000;
    expect(daysCovered).toBeGreaterThan(360);
  });

  it("never takes the checking account negative at any point in the year", () => {
    const checking = listAccounts().find((a) => a.name === "Checking - Alex")!;
    const lines = getStatement(checking.id, Number.MAX_SAFE_INTEGER);

    for (const line of lines) {
      expect(line.runningBalanceMinor).toBeGreaterThanOrEqual(0n);
    }
    expect(checking.balanceMinor).toBeGreaterThan(0n);
  });

  it("produces more expense categories than the chart has colors", () => {
    // Six categories against five validated palette slots. This is the
    // case that makes SpendingChart fold the smallest ones into "Other"
    // rather than cycling back to a color already in use.
    const { byCategory, byMonth } = getSpendingBreakdown();
    expect(byCategory.length).toBe(6);
    expect(byMonth.length).toBeGreaterThanOrEqual(12);
  });
});
