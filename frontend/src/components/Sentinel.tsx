"use client";

import { useEffect, useRef } from "react";

/** Calls onVisible when scrolled near the bottom of a list — drives infinite scroll. */
export function Sentinel({ onVisible, disabled }: { onVisible: () => void; disabled?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (disabled || !ref.current) return;
    const obs = new IntersectionObserver((entries) => entries[0]?.isIntersecting && onVisible(), {
      rootMargin: "600px 0px",
    });
    obs.observe(ref.current);
    return () => obs.disconnect();
  }, [onVisible, disabled]);
  return <div ref={ref} aria-hidden className="h-px" />;
}
