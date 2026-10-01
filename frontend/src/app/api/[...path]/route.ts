import type { NextRequest } from "next/server";

/**
 * Same-origin proxy to the Spring Boot API for local dev / docker-compose, so the browser never needs
 * CORS for API calls. API_URL is read at request time (not baked at build). In AWS, the ALB routes
 * /api/* to the API service before requests ever reach Next.js.
 */
const HOP_BY_HOP = ["connection", "keep-alive", "transfer-encoding", "host", "content-length"];

async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const base = process.env.API_URL ?? "http://localhost:8080";
  const target = `${base}/api/${path.map(encodeURIComponent).join("/")}${req.nextUrl.search}`;

  const headers = new Headers(req.headers);
  HOP_BY_HOP.forEach((h) => headers.delete(h));

  const hasBody = !["GET", "HEAD"].includes(req.method);
  try {
    const upstream = await fetch(target, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      cache: "no-store",
    });
    const respHeaders = new Headers(upstream.headers);
    HOP_BY_HOP.forEach((h) => respHeaders.delete(h));
    respHeaders.delete("content-encoding");
    return new Response(upstream.status === 204 ? null : await upstream.arrayBuffer(), {
      status: upstream.status,
      headers: respHeaders,
    });
  } catch {
    return Response.json({ code: "API_UNREACHABLE", message: "The API server is not reachable" }, { status: 502 });
  }
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
