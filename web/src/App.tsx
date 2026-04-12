import { useCallback, useEffect, useState } from "react";
import "./App.css";
import { api } from "./api/client";
import type { Account, LedgerEvent, StatementLine } from "./api/types";
import { AccountsPanel } from "./components/AccountsPanel";
import { ActivityFeed } from "./components/ActivityFeed";
import { NewAccountForm } from "./components/NewAccountForm";
import { StatementPanel } from "./components/StatementPanel";
import { StatsBar } from "./components/StatsBar";
import { ThemeToggle } from "./components/ThemeToggle";
import { ToastStack } from "./components/ToastStack";
import { TransferForm } from "./components/TransferForm";
import { useLedgerSocket } from "./useLedgerSocket";
import { useTheme } from "./useTheme";
import { useToasts } from "./useToasts";

const MAX_FEED_EVENTS = 20;
const PULSE_DURATION_MS = 900;

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [statement, setStatement] = useState<StatementLine[]>([]);
  const [events, setEvents] = useState<LedgerEvent[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pulsingAccountIds, setPulsingAccountIds] = useState<Set<string>>(new Set());

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
    const lines = await api.getStatement(accountId, 25);
    setStatement(lines);
  }, []);

  useEffect(() => {
    refreshAccounts();
  }, [refreshAccounts]);

  useEffect(() => {
    if (selectedAccountId) refreshStatement(selectedAccountId);
  }, [selectedAccountId, refreshStatement]);

  const handleLedgerEvent = useCallback(
    (event: LedgerEvent) => {
      if (event.type === "connected") return;
      setEvents((prev) => [event, ...prev].slice(0, MAX_FEED_EVENTS));
      refreshAccounts();
      if (selectedAccountId && event.affectedAccountIds.includes(selectedAccountId)) {
        refreshStatement(selectedAccountId);
      }
      setPulsingAccountIds((prev) => new Set([...prev, ...event.affectedAccountIds]));
      setTimeout(() => {
        setPulsingAccountIds((prev) => {
          const next = new Set(prev);
          for (const id of event.affectedAccountIds) next.delete(id);
          return next;
        });
      }, PULSE_DURATION_MS);
    },
    [refreshAccounts, refreshStatement, selectedAccountId],
  );

  const wsStatus = useLedgerSocket(handleLedgerEvent);
  const { theme, toggleTheme } = useTheme();
  const { toasts, push: pushToast, dismiss: dismissToast } = useToasts();

  function handlePosted() {
    refreshAccounts();
    if (selectedAccountId) refreshStatement(selectedAccountId);
  }

  const selectedAccount = accounts.find((a) => a.id === selectedAccountId);

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark">L</span>
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
          <ThemeToggle theme={theme} onToggle={toggleTheme} />
        </div>
      </header>

      {loadError && <div className="banner-error">{loadError}</div>}

      {accounts.length > 0 && <StatsBar accounts={accounts} />}

      <main className="app-grid">
        <div className="col">
          <AccountsPanel
            accounts={accounts}
            selectedAccountId={selectedAccountId}
            pulsingAccountIds={pulsingAccountIds}
            onSelect={setSelectedAccountId}
          />
          <NewAccountForm onCreated={refreshAccounts} onToast={pushToast} />
          <TransferForm accounts={accounts} onPosted={handlePosted} onToast={pushToast} />
        </div>
        <div className="col wide">
          <StatementPanel account={selectedAccount} lines={statement} onChanged={handlePosted} onToast={pushToast} />
          <ActivityFeed events={events} />
        </div>
      </main>

      <ToastStack toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
}

export default App;
