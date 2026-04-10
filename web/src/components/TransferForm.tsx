import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account } from "../api/types";
import { parseDollarsToCents } from "../money";

export function TransferForm({ accounts, onPosted }: { accounts: Account[]; onPosted: () => void }) {
  const spendable = accounts.filter((a) => a.type === "ASSET");
  const [fromId, setFromId] = useState("");
  const [toId, setToId] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = spendable.find((a) => a.id === fromId) ?? spendable[0];
  const to = spendable.find((a) => a.id === toId) ?? spendable[1];

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!from || !to) {
      setError("Pick both a from and to account.");
      return;
    }
    if (from.id === to.id) {
      setError("From and to accounts must be different.");
      return;
    }

    let amountMinor: bigint;
    try {
      amountMinor = parseDollarsToCents(amount);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid amount");
      return;
    }
    if (amountMinor <= 0n) {
      setError("Amount must be greater than zero.");
      return;
    }

    setSubmitting(true);
    try {
      await api.postTransaction({
        description: description.trim() || `Transfer: ${from.name} → ${to.name}`,
        idempotencyKey: crypto.randomUUID(),
        entries: [
          { accountId: from.id, direction: "CREDIT", amountMinor: amountMinor.toString() },
          { accountId: to.id, direction: "DEBIT", amountMinor: amountMinor.toString() },
        ],
      });
      setAmount("");
      setDescription("");
      onPosted();
    } catch (err) {
      if (err instanceof ApiRequestError && err.code === "INSUFFICIENT_FUNDS") {
        setError(`${from.name} doesn't have enough balance to cover that transfer.`);
      } else if (err instanceof ApiRequestError) {
        setError(err.message);
      } else {
        setError("Transfer failed. Is the server running?");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="panel transfer-form" onSubmit={handleSubmit}>
      <h2>New transfer</h2>
      <div className="form-row">
        <label>
          From
          <select value={from?.id ?? ""} onChange={(e) => setFromId(e.target.value)}>
            {spendable.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          To
          <select value={to?.id ?? ""} onChange={(e) => setToId(e.target.value)}>
            {spendable.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="form-row">
        <label>
          Amount (USD)
          <input
            inputMode="decimal"
            placeholder="0.00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <label className="grow">
          Description
          <input
            placeholder="Optional"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={280}
          />
        </label>
      </div>
      {error && <div className="form-error">{error}</div>}
      <button type="submit" disabled={submitting || spendable.length < 2}>
        {submitting ? "Posting…" : "Post transfer"}
      </button>
      {spendable.length < 2 && <p className="hint">Need at least two asset accounts to transfer between.</p>}
    </form>
  );
}
