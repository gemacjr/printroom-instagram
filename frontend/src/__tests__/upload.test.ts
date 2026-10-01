import { afterEach, describe, expect, it, vi } from "vitest";
import { MAX_IMAGE_BYTES, planParts, uploadFile, validateFile } from "@/lib/upload";

describe("validateFile", () => {
  it("accepts supported images and videos within limits", () => {
    expect(validateFile({ type: "image/jpeg", size: 1000 })).toBeNull();
    expect(validateFile({ type: "video/mp4", size: 3 * 1024 ** 3 })).toBeNull();
  });

  it("rejects unsupported types, empty files and oversized files", () => {
    expect(validateFile({ type: "application/pdf", size: 10 })).toMatch(/Choose a JPEG/);
    expect(validateFile({ type: "image/png", size: 0 })).toMatch(/empty/);
    expect(validateFile({ type: "image/png", size: MAX_IMAGE_BYTES + 1 })).toMatch(/8 MB/);
    expect(validateFile({ type: "video/webm", size: 5 * 1024 ** 3 })).toMatch(/4 GB/);
  });
});

describe("planParts", () => {
  it("splits into contiguous ranges with a short last part", () => {
    expect(planParts(25, 10, 3)).toEqual([
      { partNumber: 1, start: 0, end: 10 },
      { partNumber: 2, start: 10, end: 20 },
      { partNumber: 3, start: 20, end: 25 },
    ]);
  });

  it("covers every byte exactly once", () => {
    const parts = planParts(1_000_003, 100_000, 11);
    expect(parts[0].start).toBe(0);
    expect(parts.at(-1)!.end).toBe(1_000_003);
    for (let i = 1; i < parts.length; i++) expect(parts[i].start).toBe(parts[i - 1].end);
  });
});

/** Minimal XHR fake that "uploads" instantly and returns an ETag per URL. */
class FakeXHR {
  static sent: { url: string; size: number; contentType?: string }[] = [];
  upload = { onprogress: null as ((e: { loaded: number }) => void) | null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  status = 200;
  private url = "";
  private headers: Record<string, string> = {};
  open(_m: string, url: string) { this.url = url; }
  setRequestHeader(k: string, v: string) { this.headers[k] = v; }
  getResponseHeader() { return `"etag-${this.url.split("/").pop()}"`; }
  send(body: Blob) {
    FakeXHR.sent.push({ url: this.url, size: body.size, contentType: this.headers["Content-Type"] });
    this.upload.onprogress?.({ loaded: body.size });
    queueMicrotask(() => this.onload?.());
  }
}

describe("uploadFile", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    FakeXHR.sent = [];
  });

  it("does a single presigned PUT with the file's content type", async () => {
    vi.stubGlobal("XMLHttpRequest", FakeXHR);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      mediaId: "m1", mediaType: "IMAGE", uploadType: "SINGLE", uploadUrl: "https://s3/put", expiresAt: "",
    }), { status: 201 })));
    const progress: number[] = [];

    const id = await uploadFile(new File(["x".repeat(100)], "a.jpg", { type: "image/jpeg" }), (f) => progress.push(f));

    expect(id).toBe("m1");
    expect(FakeXHR.sent).toEqual([{ url: "https://s3/put", size: 100, contentType: "image/jpeg" }]);
    expect(progress.at(-1)).toBe(1);
  });

  it("uploads multipart slices in parallel and completes with sorted ETags", async () => {
    vi.stubGlobal("XMLHttpRequest", FakeXHR);
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.startsWith("/api/media/uploads")) {
        return new Response(JSON.stringify({
          mediaId: "v1", mediaType: "VIDEO", uploadType: "MULTIPART", partSize: 40, expiresAt: "",
          parts: [1, 2, 3].map((n) => ({ partNumber: n, url: `https://s3/part/${n}` })),
        }), { status: 201 });
      }
      return new Response(JSON.stringify({ mediaId: "v1", status: "PENDING_UPLOAD", body: init?.body }), { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const id = await uploadFile(new File(["y".repeat(100)], "v.mp4", { type: "video/mp4" }));

    expect(id).toBe("v1");
    expect(FakeXHR.sent.map((s) => s.size).sort()).toEqual([20, 40, 40]);
    expect(FakeXHR.sent.every((s) => s.contentType === undefined)).toBe(true);
    const complete = fetchMock.mock.calls.find(([u]) => String(u).includes("/complete"))!;
    expect(JSON.parse(String(complete[1]!.body))).toEqual({
      parts: [
        { partNumber: 1, eTag: '"etag-1"' },
        { partNumber: 2, eTag: '"etag-2"' },
        { partNumber: 3, eTag: '"etag-3"' },
      ],
    });
  });
});
