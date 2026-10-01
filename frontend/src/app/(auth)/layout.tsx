export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className="grid min-h-dvh place-items-center px-4 py-10">
      <div className="w-full max-w-sm">
        <p className="wordmark mb-2 text-center text-5xl">Printroom</p>
        <p className="mb-8 text-center text-silver">Photos and videos from the people you follow.</p>
        {children}
      </div>
    </main>
  );
}
