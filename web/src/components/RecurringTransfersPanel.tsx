import { useState } from "react";
import { Pause, Play, X } from "@phosphor-icons/react";
import { api, ApiRequestError } from "../api/client";
import type { RecurringTransfer } from "../api/types";
import { formatRelativeTime } from "../formatRelativeTime";
import { INTERVAL_LABEL } from "../labels";
import { formatMoney } from "../money";

function nextRunLabel(item: RecurringTransfer): string {
  if (!item.active) return "Paused";
  const next = new Date(item.nextRunAt);
  return next.getTime() <= Date.now() ? "Due now" : `Next run ${formatRelativeTime(next)}`;
}

// New schedules are created from the Move money form (any Repeat option
// other than "Just once"), so this panel is only the list.
export function RecurringTransfersPanel({
  recurringTransfers,
  onChanged,
  onToast,
}: {
  recurringTransfers: RecurringTransfer[];
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

  const activeCount = recurringTransfers.filter((t) => t.active).length;

  return (
    <section className="panel sched" aria-labelledby="sched-title">
      <div className="panel-head">
        <h2 id="sched-title">Scheduled transfers</h2>
        {recurringTransfers.length > 0 && <span className="panel-meta">{activeCount} active</span>}
      </div>
      <p className="panel-note">A background job posts these on schedule, the same way a bank runs standing orders.</p>

      {recurringTransfers.length === 0 ? (
        <div className="empty">
          <strong>Nothing scheduled</strong>
          Pick a Repeat option in Move money to set one up.
        </div>
      ) : (
        <ul className="sched-list">
          {recurringTransfers.map((item) => (
            <li key={item.id} className={`sched-row${item.active ? "" : " is-paused"}`}>
              <div className="sched-main">
                <span className="sched-desc" title={item.description}>
                  {item.description}
                </span>
                <span className="sched-route">
                  {item.fromAccount.name} {"→"} {item.toAccount.name}
                </span>
              </div>
              <div className="sched-when">
                {INTERVAL_LABEL[item.interval]}
                <span className="sched-next">{nextRunLabel(item)}</span>
                {item.lastRunStatus === "FAILED" && (
                  <span className="sched-next sched-failed" title={item.lastRunError ?? undefined}>
                    Last run failed
                  </span>
                )}
              </div>
              <span className="sched-amount num">{formatMoney(item.amountMinor)}</span>
              <div className="sched-actions">
                <button
                  type="button"
                  className="btn btn-icon btn-ghost"
                  disabled={busyId === item.id}
                  onClick={() => handleToggle(item)}
                  aria-label={`${item.active ? "Pause" : "Resume"} "${item.description}"`}
                  title={item.active ? "Pause" : "Resume"}
                >
                  {item.active ? <Pause size={16} aria-hidden /> : <Play size={16} aria-hidden />}
                </button>
                <button
                  type="button"
                  className="btn btn-icon btn-ghost btn-danger"
                  disabled={busyId === item.id}
                  onClick={() => handleDelete(item)}
                  aria-label={`Cancel "${item.description}"`}
                  title="Cancel schedule"
                >
                  <X size={16} aria-hidden />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
