import { prisma } from "../src/db.js";
import { createAccount, postTransaction, reverseTransaction } from "../src/ledger/ledgerService.js";
import { createRecurringTransfer } from "../src/ledger/recurringService.js";
import type { EntryDirection } from "@prisma/client";

// A small seedable PRNG (mulberry32) instead of Math.random(), so running
// `npm run seed` twice produces the exact same year of activity both
// times. That matters here: the amounts below were sized against this
// specific sequence to keep Checking - Alex comfortably positive all
// year without needing a try/catch around every posting.
function mulberry32(seed: number) {
  return function random() {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const random = mulberry32(1337);
const randomInt = (min: number, max: number) => Math.floor(random() * (max - min + 1)) + min;
const pick = <T>(items: T[]): T => items[randomInt(0, items.length - 1)]!;

function addDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  next.setHours(randomInt(8, 20), randomInt(0, 59), randomInt(0, 59), 0);
  return next;
}

function addMonths(date: Date, months: number): Date {
  const next = new Date(date);
  next.setMonth(next.getMonth() + months);
  next.setHours(randomInt(8, 20), randomInt(0, 59), 0, 0);
  return next;
}

interface SeedEvent {
  date: Date;
  description: string;
  entries: { accountId: string; direction: EntryDirection; amountMinor: bigint }[];
}

function spend(expenseId: string, fromId: string, amountMinor: bigint, description: string, date: Date): SeedEvent {
  return {
    date,
    description,
    entries: [
      { accountId: expenseId, direction: "DEBIT", amountMinor },
      { accountId: fromId, direction: "CREDIT", amountMinor },
    ],
  };
}

function earn(revenueId: string, toId: string, amountMinor: bigint, description: string, date: Date): SeedEvent {
  return {
    date,
    description,
    entries: [
      { accountId: toId, direction: "DEBIT", amountMinor },
      { accountId: revenueId, direction: "CREDIT", amountMinor },
    ],
  };
}

function transfer(fromId: string, toId: string, amountMinor: bigint, description: string, date: Date): SeedEvent {
  return {
    date,
    description,
    entries: [
      { accountId: fromId, direction: "CREDIT", amountMinor },
      { accountId: toId, direction: "DEBIT", amountMinor },
    ],
  };
}

// Posts through the normal, invariant-checked path and then backdates the
// row afterward. postTransaction itself never accepts a caller-supplied
// timestamp - that's deliberate, it's what keeps a client from ever being
// able to lie about when something really happened through the API. This
// script is the one place that reaches past that, directly through Prisma,
// specifically to build a realistic-looking demo history.
async function postHistorical(event: SeedEvent) {
  const result = await postTransaction({ description: event.description, entries: event.entries });
  await prisma.transaction.update({ where: { id: result.transaction.id }, data: { createdAt: event.date } });
  await prisma.entry.updateMany({ where: { transactionId: result.transaction.id }, data: { createdAt: event.date } });
  return result;
}

async function main() {
  console.log("Resetting database...");
  await prisma.auditLog.deleteMany();
  await prisma.recurringTransfer.deleteMany();
  await prisma.entry.deleteMany();
  await prisma.transaction.deleteMany();
  await prisma.account.deleteMany();

  console.log("Creating accounts...");
  const equity = await createAccount({ name: "Opening Balance Equity", type: "EQUITY" });
  const alexChecking = await createAccount({ name: "Checking - Alex", type: "ASSET" });
  const alexSavings = await createAccount({ name: "Savings - Alex", type: "ASSET" });
  const jordanChecking = await createAccount({ name: "Checking - Jordan", type: "ASSET" });
  const paycheckRevenue = await createAccount({ name: "Revenue - Paycheck", type: "REVENUE" });
  const interestRevenue = await createAccount({ name: "Revenue - Interest", type: "REVENUE" });
  const feesExpense = await createAccount({ name: "Expenses - Bank Fees", type: "EXPENSE" });
  const diningExpense = await createAccount({ name: "Expenses - Dining", type: "EXPENSE" });
  const subscriptionsExpense = await createAccount({ name: "Expenses - Subscriptions", type: "EXPENSE" });
  const rentExpense = await createAccount({ name: "Expenses - Rent", type: "EXPENSE" });
  const groceriesExpense = await createAccount({ name: "Expenses - Groceries", type: "EXPENSE" });

  const today = new Date();
  const yearAgo = addDays(today, -365);

  console.log("Funding accounts via opening balances (365 days ago)...");
  await postHistorical({
    date: yearAgo,
    description: "Opening balance",
    entries: [
      { accountId: alexChecking.id, direction: "DEBIT", amountMinor: 250_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 250_000n },
    ],
  });
  await postHistorical({
    date: yearAgo,
    description: "Opening balance",
    entries: [
      { accountId: alexSavings.id, direction: "DEBIT", amountMinor: 1_000_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 1_000_000n },
    ],
  });
  await postHistorical({
    date: yearAgo,
    description: "Opening balance",
    entries: [
      { accountId: jordanChecking.id, direction: "DEBIT", amountMinor: 80_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 80_000n },
    ],
  });

  console.log("Generating a year of everyday activity...");
  const events: SeedEvent[] = [];
  const diningSpots = ["Coffee shop", "Lunch out", "Dinner with friends", "Takeout", "Brunch", "Pizza night"];
  const bigPurchases = ["Flight tickets", "New laptop", "Furniture", "Holiday shopping"];

  // Biweekly paycheck, starting a few days in.
  for (let day = 3; day < 365; day += 14) {
    events.push(
      earn(paycheckRevenue.id, alexChecking.id, 240_000n, "Paycheck deposit", addDays(yearAgo, day)),
    );
  }

  // Weekly groceries, plus dining out once a week and sometimes twice.
  for (let week = 0; week < 52; week++) {
    const groceryDay = week * 7 + randomInt(0, 6);
    events.push(
      spend(
        groceriesExpense.id,
        alexChecking.id,
        BigInt(randomInt(6_500, 14_000)),
        "Groceries",
        addDays(yearAgo, groceryDay),
      ),
    );

    const diningDay1 = week * 7 + randomInt(0, 6);
    events.push(
      spend(diningExpense.id, alexChecking.id, BigInt(randomInt(800, 3_500)), pick(diningSpots), addDays(yearAgo, diningDay1)),
    );
    if (random() < 0.5) {
      const diningDay2 = week * 7 + randomInt(0, 6);
      events.push(
        spend(diningExpense.id, alexChecking.id, BigInt(randomInt(1_500, 7_000)), pick(diningSpots), addDays(yearAgo, diningDay2)),
      );
    }
  }

  // Monthly rent, bank fee, subscription, interest, a sweep to savings,
  // and a rent split with Jordan - twelve occurrences each, spaced by
  // real calendar months so it never drifts against the day-of-month.
  for (let m = 1; m <= 12; m++) {
    const monthDate = addMonths(yearAgo, m);
    events.push(spend(rentExpense.id, alexChecking.id, 145_000n, "Rent payment", monthDate));
    events.push(spend(feesExpense.id, alexChecking.id, 250n, "Monthly maintenance fee", monthDate));
    events.push(spend(subscriptionsExpense.id, alexChecking.id, 1_599n, "Streaming subscription", monthDate));
    events.push(
      earn(interestRevenue.id, alexSavings.id, BigInt(randomInt(2_500, 4_500)), "Interest earned", monthDate),
    );
    events.push(
      transfer(alexChecking.id, alexSavings.id, BigInt(randomInt(150_000, 210_000)), "Savings transfer", monthDate),
    );
    events.push(
      transfer(alexChecking.id, jordanChecking.id, BigInt(randomInt(11_500, 13_500)), "Rent split - Alex to Jordan", monthDate),
    );
  }

  // A handful of large one-off purchases through the year, well above
  // the $1,000 "large transaction" threshold the dashboard flags. Posted
  // as a direct debit against Checking (via a dedicated shopping expense
  // account) rather than through any category used above, since these
  // are one-off purchases, not a recurring spending category.
  const shoppingExpense = await createAccount({ name: "Expenses - Shopping", type: "EXPENSE" });
  const bigPurchaseWeeks = [8, 20, 33, 45];
  bigPurchaseWeeks.forEach((week, i) => {
    events.push(
      spend(
        shoppingExpense.id,
        alexChecking.id,
        BigInt(randomInt(110_000, 250_000)),
        bigPurchases[i]!,
        addDays(yearAgo, week * 7 + randomInt(0, 6)),
      ),
    );
  });

  events.sort((a, b) => a.date.getTime() - b.date.getTime());
  console.log(`Posting ${events.length} historical transactions in chronological order...`);
  for (const event of events) {
    await postHistorical(event);
  }

  console.log("Posting a couple of transactions right now...");
  await postTransaction({
    description: "Coffee shop",
    entries: [
      { accountId: diningExpense.id, direction: "DEBIT", amountMinor: 875n },
      { accountId: alexChecking.id, direction: "CREDIT", amountMinor: 875n },
    ],
  });
  const interestTx = await postTransaction({
    description: "Interest earned",
    entries: [
      { accountId: alexSavings.id, direction: "DEBIT", amountMinor: 1_234n },
      { accountId: interestRevenue.id, direction: "CREDIT", amountMinor: 1_234n },
    ],
  });

  console.log("Reversing that last one to demonstrate append-only correction...");
  await reverseTransaction(interestTx.transaction.id, "Correcting duplicate interest posting");

  console.log("Scheduling a recurring transfer...");
  // Starts due immediately, so the scheduler picks it up and posts the
  // first occurrence within one sweep of the server starting up, which is
  // the easiest way to actually watch the background job do something.
  await createRecurringTransfer({
    description: "Automatic savings sweep",
    fromAccountId: alexChecking.id,
    toAccountId: alexSavings.id,
    amountMinor: 5_000n,
    interval: "WEEKLY",
  });

  console.log("Seed complete.");
}

main()
  .catch((err) => {
    console.error(err);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
