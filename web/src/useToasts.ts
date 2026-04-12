import { useCallback, useRef, useState } from "react";

export interface Toast {
  id: string;
  message: string;
  kind: "success" | "error";
}

export function useToasts() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const counter = useRef(0);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (message: string, kind: Toast["kind"] = "success") => {
      const id = `${Date.now()}-${counter.current++}`;
      setToasts((prev) => [...prev, { id, message, kind }]);
      setTimeout(() => dismiss(id), 3500);
    },
    [dismiss],
  );

  return { toasts, push, dismiss };
}
