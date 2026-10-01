"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { Avatar } from "@/components/Avatar";
import { useAuth } from "@/lib/auth";

const NAV = [
  { href: "/", label: "Feed" },
  { href: "/explore", label: "Find people" },
  { href: "/new", label: "New post" },
];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, signOut } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!loading && !user) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [loading, user, router, pathname]);

  if (!user) {
    return <div className="grid min-h-dvh place-items-center text-silver">Loading…</div>;
  }

  const isActive = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));

  return (
    <div className="mx-auto flex max-w-5xl gap-10 px-4 md:px-6">
      <nav
        aria-label="Main"
        className="sticky top-0 hidden h-dvh w-52 shrink-0 flex-col gap-1 py-8 md:flex"
      >
        <Link href="/" className="wordmark mb-8 px-3">Printroom</Link>
        {NAV.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            aria-current={isActive(item.href) ? "page" : undefined}
            className={`rounded-md px-3 py-2 ${isActive(item.href) ? "bg-print font-semibold" : "hover:bg-print/60"}`}
          >
            {item.label}
          </Link>
        ))}
        <Link
          href={`/u/${user.username}`}
          aria-current={pathname === `/u/${user.username}` ? "page" : undefined}
          className={`mt-2 flex items-center gap-2 rounded-md px-3 py-2 ${pathname === `/u/${user.username}` ? "bg-print font-semibold" : "hover:bg-print/60"}`}
        >
          <Avatar user={user} size={22} />
          Your profile
        </Link>
        <button type="button" onClick={signOut} className="mt-auto rounded-md px-3 py-2 text-left text-silver hover:bg-print/60">
          Log out
        </button>
      </nav>

      <main className="min-w-0 flex-1 pb-24 pt-6 md:py-8">
        <div className="mb-6 flex items-center justify-between md:hidden">
          <Link href="/" className="wordmark">Printroom</Link>
          <button type="button" onClick={signOut} className="text-sm text-silver">Log out</button>
        </div>
        {children}
      </main>

      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-10 flex justify-around border-t border-rule bg-print/95 py-2 backdrop-blur md:hidden"
      >
        {[...NAV, { href: `/u/${user.username}`, label: "Profile" }].map((item) => (
          <Link
            key={item.href}
            href={item.href}
            aria-current={isActive(item.href) ? "page" : undefined}
            className={`px-3 py-1.5 text-sm ${isActive(item.href) ? "font-semibold text-cyan" : "text-ink"}`}
          >
            {item.label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
