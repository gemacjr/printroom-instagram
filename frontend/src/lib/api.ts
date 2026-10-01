import type {
  ApiErrorBody,
  AuthResponse,
  FollowResult,
  InitiateUploadResponse,
  MediaResponse,
  Page,
  Post,
  UserProfile,
  UserSummary,
} from "./types";

export const TOKEN_KEY = "printroom.token";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly fieldErrors?: Record<string, string>,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

type Json = Record<string, unknown> | unknown[];

/**
 * All API calls go to same-origin /api/*: in local dev a Next route handler proxies to Spring Boot,
 * in AWS the ALB routes /api/* straight to the API service.
 */
export async function request<T>(
  path: string,
  init: { method?: string; body?: Json; query?: Record<string, string | number | undefined | null> } = {},
): Promise<T> {
  const url = new URL(`/api${path}`, typeof window === "undefined" ? "http://localhost" : window.location.origin);
  for (const [k, v] of Object.entries(init.query ?? {})) {
    if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
  }
  const headers: Record<string, string> = { Accept: "application/json" };
  if (init.body) headers["Content-Type"] = "application/json";
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(url.pathname + url.search, {
    method: init.method ?? "GET",
    headers,
    body: init.body ? JSON.stringify(init.body) : undefined,
  });

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? safeParse(text) : undefined;
  if (!res.ok) {
    const body = (data ?? {}) as Partial<ApiErrorBody>;
    throw new ApiError(res.status, body.code ?? "HTTP_ERROR", body.message ?? `Request failed (${res.status})`, body.fieldErrors);
  }
  return data as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const api = {
  register: (body: { username: string; email: string; password: string; displayName?: string }) =>
    request<AuthResponse>("/auth/register", { method: "POST", body }),
  login: (body: { username: string; password: string }) =>
    request<AuthResponse>("/auth/login", { method: "POST", body }),

  me: () => request<UserProfile>("/users/me"),
  updateMe: (body: { displayName?: string; bio?: string }) =>
    request<UserProfile>("/users/me", { method: "PATCH", body }),
  profile: (username: string) => request<UserProfile>(`/users/by-username/${encodeURIComponent(username)}`),
  userPosts: (userId: string, cursor?: string | null) =>
    request<Page<Post>>(`/users/${userId}/posts`, { query: { cursor, limit: 18 } }),
  followers: (userId: string, cursor?: string | null) =>
    request<Page<UserSummary>>(`/users/${userId}/followers`, { query: { cursor } }),
  following: (userId: string, cursor?: string | null) =>
    request<Page<UserSummary>>(`/users/${userId}/following`, { query: { cursor } }),
  search: (q: string) => request<UserSummary[]>("/users/search", { query: { q } }),

  follow: (followeeId: string) => request<FollowResult>("/follows", { method: "POST", body: { followeeId } }),
  unfollow: (followeeId: string) => request<FollowResult>(`/follows/${followeeId}`, { method: "DELETE" }),

  feed: (cursor?: string | null) => request<Page<Post>>("/feed", { query: { cursor, limit: 10 } }),
  post: (postId: string) => request<Post>(`/posts/${postId}`),
  createPost: (body: { caption: string; mediaIds: string[] }) =>
    request<Post>("/posts", { method: "POST", body }),
  deletePost: (postId: string) => request<void>(`/posts/${postId}`, { method: "DELETE" }),

  initiateUpload: (body: { contentType: string; sizeBytes: number }) =>
    request<InitiateUploadResponse>("/media/uploads", { method: "POST", body }),
  completeUpload: (mediaId: string, parts: { partNumber: number; eTag: string }[]) =>
    request<MediaResponse>(`/media/${mediaId}/complete`, { method: "POST", body: { parts } }),
  media: (mediaId: string) => request<MediaResponse>(`/media/${mediaId}`),
};
