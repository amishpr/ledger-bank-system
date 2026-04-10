import type { AccountType, EntryDirection } from "./api/types";

// Mirrors server/src/ledger/types.ts#signedDelta - kept here only for
// display purposes (which way to color/sign an entry), never to compute a
// balance the server hasn't already confirmed.
const DEBIT_NORMAL_TYPES = new Set<AccountType>(["ASSET", "EXPENSE"]);

export function entryIncreasesBalance(accountType: AccountType, direction: EntryDirection): boolean {
  const isDebitNormal = DEBIT_NORMAL_TYPES.has(accountType);
  const isDebit = direction === "DEBIT";
  return isDebit === isDebitNormal;
}
