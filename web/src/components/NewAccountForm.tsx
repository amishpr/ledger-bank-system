import { useState } from "react";
import { Plus } from "@phosphor-icons/react";
import { api, ApiRequestError } from "../api/client";
import type { Account } from "../api/types";
import { ACCOUNT_TYPES, TYPE_LABEL } from "../labels";

export function NewAccountForm({
  onCreated,
  onToast,
}: {
  onCreated: () => void;
  onToast: (message: string, kind?: "success" | "error") => void;
}) {
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
      onToast(`Created account "${name.trim()}"`);
      setName("");
      onCreated();
    } catch (err) {
      const message = err instanceof ApiRequestError ? err.message : "Could not create account.";
      setError(message);
      onToast(message, "error");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="new-account" onSubmit={handleSubmit}>
      <label className="field-label" htmlFor="new-account-name">
        New account
      </label>
      <div className="new-account-row">
        <input
          id="new-account-name"
          placeholder="Name"
          autoComplete="off"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={120}
        />
        <select aria-label="Account type" value={type} onChange={(e) => setType(e.target.value as Account["type"])}>
          {ACCOUNT_TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={submitting || !name.trim()}
          aria-label="Add account"
          title="Add account"
        >
          <Plus size={16} weight="bold" aria-hidden />
        </button>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
