"use client";

import Link from "next/link";
import { useCallback } from "react";
import { PostCard } from "@/components/PostCard";
import { Sentinel } from "@/components/Sentinel";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useCursorPages } from "@/lib/useCursorPages";

export default function FeedPage() {
  const { user } = useAuth();
  const fetchPage = useCallback((cursor: string | null) => api.feed(cursor), []);
  const { items, setItems, loading, error, done, loadMore, reload } = useCursorPages(fetchPage);

  async function remove(postId: string) {
    await api.deletePost(postId);
    setItems((prev) => prev.filter((p) => p.postId !== postId));
  }

  return (
    <div className="max-w-[480px]">
      <div className="mb-6 flex items-baseline justify-between">
        <h1 className="font-serif text-3xl">Your feed</h1>
        <button type="button" onClick={() => void reload()} className="text-sm text-cyan hover:underline">
          Refresh
        </button>
      </div>

      <div className="flex flex-col gap-8">
        {items.map((post) => (
          <PostCard key={post.postId} post={post} isOwn={post.author.userId === user?.userId} onDelete={remove} />
        ))}
      </div>

      {error && (
        <p role="alert" className="mt-6 text-danger">
          The feed didn’t load: {error}.{" "}
          <button type="button" className="underline" onClick={() => void reload()}>Try again</button>
        </p>
      )}
      {!loading && !error && items.length === 0 && (
        <div className="print mt-2 text-center">
          <p className="font-serif text-2xl">Nothing here yet</p>
          <p className="mt-2 text-silver">Posts from people you follow show up here, newest first.</p>
          <div className="mt-5 flex justify-center gap-3">
            <Link href="/explore" className="btn-primary">Find people to follow</Link>
            <Link href="/new" className="btn-secondary">Share a photo</Link>
          </div>
        </div>
      )}
      {loading && <p className="mt-8 text-center text-silver">Loading…</p>}
      {done && items.length > 0 && <p className="mt-10 text-center text-sm text-silver">You’re all caught up.</p>}
      <Sentinel onVisible={loadMore} disabled={done || loading || !!error} />
    </div>
  );
}
