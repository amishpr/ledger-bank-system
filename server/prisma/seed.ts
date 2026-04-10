import { prisma } from "../src/db.js";
import { createAccount, postTransaction } from "../src/ledger/ledgerService.js";

async function main() {
  console.log("Resetting database...");
  await prisma.auditLog.deleteMany();
  await prisma.entry.deleteMany();
  await prisma.transaction.deleteMany();
  await prisma.account.deleteMany();

  console.log("Creating accounts...");
  const equity = await createAccount({ name: "Opening Balance Equity", type: "EQUITY" });
  const alexChecking = await createAccount({ name: "Checking - Alex", type: "ASSET" });
  const alexSavings = await createAccount({ name: "Savings - Alex", type: "ASSET" });
  const jordanChecking = await createAccount({ name: "Checking - Jordan", type: "ASSET" });
  const interestRevenue = await createAccount({ name: "Revenue - Interest", type: "REVENUE" });
  const feesExpense = await createAccount({ name: "Expenses - Bank Fees", type: "EXPENSE" });

  console.log("Funding accounts via opening balances...");
  // Opening balances are posted as real double-entry transactions against an
  // equity account, never poked directly into a balance column.
  await postTransaction({
    description: "Opening balance",
    entries: [
      { accountId: alexChecking.id, direction: "DEBIT", amountMinor: 250_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 250_000n },
    ],
  });
  await postTransaction({
    description: "Opening balance",
    entries: [
      { accountId: alexSavings.id, direction: "DEBIT", amountMinor: 1_000_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 1_000_000n },
    ],
  });
  await postTransaction({
    description: "Opening balance",
    entries: [
      { accountId: jordanChecking.id, direction: "DEBIT", amountMinor: 80_000n },
      { accountId: equity.id, direction: "CREDIT", amountMinor: 80_000n },
    ],
  });

  console.log("Posting sample activity...");
  await postTransaction({
    description: "Rent split - Alex to Jordan",
    entries: [
      { accountId: jordanChecking.id, direction: "DEBIT", amountMinor: 12_550n },
      { accountId: alexChecking.id, direction: "CREDIT", amountMinor: 12_550n },
    ],
  });
  await postTransaction({
    description: "Monthly maintenance fee",
    entries: [
      { accountId: feesExpense.id, direction: "DEBIT", amountMinor: 250n },
      { accountId: alexChecking.id, direction: "CREDIT", amountMinor: 250n },
    ],
  });
  const interestTx = await postTransaction({
    description: "Interest earned",
    entries: [
      { accountId: alexSavings.id, direction: "DEBIT", amountMinor: 1_234n },
      { accountId: interestRevenue.id, direction: "CREDIT", amountMinor: 1_234n },
    ],
  });

  console.log("Reversing a transaction to demonstrate append-only correction...");
  const { reverseTransaction } = await import("../src/ledger/ledgerService.js");
  await reverseTransaction(interestTx.transaction.id, "Correcting duplicate interest posting");

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
