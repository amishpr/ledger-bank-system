import { useEffect, useState } from "react";

type Theme = "light" | "dark";
const STORAGE_KEY = "ledger-theme";

function readStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

// The dark OMS-terminal look is the default the app opens with, matching
// the trading-desk software this dashboard is modeled after. Light stays
// available through the toggle for anyone who wants it, but it's an
// explicit choice, never the OS preference.
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
