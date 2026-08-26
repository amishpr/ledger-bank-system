// Short dates for dense tables: "Sep 25, 2:16 PM" within the current
// year, "Sep 25, 2025" for anything older, so a year of history is never
// ambiguous about which September it means.
export function formatShortDateTime(date: Date, now = new Date()): string {
  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}
