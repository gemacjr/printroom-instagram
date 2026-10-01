"use client";

import Link from "next/link";
import { useState } from "react";
import { Avatar } from "./Avatar";
import { relativeTime } from "@/lib/format";
import type { Post } from "@/lib/types";

/**
 * A post rendered as a print: media on a white mat, caption written beneath like a note on the print.
 */
export function PostCard({ post, onDelete, isOwn }: { post: Post; isOwn?: boolean; onDelete?: (id: string) => void }) {
  const [index, setIndex] = useState(0);
  const media = post.media[index];
  const count = post.media.length;
  const when = post.publishedAt ?? post.createdAt;

  return (
    <article className="print" aria-label={`Post by ${post.author.username}`}>
      <header className="flex items-center gap-3 px-1 pb-3">
        <Avatar user={post.author} size={30} />
        <Link href={`/u/${post.author.username}`} className="font-medium hover:underline">
          {post.author.username}
        </Link>
        <Link href={`/p/${post.postId}`} className="text-silver text-sm hover:underline" title={new Date(when).toLocaleString()}>
          <time dateTime={new Date(when).toISOString()}>{relativeTime(when)}</time>
        </Link>
        {isOwn && onDelete && (
          <button
            type="button"
            onClick={() => onDelete(post.postId)}
            className="ml-auto text-sm text-silver hover:text-danger"
          >
            Delete
          </button>
        )}
      </header>

      {post.status !== "PUBLISHED" ? (
        <div className="grid aspect-square place-items-center bg-paper text-silver">
          {post.status === "PROCESSING" ? "Developing… this post appears once processing finishes." : "Processing failed for this post."}
        </div>
      ) : media ? (
        <div className="relative bg-paper">
          {media.mediaType === "VIDEO" ? (
            <video src={media.url} controls playsInline preload="metadata" className="block w-full max-h-[80vh] bg-ink" />
          ) : (
            // eslint-disable-next-line @next/next/no-img-element -- served from CDN at final size already
            <img
              src={media.url}
              alt={post.caption ? `Photo: ${post.caption.slice(0, 100)}` : `Photo by ${post.author.username}`}
              width={media.width}
              height={media.height}
              loading="lazy"
              className="block h-auto w-full"
            />
          )}
          {count > 1 && (
            <div className="absolute inset-x-0 bottom-2 flex items-center justify-between px-2">
              <button
                type="button"
                aria-label="Previous"
                disabled={index === 0}
                onClick={() => setIndex((i) => i - 1)}
                className="carousel-btn"
              >
                ‹
              </button>
              <span className="rounded-full bg-white/85 px-2 py-0.5 text-xs tabular-nums text-ink">
                {index + 1} of {count}
              </span>
              <button
                type="button"
                aria-label="Next"
                disabled={index === count - 1}
                onClick={() => setIndex((i) => i + 1)}
                className="carousel-btn"
              >
                ›
              </button>
            </div>
          )}
        </div>
      ) : null}

      {post.caption && (
        <p className="caption px-1 pt-3">
          <Link href={`/u/${post.author.username}`} className="mr-2 font-sans text-[15px] font-medium not-italic">
            {post.author.username}
          </Link>
          {post.caption}
        </p>
      )}
    </article>
  );
}
