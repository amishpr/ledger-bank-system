import type { EntryDirection } from "../api/types";
import { createAccount, postTransaction, reverseTransaction } from "./ledger";
import { createRecurringTransfer } from "./recurring";
import { db, resetDb } from "./store";

// A port of server/prisma/seed.ts. Because both use the same mulberry32
// generator with the same seed and make the same sequence of calls, the
// hosted demo shows the same year of activity that `npm run seed` builds
// locally, rather than a separate set of made-up numbers.
function mulberry32(seed: number) {
  return function random() {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface SeedEvent {
  date: Date;
  description: string;
  entries: { accountId: string; direction: EntryDirection; amountMinor: bigint }[];
}

// Posts through the normal, invariant-checked path and then backdates the
// row afterward. postTransaction itself never accepts a caller-supplied
// timestamp, which is what keeps a client from being able to lie about
// when something happened. The seed reaches past that deliberately, by
// writing to the store directly, the same way the server's seed script
// reaches past it with Prisma.
async function postHistorical(event: SeedEvent) {
  const result = await postTransaction({ description: event.description, entries: event.entries });
  const transaction = db.transactions.find((t) => t.id === result.transaction.id)!;
  transaction.createdAt = event.date;
  for (const entry of db.entries) {
    if (entry.transactionId === transaction.id) entry.createdAt = event.date;
  }
  return result;
}

export async function seedDemoDb(): Promise<void> {
  resetDb();

  const random = mulberry32(1337);
  const randomInt = (min: number, max: number) => Math.floor(random() * (max - min + 1)) + min;
  const pick = <T,>(items: T[]): T => items[randomInt(0, items.length - 1)]!;

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

  const equity = createAccount({ name: "Opening Balance Equity", type: "EQUITY" });
  const alexChecking = createAccount({ name: "Checking - Alex", type: "ASSET" });
  const alexSavings = createAccount({ name: "Savings - Alex", type: "ASSET" });
  const jordanChecking = createAccount({ name: "Checking - Jordan", type: "ASSET" });
  const paycheckRevenue = createAccount({ name: "Revenue - Paycheck", type: "REVENUE" });
  const interestRevenue = createAccount({ name: "Revenue - Interest", type: "REVENUE" });
  const feesExpense = createAccount({ name: "Expenses - Bank Fees", type: "EXPENSE" });
  const diningExpense = createAccount({ name: "Expenses - Dining", type: "EXPENSE" });
  const subscriptionsExpense = createAccount({ name: "Expenses - Subscriptions", type: "EXPENSE" });
  const rentExpense = createAccount({ name: "Expenses - Rent", type: "EXPENSE" });
  const groceriesExpense = createAccount({ name: "Expenses - Groceries", type: "EXPENSE" });

  const today = new Date();
  const yearAgo = addDays(today, -365);

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

  const events: SeedEvent[] = [];
  const diningSpots = ["Coffee shop", "Lunch out", "Dinner with friends", "Takeout", "Brunch", "Pizza night"];
  const bigPurchases = ["Flight tickets", "New laptop", "Furniture", "Holiday shopping"];

  // Biweekly paycheck, starting a few days in.
  for (let day = 3; day < 365; day += 14) {
    events.push(earn(paycheckRevenue.id, alexChecking.id, 240_000n, "Paycheck deposit", addDays(yearAgo, day)));
  }

  // Weekly groceries, plus dining out once a week and sometimes twice.
  for (let week = 0; week < 52; week++) {
    const groceryDay = week * 7 + randomInt(0, 6);
    events.push(
      spend(groceriesExpense.id, alexChecking.id, BigInt(randomInt(6_500, 14_000)), "Groceries", addDays(yearAgo, groceryDay)),
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
  // and a rent split with Jordan, spaced by real calendar months so they
  // never drift against the day of the month.
  for (let m = 1; m <= 12; m++) {
    const monthDate = addMonths(yearAgo, m);
    events.push(spend(rentExpense.id, alexChecking.id, 145_000n, "Rent payment", monthDate));
    events.push(spend(feesExpense.id, alexChecking.id, 250n, "Monthly maintenance fee", monthDate));
    events.push(spend(subscriptionsExpense.id, alexChecking.id, 1_599n, "Streaming subscription", monthDate));
    events.push(earn(interestRevenue.id, alexSavings.id, BigInt(randomInt(2_500, 4_500)), "Interest earned", monthDate));
    events.push(transfer(alexChecking.id, alexSavings.id, BigInt(randomInt(150_000, 210_000)), "Savings transfer", monthDate));
    events.push(
      transfer(alexChecking.id, jordanChecking.id, BigInt(randomInt(11_500, 13_500)), "Rent split - Alex to Jordan", monthDate),
    );
  }

  // A handful of large one-off purchases through the year, well above the
  // $1,000 threshold the dashboard flags as large. They get their own
  // expense account because they are one-off purchases, not a recurring
  // spending category.
  const shoppingExpense = createAccount({ name: "Expenses - Shopping", type: "EXPENSE" });
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
  for (const event of events) {
    await postHistorical(event);
  }

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

  // Reversed on purpose, so the dashboard opens with a real example of an
  // append-only correction rather than a perfectly clean history.
  await reverseTransaction(interestTx.transaction.id, "Correcting duplicate interest posting");

  // Starts due immediately, so the in-browser scheduler picks it up on its
  // first sweep and the background job visibly does something.
  createRecurringTransfer({
    description: "Automatic savings sweep",
    fromAccountId: alexChecking.id,
    toAccountId: alexSavings.id,
    amountMinor: 5_000n,
    interval: "WEEKLY",
  });
}
