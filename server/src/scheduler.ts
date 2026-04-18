import { runDueRecurringTransfers } from "./ledger/recurringService.js";

const SWEEP_INTERVAL_MS = Number(process.env.SCHEDULER_INTERVAL_MS ?? 15_000);

// A deliberately simple background job: a single in-process interval that
// sweeps for due recurring transfers. There's no separate worker process
// or queue (BullMQ/Redis would be the real answer at production scale),
// but the properties that matter are still true here: the sweep is safe to
// run concurrently with itself since posting is idempotent per occurrence,
// and it is safe to restart the whole process at any point since nothing
// about "what's due" lives anywhere but the database.
export function startScheduler() {
  const timer = setInterval(() => {
    runDueRecurringTransfers().catch((err) => {
      console.error("Recurring transfer sweep failed:", err);
    });
  }, SWEEP_INTERVAL_MS);

  // Don't let this timer alone keep the process alive (matters for tests
  // and for clean shutdowns).
  timer.unref();
  return timer;
}
