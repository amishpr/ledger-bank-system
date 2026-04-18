import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account, RecurrenceInterval, RecurringTransfer } from "../api/types";
import { formatRelativeTime } from "../formatRelativeTime";
import { formatMoney, parseDollarsToCents } from "../money";

const INTERVAL_LABEL: Record<RecurrenceInterval, string> = {
  EVERY_MINUTE: "Every minute (demo)",
  DAILY: "Daily",
  WEEKLY: "Weekly",
  MONTHLY: "Monthly",
};

const INTERVALS: RecurrenceInterval[] = ["EVERY_MINUTE", "DAILY", "WEEKLY", "MONTHLY"];

function CreateForm({
  accounts,
  onCreated,
  onToast,
}: {
  accounts: Account[];
  onCreated: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
  const spendable = accounts.filter((a) => a.type === "ASSET");
  const [fromId, setFromId] = useState("");
  const [toId, setToId] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [interval, setInterval] = useState<RecurrenceInterval>("WEEKLY");
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
      await api.createRecurringTransfer({
        description: description.trim() || `${INTERVAL_LABEL[interval]} transfer: ${from.name} → ${to.name}`,
        fromAccountId: from.id,
        toAccountId: to.id,
        amountMinor: amountMinor.toString(),
        interval,
      });
      onToast(`Scheduled a transfer of ${formatMoney(amountMinor)}, repeating ${INTERVAL_LABEL[interval].toLowerCase()}`);
      setAmount("");
      setDescription("");
      onCreated();
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Could not schedule transfer.";
      setError(message);
      onToast(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="transfer-form" onSubmit={handleSubmit}>
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
          <input inputMode="decimal" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        <label>
          Repeats
          <select value={interval} onChange={(e) => setInterval(e.target.value as RecurrenceInterval)}>
            {INTERVALS.map((i) => (
              <option key={i} value={i}>
                {INTERVAL_LABEL[i]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="form-row">
        <label className="grow">
          Description
          <input placeholder="Optional" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={280} />
        </label>
      </div>
      {error && <div className="form-error">{error}</div>}
      <button type="submit" disabled={submitting || spendable.length < 2}>
        {submitting ? "Scheduling…" : "Schedule transfer"}
      </button>
      {spendable.length < 2 && <p className="hint">Need at least two asset accounts to schedule a transfer.</p>}
    </form>
  );
}

export function RecurringTransfersPanel({
  recurringTransfers,
  accounts,
  onChanged,
  onToast,
}: {
  recurringTransfers: RecurringTransfer[];
  accounts: Account[];
  onChanged: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
  const [busyId, setBusyId] = useState<string | null>(null);

  async function handleToggle(item: RecurringTransfer) {
    setBusyId(item.id);
    try {
      await api.toggleRecurringTransfer(item.id);
      onToast(item.active ? `Paused "${item.description}"` : `Resumed "${item.description}"`);
      onChanged();
    } catch (err) {
      onToast(err instanceof ApiRequestError ? err.message : "Could not update schedule.", "error");
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(item: RecurringTransfer) {
    setBusyId(item.id);
    try {
      await api.deleteRecurringTransfer(item.id);
      onToast(`Cancelled "${item.description}"`);
      onChanged();
    } catch (err) {
      onToast(err instanceof ApiRequestError ? err.message : "Could not cancel schedule.", "error");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="panel">
      <h2>Recurring transfers</h2>
      <p className="hint recurring-intro">
        Posted automatically by a background job on the server, the same way a real bank's scheduled payments work.
      </p>
      <ul className="recurring-list">
        {recurringTransfers.length === 0 && <li className="hint">No recurring transfers scheduled.</li>}
        {recurringTransfers.map((item) => (
          <li key={item.id} className={`recurring-item${item.active ? "" : " recurring-paused"}`}>
            <div className="recurring-top">
              <span className="recurring-description">{item.description}</span>
              <span className="recurring-amount">{formatMoney(item.amountMinor)}</span>
            </div>
            <div className="recurring-meta">
              <span>
                {item.fromAccount.name} {"→"} {item.toAccount.name}
              </span>
              <span>{INTERVAL_LABEL[item.interval]}</span>
              <span>
                {item.active ? `Next ${formatRelativeTime(new Date(item.nextRunAt))}` : "Paused"}
              </span>
              {item.lastRunStatus && (
                <span className={`recurring-status recurring-status-${item.lastRunStatus.toLowerCase()}`}>
                  Last run {item.lastRunStatus === "SUCCESS" ? "succeeded" : "failed"}
                </span>
              )}
            </div>
            <div className="recurring-actions">
              <button className="link-button" disabled={busyId === item.id} onClick={() => handleToggle(item)}>
                {item.active ? "Pause" : "Resume"}
              </button>
              <button className="link-button link-button-danger" disabled={busyId === item.id} onClick={() => handleDelete(item)}>
                Cancel
              </button>
            </div>
          </li>
        ))}
      </ul>
      <CreateForm accounts={accounts} onCreated={onChanged} onToast={onToast} />
    </div>
  );
}
