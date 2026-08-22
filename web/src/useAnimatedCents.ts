import { useEffect, useRef, useState } from "react";

// Tweens a cent amount for display only, never for arithmetic. Demo-scale
// account balances sit nowhere near Number.MAX_SAFE_INTEGER, so it is safe
// to interpolate through a plain number here even though the real value is
// carried everywhere else as a BigInt.
export function useAnimatedCents(target: bigint, duration = 650): bigint {
  const [display, setDisplay] = useState(target);
  const fromRef = useRef(target);
  const reduceMotion = prefersReducedMotion();

  useEffect(() => {
    const from = fromRef.current;
    if (from === target) return;

    // With reduced motion the number just changes, no count-up. The
    // return below hands back the target directly in that case.
    if (reduceMotion) {
      fromRef.current = target;
      return;
    }

    const fromNum = Number(from);
    const toNum = Number(target);
    const start = performance.now();
    let raf = 0;

    function tick(now: number) {
      const elapsed = Math.min(1, (now - start) / duration);
      const eased = 1 - (1 - elapsed) ** 3;
      const value = Math.round(fromNum + (toNum - fromNum) * eased);
      setDisplay(BigInt(value));
      if (elapsed < 1) {
        raf = requestAnimationFrame(tick);
      } else {
        fromRef.current = target;
      }
    }

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, duration, reduceMotion]);

  return reduceMotion ? target : display;
}

function prefersReducedMotion(): boolean {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}
