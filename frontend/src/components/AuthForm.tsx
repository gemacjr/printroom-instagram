"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const { signIn } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const get = (k: string) => String(form.get(k) ?? "").trim();
    setPending(true);
    setError(null);
    setFieldErrors({});
    try {
      const resp =
        mode === "login"
          ? await api.login({ username: get("username"), password: String(form.get("password") ?? "") })
          : await api.register({
              username: get("username").toLowerCase(),
              email: get("email"),
              password: String(form.get("password") ?? ""),
              displayName: get("displayName") || undefined,
            });
      signIn(resp);
      const next = params.get("next");
      router.replace(next && next.startsWith("/") ? next : "/");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.code === "VALIDATION_FAILED" ? "Check the highlighted fields." : err.message);
        setFieldErrors(err.fieldErrors ?? {});
      } else {
        setError("Couldn’t reach the server. Check that the API is running.");
      }
    } finally {
      setPending(false);
    }
  }

  const field = (name: string, label: string, props: React.InputHTMLAttributes<HTMLInputElement>) => (
    <label className="block">
      <span className="mb-1 block text-sm font-medium">{label}</span>
      <input name={name} className="field" aria-invalid={!!fieldErrors[name]} {...props} />
      {fieldErrors[name] && <span className="mt-1 block text-sm text-danger">{fieldErrors[name]}</span>}
    </label>
  );

  return (
    <form onSubmit={onSubmit} className="print flex flex-col gap-4 p-6" noValidate>
      <h1 className="font-serif text-2xl">{mode === "login" ? "Log in" : "Create your account"}</h1>
      {field("username", "Username", { autoComplete: "username", required: true, autoCapitalize: "none" })}
      {mode === "register" && field("email", "Email", { type: "email", autoComplete: "email", required: true })}
      {mode === "register" && field("displayName", "Name (optional)", { autoComplete: "name" })}
      {field("password", "Password", {
        type: "password",
        autoComplete: mode === "login" ? "current-password" : "new-password",
        required: true,
        minLength: mode === "register" ? 8 : undefined,
      })}
      {mode === "register" && <p className="-mt-2 text-sm text-silver">At least 8 characters.</p>}
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      <button type="submit" disabled={pending} className="btn-primary mt-1 py-2.5">
        {pending ? "One moment…" : mode === "login" ? "Log in" : "Create account"}
      </button>
      <p className="text-center text-sm text-silver">
        {mode === "login" ? (
          <>New here? <Link href="/register" className="text-cyan hover:underline">Create an account</Link></>
        ) : (
          <>Already have an account? <Link href="/login" className="text-cyan hover:underline">Log in</Link></>
        )}
      </p>
    </form>
  );
}
