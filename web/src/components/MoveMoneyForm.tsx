import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account, RecurrenceInterval } from "../api/types";
import { isLargeAmount } from "../flags";
import { INTERVAL_LABEL } from "../labels";
import { formatMoney, parseDollarsToCents } from "../money";

type Repeat = "ONCE" | RecurrenceInterval;

const REPEAT_OPTIONS: { value: Repeat; label: string }[] = [
  { value: "ONCE", label: "Just once" },
  { value: "EVERY_MINUTE", label: INTERVAL_LABEL.EVERY_MINUTE },
  { value: "DAILY", label: INTERVAL_LABEL.DAILY },
  { value: "WEEKLY", label: INTERVAL_LABEL.WEEKLY },
  { value: "MONTHLY", label: INTERVAL_LABEL.MONTHLY },
];

// One form for both kinds of transfer. "Just once" posts a transaction
// right away; any other Repeat choice creates a recurring transfer that
// the scheduler posts later. The fields are the same either way, which is
// why this used to be two near-identical forms.
export function MoveMoneyForm({
  accounts,
  onPosted,
  onScheduled,
  onToast,
}: {
  accounts: Account[];
  onPosted: () => void;
  onScheduled: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
  const spendable = accounts.filter((a) => a.type === "ASSET");
  const [fromId, setFromId] = useState("");
  const [toId, setToId] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [repeat, setRepeat] = useState<Repeat>("ONCE");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = spendable.find((a) => a.id === fromId) ?? spendable[0];
  const to = spendable.find((a) => a.id === toId) ?? spendable[1];
  const scheduling = repeat !== "ONCE";

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
      if (repeat === "ONCE") {
        await api.postTransaction({
          description: description.trim() || `Transfer: ${from.name} → ${to.name}`,
          idempotencyKey: crypto.randomUUID(),
          entries: [
            { accountId: from.id, direction: "CREDIT", amountMinor: amountMinor.toString() },
            { accountId: to.id, direction: "DEBIT", amountMinor: amountMinor.toString() },
          ],
        });
        onToast(`Sent ${formatMoney(amountMinor)} from ${from.name} to ${to.name}`);
        onPosted();
      } else {
        await api.createRecurringTransfer({
          description: description.trim() || `${INTERVAL_LABEL[repeat]} transfer: ${from.name} → ${to.name}`,
          fromAccountId: from.id,
          toAccountId: to.id,
          amountMinor: amountMinor.toString(),
          interval: repeat,
        });
        onToast(`Scheduled ${formatMoney(amountMinor)} from ${from.name} to ${to.name}, ${INTERVAL_LABEL[repeat].toLowerCase()}`);
        onScheduled();
      }
      setAmount("");
      setDescription("");
    } catch (err) {
      let message: string;
      if (err instanceof ApiRequestError && err.code === "INSUFFICIENT_FUNDS") {
        message = `${from.name} doesn't have enough balance to cover that transfer.`;
      } else if (err instanceof ApiRequestError) {
        message = err.message;
      } else {
        message = scheduling ? "Could not schedule the transfer." : "Transfer failed. Is the server running?";
      }
      setError(message);
      onToast(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  const accountOptions = spendable.map((a) => (
    <option key={a.id} value={a.id}>
      {a.name}
    </option>
  ));

  return (
    <form className="panel move" onSubmit={handleSubmit} aria-labelledby="move-title" noValidate>
      <div className="panel-head">
        <h2 id="move-title">Move money</h2>
      </div>
      <div className="move-fields">
        <label className="field field-wide">
          <span className="field-label">From</span>
          <select value={from?.id ?? ""} onChange={(e) => setFromId(e.target.value)}>
            {accountOptions}
          </select>
        </label>
        <label className="field field-wide">
          <span className="field-label">To</span>
          <select value={to?.id ?? ""} onChange={(e) => setToId(e.target.value)}>
            {accountOptions}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Amount</span>
          <span className="input-affix">
            <span aria-hidden>$</span>
            <input
              inputMode="decimal"
              placeholder="0.00"
              autoComplete="off"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </span>
        </label>
        <label className="field">
          <span className="field-label">Repeat</span>
          <select value={repeat} onChange={(e) => setRepeat(e.target.value as Repeat)}>
            {REPEAT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field field-wide">
          <span className="field-label">Description</span>
          <input
            placeholder="Optional"
            autoComplete="off"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={280}
          />
        </label>
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {!error && previewCents !== null && isLargeAmount(previewCents) && (
        <p className="form-notice">This is a large transfer, so it will be flagged in the activity feed.</p>
      )}
      {repeat === "EVERY_MINUTE" && (
        <p className="form-hint">Every minute is only here so you can watch the scheduler run.</p>
      )}

      <button type="submit" className="btn btn-primary" disabled={submitting || spendable.length < 2}>
        {submitting ? (scheduling ? "Scheduling…" : "Posting…") : scheduling ? "Schedule transfer" : "Post transfer"}
      </button>
      {spendable.length < 2 && <p className="hint">Add a second asset account to move money between them.</p>}
    </form>
  );
}
