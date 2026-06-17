import { DEMO_MODE } from "../demo/mode";

export function DemoBanner() {
  if (!DEMO_MODE) return null;

  return (
    <div className="banner-demo">
      <p>
        <strong>Demo mode.</strong> The API, the database, and the recurring transfer scheduler are all running
        inside this browser tab, so this page can be hosted as a static site. Nothing is sent anywhere, and every
        change is discarded on reload. The same dashboard runs against the real Node and Express API, Prisma, and
        WebSocket server when the project is cloned and started locally.
      </p>
      <button className="link-button" onClick={() => window.location.reload()} title="Reload and reseed the demo data">
        Reset data
      </button>
    </div>
  );
}
