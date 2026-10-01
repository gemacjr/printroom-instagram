"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Avatar } from "@/components/Avatar";
import { api } from "@/lib/api";
import type { UserSummary } from "@/lib/types";

export default function ExplorePage() {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<UserSummary[]>([]);
  const [searched, setSearched] = useState(false);

  useEffect(() => {
    const term = q.trim();
    if (!term) return;
    const t = setTimeout(async () => {
      try {
        setResults(await api.search(term));
      } finally {
        setSearched(true);
      }
    }, 250);
    return () => clearTimeout(t);
  }, [q]);

  const visible = q.trim() ? results : [];

  return (
    <div className="max-w-[480px]">
      <h1 className="mb-6 font-serif text-3xl">Find people</h1>
      <label className="block">
        <span className="sr-only">Search by username</span>
        <input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search by username"
          className="field"
          autoFocus
          autoCapitalize="none"
        />
      </label>
      <ul className="mt-4 flex flex-col">
        {visible.map((u) => (
          <li key={u.userId}>
            <Link href={`/u/${u.username}`} className="flex items-center gap-3 rounded-md px-2 py-2.5 hover:bg-print">
              <Avatar user={u} size={36} />
              <span>
                <span className="block font-medium">{u.username}</span>
                <span className="block text-sm text-silver">{u.displayName}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      {q.trim() && searched && visible.length === 0 && (
        <p className="mt-4 text-silver">No usernames start with “{q.trim()}”.</p>
      )}
    </div>
  );
}
