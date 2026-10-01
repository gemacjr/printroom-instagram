"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { use, useEffect, useState } from "react";
import { PostCard } from "@/components/PostCard";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { Post } from "@/lib/types";

const POLL_MS = 2000;

export default function PostPage({ params }: { params: Promise<{ postId: string }> }) {
  const { postId } = use(params);
  const { user } = useAuth();
  const router = useRouter();
  const [post, setPost] = useState<Post | null>(null);
  const [missing, setMissing] = useState(false);

  // A freshly created post may still be PROCESSING while the media worker runs; poll until it settles.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let cancelled = false;
    async function load() {
      try {
        const p = await api.post(postId);
        if (cancelled) return;
        setPost(p);
        if (p.status === "PROCESSING") timer = setTimeout(load, POLL_MS);
      } catch (e) {
        if (!cancelled && e instanceof ApiError && e.status === 404) setMissing(true);
      }
    }
    void load();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [postId]);

  if (missing) {
    return (
      <div className="print max-w-[480px] text-center">
        <p className="font-serif text-2xl">This post isn’t available</p>
        <p className="mt-1 text-silver">It may have been deleted.</p>
        <Link href="/" className="btn-primary mt-4">Back to your feed</Link>
      </div>
    );
  }
  if (!post) return <p className="text-silver">Loading…</p>;

  return (
    <div className="max-w-[560px]">
      <PostCard
        post={post}
        isOwn={post.author.userId === user?.userId}
        onDelete={async (id) => {
          await api.deletePost(id);
          router.push(`/u/${post.author.username}`);
        }}
      />
    </div>
  );
}
