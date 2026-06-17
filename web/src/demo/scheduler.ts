import { runDueRecurringTransfers } from "./recurring";

// Mirrors server/src/scheduler.ts, at the same fifteen second cadence. On
// the server this interval lives in the Node process; here it lives in the
// tab. Both call the same sweep function, and both rely on the sweep being
// safe to run again rather than on the timer being reliable.
const SWEEP_INTERVAL_MS = 15_000;

export function startDemoScheduler(): void {
  setInterval(() => {
    runDueRecurringTransfers().catch((err: unknown) => {
      console.error("Recurring transfer sweep failed:", err);
    });
  }, SWEEP_INTERVAL_MS);
}
