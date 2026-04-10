import { useState } from "react";
import { api, ApiRequestError } from "../api/client";
import type { Account } from "../api/types";

const TYPES: Account["type"][] = ["ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"];

export function NewAccountForm({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState<Account["type"]>("ASSET");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setError(null);
    setSubmitting(true);
    try {
      await api.createAccount({ name: name.trim(), type });
      setName("");
      onCreated();
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Could not create account.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="new-account-form" onSubmit={handleSubmit}>
      <input placeholder="New account name" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} />
      <select value={type} onChange={(e) => setType(e.target.value as Account["type"])}>
        {TYPES.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
      <button type="submit" disabled={submitting || !name.trim()}>
        Add
      </button>
      {error && <div className="form-error">{error}</div>}
    </form>
  );
}
