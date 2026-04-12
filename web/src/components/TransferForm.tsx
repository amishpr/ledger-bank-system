import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account } from "../api/types";
import { isLargeAmount } from "../flags";
import { formatMoney, parseDollarsToCents } from "../money";

export function TransferForm({
  accounts,
  onPosted,
  onToast,
}: {
  accounts: Account[];
  onPosted: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
  const spendable = accounts.filter((a) => a.type === "ASSET");
  const [fromId, setFromId] = useState("");
  const [toId, setToId] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = spendable.find((a) => a.id === fromId) ?? spendable[0];
  const to = spendable.find((a) => a.id === toId) ?? spendable[1];

  let previewCents: bigint | null = null;
  try {
    previewCents = amount.trim() ? parseDollarsToCents(amount) : null;
  } catch {
    previewCents = null;
  }

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
      onToast(`Sent ${formatMoney(amountMinor)} from ${from.name} to ${to.name}`);
      setAmount("");
      setDescription("");
      onPosted();
    } catch (err) {
      let message: string;
      if (err instanceof ApiRequestError && err.code === "INSUFFICIENT_FUNDS") {
        message = `${from.name} doesn't have enough balance to cover that transfer.`;
      } else if (err instanceof ApiRequestError) {
        message = err.message;
      } else {
        message = "Transfer failed. Is the server running?";
      }
      setError(message);
      onToast(message, "error");
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
      {!error && previewCents !== null && isLargeAmount(previewCents) && (
        <div className="form-notice">This is a large transfer and will be flagged for review in the feed.</div>
      )}
      <button type="submit" disabled={submitting || spendable.length < 2}>
        {submitting ? "Posting…" : "Post transfer"}
      </button>
      {spendable.length < 2 && <p className="hint">Need at least two asset accounts to transfer between.</p>}
    </form>
  );
}
