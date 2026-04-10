import { useCallback, useEffect, useState } from "react";
import "./App.css";
import { api } from "./api/client";
import type { Account, LedgerEvent, StatementLine } from "./api/types";
import { AccountsPanel } from "./components/AccountsPanel";
import { ActivityFeed } from "./components/ActivityFeed";
import { NewAccountForm } from "./components/NewAccountForm";
import { StatementPanel } from "./components/StatementPanel";
import { TransferForm } from "./components/TransferForm";
import { useLedgerSocket } from "./useLedgerSocket";

const MAX_FEED_EVENTS = 20;

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [statement, setStatement] = useState<StatementLine[]>([]);
  const [events, setEvents] = useState<LedgerEvent[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

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
    },
    [refreshAccounts, refreshStatement, selectedAccountId],
  );

  const wsStatus = useLedgerSocket(handleLedgerEvent);

  function handlePosted() {
    refreshAccounts();
    if (selectedAccountId) refreshStatement(selectedAccountId);
  }

  const selectedAccount = accounts.find((a) => a.id === selectedAccountId);

  return (
    <div className="app">
      <header className="app-header">
        <div>
          <h1>Ledger</h1>
          <p className="tagline">A double-entry core-banking ledger with an append-only, idempotent transaction API.</p>
        </div>
        <div className={`ws-status ws-${wsStatus}`}>
          <span className="ws-dot" />
          {wsStatus === "open" ? "Live" : wsStatus === "connecting" ? "Connecting…" : "Disconnected"}
        </div>
      </header>

      {loadError && <div className="banner-error">{loadError}</div>}

      <main className="app-grid">
        <div className="col">
          <AccountsPanel accounts={accounts} selectedAccountId={selectedAccountId} onSelect={setSelectedAccountId} />
          <NewAccountForm onCreated={refreshAccounts} />
          <TransferForm accounts={accounts} onPosted={handlePosted} />
        </div>
        <div className="col wide">
          <StatementPanel account={selectedAccount} lines={statement} onChanged={handlePosted} />
          <ActivityFeed events={events} />
        </div>
      </main>
    </div>
  );
}

export default App;
