import { Moon, Sun } from "@phosphor-icons/react";

// Shows the theme you would switch to, and says so in its label, so the
// icon is never mistaken for the current state.
export function ThemeToggle({ theme, onToggle }: { theme: "light" | "dark"; onToggle: () => void }) {
  const next = theme === "light" ? "dark" : "light";
  return (
    <button
      type="button"
      className="btn btn-secondary btn-icon"
      onClick={onToggle}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
    >
      {next === "dark" ? <Moon size={16} aria-hidden /> : <Sun size={16} aria-hidden />}
    </button>
  );
}
