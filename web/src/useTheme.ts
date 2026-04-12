import { useEffect, useState } from "react";

type Theme = "light" | "dark";
const STORAGE_KEY = "ledger-theme";

function readStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

// Light is always the default and the app never follows the OS color
// scheme automatically. Dark mode only ever turns on when picked here.
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readStoredTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // ignore - private browsing / blocked storage
    }
  }, [theme]);

  return { theme, toggleTheme: () => setTheme((t) => (t === "light" ? "dark" : "light")) };
}
