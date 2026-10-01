"use client";

import Link from "next/link";
import { use, useCallback, useEffect, useState } from "react";
import { Avatar } from "@/components/Avatar";
import { FollowButton } from "@/components/FollowButton";
import { Sentinel } from "@/components/Sentinel";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { compactCount, plural } from "@/lib/format";
import type { Page, Post, UserProfile } from "@/lib/types";
import { useCursorPages } from "@/lib/useCursorPages";

export default function ProfilePage({ params }: { params: Promise<{ username: string }> }) {
  const { username } = use(params);
  const { user: me } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    api.profile(username).then(setProfile).catch((e) => {
      if (e instanceof ApiError && e.status === 404) setNotFound(true);
    });
  }, [username]);

  const userId = profile?.userId;
  const fetchPage = useCallback(
    (cursor: string | null): Promise<Page<Post>> =>
      userId ? api.userPosts(userId, cursor) : Promise.resolve({ items: [], nextCursor: null }),
    [userId],
  );
  const { items, loading, done, loadMore } = useCursorPages(fetchPage);

  if (notFound) {
    return (
      <div className="print max-w-[480px] text-center">
        <p className="font-serif text-2xl">No one goes by @{username}</p>
        <Link href="/explore" className="btn-primary mt-4">Find people</Link>
      </div>
    );
  }
  if (!profile) return <p className="text-silver">Loading…</p>;

  const isMe = me?.userId === profile.userId;

  return (
    <div className="max-w-[720px]">
      <header className="mb-8 flex flex-wrap items-start gap-6">
        <Avatar user={profile} size={88} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-serif text-4xl leading-none">{profile.displayName}</h1>
            {!isMe && (
              <FollowButton
                key={profile.userId}
                userId={profile.userId}
                initialFollowing={!!profile.isFollowing}
                onChange={(following) =>
                  setProfile((p) => p && { ...p, isFollowing: following, followerCount: p.followerCount + (following ? 1 : -1) })
                }
              />
            )}
          </div>
          <p className="mt-1 text-silver">@{profile.username}</p>
          <dl className="mt-4 flex gap-6 text-sm">
            {[
              [profile.postCount, "post"],
              [profile.followerCount, "follower"],
            ].map(([n, label]) => (
              <div key={label as string} className="flex gap-1">
                <dt className="sr-only">{label}s</dt>
                <dd><strong className="tabular-nums">{compactCount(n as number)}</strong> {plural(n as number, label as string)}</dd>
              </div>
            ))}
            <div className="flex gap-1">
              <dt className="sr-only">Following</dt>
              <dd><strong className="tabular-nums">{compactCount(profile.followingCount)}</strong> following</dd>
            </div>
          </dl>
          {profile.bio && <p className="caption mt-3">{profile.bio}</p>}
        </div>
      </header>

      {items.length === 0 && !loading ? (
        <div className="print text-center text-silver">
          {isMe ? (
            <>No posts yet. <Link href="/new" className="text-cyan hover:underline">Share your first photo</Link>.</>
          ) : (
            "No posts yet."
          )}
        </div>
      ) : (
        <ul className="grid grid-cols-3 gap-1.5 sm:gap-3">
          {items.map((post) => {
            const cover = post.media[0];
            return (
              <li key={post.postId} className="bg-print p-1 sm:p-1.5">
                <Link href={`/p/${post.postId}`} className="relative block aspect-square overflow-hidden bg-paper" aria-label={post.caption || "Open post"}>
                  {cover?.mediaType === "VIDEO" ? (
                    <video src={cover.url} muted preload="metadata" className="h-full w-full object-cover" />
                  ) : cover ? (
                    // eslint-disable-next-line @next/next/no-img-element -- CDN-served rendition
                    <img src={cover.url} alt="" loading="lazy" className="h-full w-full object-cover" />
                  ) : null}
                  {post.media.length > 1 && (
                    <span className="absolute right-1.5 top-1.5 rounded bg-white/85 px-1.5 text-xs tabular-nums">{post.media.length}</span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {loading && <p className="mt-6 text-center text-silver">Loading…</p>}
      <Sentinel onVisible={loadMore} disabled={done || loading} />
    </div>
  );
}
