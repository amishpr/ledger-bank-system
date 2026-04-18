const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 31_536_000],
  ["month", 2_592_000],
  ["week", 604_800],
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
  ["second", 1],
];

const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

export function formatRelativeTime(target: Date, now = new Date()): string {
  const diffSeconds = (target.getTime() - now.getTime()) / 1000;
  const abs = Math.abs(diffSeconds);
  for (const [unit, secondsInUnit] of UNITS) {
    if (abs >= secondsInUnit || unit === "second") {
      const value = Math.round(diffSeconds / secondsInUnit);
      return rtf.format(value, unit);
    }
  }
  return rtf.format(0, "second");
}
