"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { Page } from "./types";

/**
 * Accumulates cursor-paginated results. `loadMore` is safe to call repeatedly (e.g. from an
 * IntersectionObserver); concurrent calls are ignored and it stops once nextCursor is null.
 */
/** `fetchPage` must be memoized (useCallback); changing it resets the list. */
export function useCursorPages<T>(fetchPage: (cursor: string | null) => Promise<Page<T>>) {
  const [items, setItems] = useState<T[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const generation = useRef(0);

  const load = useCallback(
    async (from: string | null, reset: boolean) => {
      if (inFlight.current) return;
      inFlight.current = true;
      const gen = generation.current;
      setLoading(true);
      setError(null);
      try {
        const page = await fetchPage(from);
        if (gen !== generation.current) return;
        setItems((prev) => (reset ? page.items : [...prev, ...page.items]));
        setCursor(page.nextCursor ?? null);
        setDone(!page.nextCursor);
      } catch (e) {
        if (gen === generation.current) setError(e instanceof Error ? e.message : "Something went wrong");
      } finally {
        inFlight.current = false;
        if (gen === generation.current) setLoading(false);
      }
    },
    [fetchPage],
  );

  const reload = useCallback(() => {
    generation.current += 1;
    inFlight.current = false;
    setDone(false);
    return load(null, true);
  }, [load]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch first page when inputs change
    void reload();
  }, [reload]);

  const loadMore = useCallback(() => {
    if (!done && !inFlight.current) void load(cursor, false);
  }, [cursor, done, load]);

  return { items, setItems, loading, error, done, loadMore, reload };
}
