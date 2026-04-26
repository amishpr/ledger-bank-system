import { useEffect, useRef, useState } from "react";

// Tracks an element's rendered pixel width so an SVG chart can size its
// viewBox to match 1:1 instead of being scaled by CSS, which is what
// keeps hover math (mouse position -> data point) exact.
export function useContainerWidth<T extends HTMLElement>(fallback = 600) {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver((entries) => {
      const measured = entries[0]?.contentRect.width;
      if (measured) setWidth(measured);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return [ref, width] as const;
}
