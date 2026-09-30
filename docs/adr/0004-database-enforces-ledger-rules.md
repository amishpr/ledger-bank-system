# 0004. Let the database enforce the ledger's rules too

Status: accepted

## Context

The ledger's rules (entries never change, a transaction only ever goes from
POSTED to VOIDED, two postings to one account cannot both pass the overdraft
check) were enforced only by application code in the first version. Code paths
multiply over time: a migration script, an admin tool, a hotfix. Any of them
could quietly break a rule the rest of the system relies on.

## Decision

- Triggers reject any `UPDATE` or `DELETE` on `journal_entry`, and any change to
  `ledger_transaction` other than its status moving from POSTED to VOIDED.
- Check constraints cover amounts (positive), directions, account types and
  currency codes.
- Postings lock the affected account rows with `SELECT ... FOR UPDATE`, always
  in id order so two postings cannot deadlock, before reading balances.
- The idempotency key has a unique index, so two simultaneous requests with the
  same key cannot both insert. The loser reads the winner's result.

## Consequences

- A bug or a careless SQL session cannot rewrite history. Tests prove it by
  attempting exactly that.
- Postings to the same account are serialised. That is the correct behaviour
  for a balance check and is what a real ledger does; heavily shared accounts
  (a bank's own fee account, for example) are the known hotspot, usually solved
  by sharding the account into several sub-accounts.
- The rules exist in two places, code and schema, and have to be kept in step.
  The code gives the friendly error; the database is the backstop.
