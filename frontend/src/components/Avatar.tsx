const TINTS = ["#1D4F91", "#3B6B5A", "#7A4E2D", "#5B4A8A", "#8A3B4E", "#2F6F7A"];

function tintFor(id: string) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return TINTS[h % TINTS.length];
}

/** Monogram avatar; colour is stable per user. */
export function Avatar({ user, size = 32 }: { user: { userId: string; displayName: string; username: string }; size?: number }) {
  const initial = (user.displayName || user.username).trim().charAt(0).toUpperCase() || "?";
  return (
    <span
      aria-hidden
      className="inline-grid shrink-0 place-items-center rounded-full font-serif text-white"
      style={{ width: size, height: size, background: tintFor(user.userId), fontSize: size * 0.5 }}
    >
      {initial}
    </span>
  );
}
