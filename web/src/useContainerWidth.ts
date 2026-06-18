import { useCallback, useState } from "react";

// Tracks an element's rendered pixel width so an SVG chart can size its
// viewBox to match 1:1 instead of being scaled by CSS, which is what
// keeps hover math (mouse position -> data point) exact.
//
// This is a callback ref rather than a ref object plus an effect, because
// the elements it measures are inside components that render a placeholder
// first and the real element only once their data arrives. An effect with
// an empty dependency list runs while the ref is still null and never gets
// a second chance, which left the chart stuck at the fallback width.
export function useContainerWidth<T extends HTMLElement>(fallback = 600) {
  const [width, setWidth] = useState(fallback);

  const ref = useCallback((node: T | null) => {
    if (!node) return;
    const observer = new ResizeObserver((entries) => {
      const measured = entries[0]?.contentRect.width;
      if (measured) setWidth(measured);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return [ref, width] as const;
}
