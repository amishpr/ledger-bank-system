import { useEffect, useRef, useState } from "react";
import type { LedgerEvent } from "./api/types";
import { subscribe } from "./demo/bus";
import { DEMO_MODE } from "./demo/mode";

const WS_URL = import.meta.env.VITE_WS_URL ?? "ws://localhost:4000/ws";

export type ConnectionStatus = "connecting" | "open" | "closed";

// Reconnects with backoff so a backend restart during a demo doesn't leave
// the dashboard silently stale.
export function useLedgerSocket(onEvent: (event: LedgerEvent) => void) {
  // In demo mode there is nothing to connect to, so the status starts at
  // its only possible value instead of flickering through "connecting".
  const [status, setStatus] = useState<ConnectionStatus>(DEMO_MODE ? "open" : "connecting");
  const onEventRef = useRef(onEvent);
  useEffect(() => {
    onEventRef.current = onEvent;
  });

  useEffect(() => {
    // In demo mode the "server" is in this tab, so there is nothing to
    // connect to and nothing that can drop. The subscription delivers the
    // same event objects the WebSocket would have delivered.
    if (DEMO_MODE) {
      return subscribe((event) => onEventRef.current(event));
    }

    let socket: WebSocket | undefined;
    let retryDelay = 1000;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;

    function connect() {
      setStatus("connecting");
      socket = new WebSocket(WS_URL);

      socket.onopen = () => {
        retryDelay = 1000;
        setStatus("open");
      };
      socket.onmessage = (message) => {
        try {
          onEventRef.current(JSON.parse(message.data) as LedgerEvent);
        } catch {
          // ignore malformed frames
        }
      };
      socket.onclose = () => {
        setStatus("closed");
        if (stopped) return;
        retryTimer = setTimeout(connect, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 10_000);
      };
      socket.onerror = () => socket?.close();
    }

    connect();
    return () => {
      stopped = true;
      clearTimeout(retryTimer);
      socket?.close();
    };
  }, []);

  return status;
}
