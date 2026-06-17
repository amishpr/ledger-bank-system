import type { LedgerEvent } from "../api/types";
import { toWire } from "./wire";

// Mirrors server/src/realtime/ws.ts. The real server pushes these events
// down a WebSocket to every connected dashboard; here the "connection" is
// a callback in the same tab. The event payloads go through the same
// BigInt-to-string conversion the WebSocket frame would have applied, so
// the dashboard's event handling code cannot tell the difference.

type Listener = (event: LedgerEvent) => void;

const listeners = new Set<Listener>();

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function broadcast(event: unknown): void {
  const payload = toWire<LedgerEvent>(event);
  for (const listener of listeners) {
    listener(payload);
  }
}
