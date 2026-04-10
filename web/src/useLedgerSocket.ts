import { useEffect, useRef, useState } from "react";
import type { LedgerEvent } from "./api/types";

const WS_URL = import.meta.env.VITE_WS_URL ?? "ws://localhost:4000/ws";

export type ConnectionStatus = "connecting" | "open" | "closed";

// Reconnects with backoff so a backend restart during a demo doesn't leave
// the dashboard silently stale.
export function useLedgerSocket(onEvent: (event: LedgerEvent) => void) {
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const onEventRef = useRef(onEvent);
  useEffect(() => {
    onEventRef.current = onEvent;
  });

  useEffect(() => {
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
