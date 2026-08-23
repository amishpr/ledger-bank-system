import { ArrowCounterClockwise } from "@phosphor-icons/react";
import { DEMO_MODE } from "../demo/mode";

export function DemoBanner() {
  if (!DEMO_MODE) return null;

  return (
    <aside className="demo-note" aria-label="Demo mode">
      <strong className="demo-note-title">Demo mode</strong>
      <p>
        The API, the database and the recurring transfer scheduler all run inside this tab. Nothing is sent anywhere,
        and every change is gone on reload. Cloned and started locally, the same dashboard runs against the real
        Express API, Prisma and WebSocket server.
      </p>
      <button type="button" className="btn" onClick={() => window.location.reload()} title="Reload and reseed the demo data">
        <ArrowCounterClockwise size={16} aria-hidden />
        Reset demo data
      </button>
    </aside>
  );
}
