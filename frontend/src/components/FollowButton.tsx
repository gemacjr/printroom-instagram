"use client";

import { useState } from "react";
import { api } from "@/lib/api";

export function FollowButton({
  userId,
  initialFollowing,
  onChange,
}: {
  userId: string;
  initialFollowing: boolean;
  onChange?: (following: boolean) => void;
}) {
  const [following, setFollowing] = useState(initialFollowing);
  const [pending, setPending] = useState(false);

  async function toggle() {
    setPending(true);
    const next = !following;
    setFollowing(next); // optimistic
    try {
      if (next) await api.follow(userId);
      else await api.unfollow(userId);
      onChange?.(next);
    } catch {
      setFollowing(!next);
    } finally {
      setPending(false);
    }
  }

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={pending}
      aria-pressed={following}
      className={following ? "btn-secondary" : "btn-primary"}
    >
      {following ? "Following" : "Follow"}
    </button>
  );
}
