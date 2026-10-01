import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, TOKEN_KEY } from "@/lib/api";

describe("api client", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it("sends the bearer token and query params, skipping empty ones", async () => {
    localStorage.setItem(TOKEN_KEY, "tok123");
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ items: [], nextCursor: null })));
    vi.stubGlobal("fetch", fetchMock);

    await api.feed("abc");
    await api.feed(null);

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/feed?cursor=abc&limit=10");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok123");
    expect((fetchMock.mock.calls[1] as unknown as [string])[0]).toBe("/api/feed?limit=10");
  });

  it("maps error bodies to ApiError with code and field errors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      code: "VALIDATION_FAILED", message: "Request validation failed", fieldErrors: { username: "too short" },
    }), { status: 400 })));

    const err = await api.login({ username: "a", password: "b" }).catch((e) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.code).toBe("VALIDATION_FAILED");
    expect(err.fieldErrors).toEqual({ username: "too short" });
  });

  it("handles 204 and non-JSON error bodies", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
    await expect(api.deletePost("p1")).resolves.toBeUndefined();

    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>bad gateway</html>", { status: 502 })));
    await expect(api.me()).rejects.toMatchObject({ status: 502, code: "HTTP_ERROR" });
  });
});
