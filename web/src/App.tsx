import { useCallback, useEffect, useState } from "react";
import "./App.css";
import { api } from "./api/client";
import type { Account, LedgerEvent, RecurringTransfer, SpendingBreakdown, StatementLine } from "./api/types";
import { AboutPanel } from "./components/AboutPanel";
import { AccountsPanel } from "./components/AccountsPanel";
import { ActivityFeed } from "./components/ActivityFeed";
import { BalanceHistoryChart } from "./components/BalanceHistoryChart";
import { DemoBanner } from "./components/DemoBanner";
import { NewAccountForm } from "./components/NewAccountForm";
import { RecurringTransfersPanel } from "./components/RecurringTransfersPanel";
import { RepoLink } from "./components/RepoLink";
import { SpendingChart } from "./components/SpendingChart";
import { StatementPanel } from "./components/StatementPanel";
import { StatsBar } from "./components/StatsBar";
import { ThemeToggle } from "./components/ThemeToggle";
import { ToastStack } from "./components/ToastStack";
import { TransferForm } from "./components/TransferForm";
import { entryIncreasesBalance } from "./ledgerMath";
import { useLedgerSocket } from "./useLedgerSocket";
import { useTheme } from "./useTheme";
import { useToasts } from "./useToasts";

const MAX_FEED_EVENTS = 20;
const PULSE_DURATION_MS = 1000;
const STATEMENT_LIMIT = 40;
// The balance chart intentionally gets a much larger window than the
// statement table - a year of seeded activity is the whole point, and a
// 400-row table would be unusable, but 400 points on a line chart reads
// fine. See BalanceHistoryChart's footnote: the CSV export, not the
// table, is what guarantees every charted value is reachable elsewhere.
const CHART_LIMIT = 400;
const EMPTY_BREAKDOWN: SpendingBreakdown = { byCategory: [], byMonth: [] };

type PulseDirection = "up" | "down";

// Figures out, for one account touched by this event, whether the entry
// against it increased or decreased that account's own balance - the same
// type-aware rule the statement table uses, not just "debit is up."
function directionForAccount(event: LedgerEvent, accountId: string, accounts: Account[]): PulseDirection | null {
  if (event.type !== "transaction.posted" && event.type !== "transaction.reversed" && event.type !== "recurring.executed") {
    return null;
  }
  const entry = event.transaction.entries.find((e) => e.accountId === accountId);
  const account = accounts.find((a) => a.id === accountId);
  if (!entry || !account) return null;
  return entryIncreasesBalance(account.type, entry.direction) ? "up" : "down";
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [statement, setStatement] = useState<StatementLine[]>([]);
  const [chartHistory, setChartHistory] = useState<StatementLine[]>([]);
  const [events, setEvents] = useState<LedgerEvent[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pulsingAccounts, setPulsingAccounts] = useState<Map<string, PulseDirection>>(new Map());
  const [recurringTransfers, setRecurringTransfers] = useState<RecurringTransfer[]>([]);
  const [spendingBreakdown, setSpendingBreakdown] = useState<SpendingBreakdown>(EMPTY_BREAKDOWN);

  const refreshAccounts = useCallback(async () => {
    try {
      const list = await api.listAccounts();
      setAccounts(list);
      setLoadError(null);
      setSelectedAccountId((current) => current ?? list.find((a) => a.type === "ASSET")?.id ?? list[0]?.id ?? null);
    } catch {
      setLoadError("Can't reach the ledger API. Is the server running on :4000?");
    }
  }, []);

  const refreshStatement = useCallback(async (accountId: string) => {
    const [lines, history] = await Promise.all([
      api.getStatement(accountId, STATEMENT_LIMIT),
      api.getStatement(accountId, CHART_LIMIT),
    ]);
    setStatement(lines);
    setChartHistory(history);
  }, []);

  const refreshRecurring = useCallback(async () => {
    setRecurringTransfers(await api.listRecurringTransfers());
  }, []);

  const refreshSpending = useCallback(async () => {
    setSpendingBreakdown(await api.getSpendingBreakdown());
  }, []);

  useEffect(() => {
    refreshAccounts();
    refreshRecurring();
    refreshSpending();
  }, [refreshAccounts, refreshRecurring, refreshSpending]);

  useEffect(() => {
    if (selectedAccountId) refreshStatement(selectedAccountId);
  }, [selectedAccountId, refreshStatement]);

  const { toasts, push: pushToast, dismiss: dismissToast } = useToasts();

  const handleLedgerEvent = useCallback(
    (event: LedgerEvent) => {
      if (event.type === "connected") return;

      if (event.type === "recurring.failed") {
        pushToast(`A recurring transfer failed: ${event.error}`, "error");
        refreshRecurring();
        return;
      }

      setEvents((prev) => [event, ...prev].slice(0, MAX_FEED_EVENTS));
      refreshAccounts();
      refreshSpending();
      if (selectedAccountId && event.affectedAccountIds.includes(selectedAccountId)) {
        refreshStatement(selectedAccountId);
      }
      setPulsingAccounts((prev) => {
        const next = new Map(prev);
        for (const id of event.affectedAccountIds) {
          const direction = directionForAccount(event, id, accounts);
          if (direction) next.set(id, direction);
        }
        return next;
      });
      setTimeout(() => {
        setPulsingAccounts((prev) => {
          const next = new Map(prev);
          for (const id of event.affectedAccountIds) next.delete(id);
          return next;
        });
      }, PULSE_DURATION_MS);

      if (event.type === "recurring.executed") {
        pushToast(`Recurring transfer ran: "${event.transaction.description}"`);
        refreshRecurring();
      }
    },
    [accounts, refreshAccounts, refreshRecurring, refreshSpending, refreshStatement, selectedAccountId, pushToast],
  );

  const wsStatus = useLedgerSocket(handleLedgerEvent);
  const { theme, toggleTheme } = useTheme();

  function handlePosted() {
    refreshAccounts();
    refreshSpending();
    if (selectedAccountId) refreshStatement(selectedAccountId);
  }

  const selectedAccount = accounts.find((a) => a.id === selectedAccountId);

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          {/* The same file the browser tab uses. BASE_URL rather than a
              literal "/" because GitHub Pages serves the app from a
              subdirectory, where a root-relative path would 404. The alt
              is empty on purpose: the <h1> beside it already says Ledger,
              so naming the logo too would just repeat it. */}
          <img className="brand-mark" src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" width={32} height={32} />
          <div>
            <h1>Ledger</h1>
            <p className="tagline">A double-entry core-banking ledger with an append-only, idempotent transaction API.</p>
          </div>
        </div>
        <div className="header-actions">
          <div className={`ws-status ws-${wsStatus}`}>
            <span className="ws-dot" />
            {wsStatus === "open" ? "Live" : wsStatus === "connecting" ? "Connecting…" : "Disconnected"}
          </div>
          <RepoLink />
          <ThemeToggle theme={theme} onToggle={toggleTheme} />
        </div>
      </header>

      <AboutPanel />

      <DemoBanner />

      {loadError && <div className="banner-error">{loadError}</div>}

      {accounts.length > 0 && <StatsBar accounts={accounts} />}

      <main className="app-grid">
        <div className="col">
          <AccountsPanel
            accounts={accounts}
            selectedAccountId={selectedAccountId}
            pulsingAccounts={pulsingAccounts}
            onSelect={setSelectedAccountId}
          />
          <NewAccountForm onCreated={refreshAccounts} onToast={pushToast} />
          <TransferForm accounts={accounts} onPosted={handlePosted} onToast={pushToast} />
          <RecurringTransfersPanel
            recurringTransfers={recurringTransfers}
            accounts={accounts}
            onChanged={refreshRecurring}
            onToast={pushToast}
          />
        </div>
        <div className="col wide">
          <StatementPanel account={selectedAccount} lines={statement} onChanged={handlePosted} onToast={pushToast} />
          <BalanceHistoryChart account={selectedAccount} lines={chartHistory} />
          <SpendingChart breakdown={spendingBreakdown} />
          <ActivityFeed events={events} />
        </div>
      </main>

      <ToastStack toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
}

export default App;
