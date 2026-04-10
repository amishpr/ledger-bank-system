import type { Server as HttpServer } from "node:http";
import { WebSocketServer, WebSocket } from "ws";

let wss: WebSocketServer | undefined;

export function initWebSocketServer(httpServer: HttpServer) {
  wss = new WebSocketServer({ server: httpServer, path: "/ws" });
  wss.on("connection", (socket) => {
    socket.send(JSON.stringify({ type: "connected" }));
  });
  return wss;
}

// Fire-and-forget broadcast to every connected dashboard. If nobody's
// listening this is a no-op; the ledger itself never depends on it, since
// the source of truth is always the database, not the socket.
export function broadcast(event: unknown) {
  if (!wss) return;
  const payload = JSON.stringify(event, (_key, value) => (typeof value === "bigint" ? value.toString() : value));
  for (const client of wss.clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }
}
