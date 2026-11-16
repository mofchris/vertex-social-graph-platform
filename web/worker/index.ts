// The Vertex edge. Serves the static site (out/) and forwards /api/* to the gateway through the tunnel.
//
// The gateway has no public address: the Worker reaches it through a Workers VPC service bound to
// a Cloudflare Tunnel, whose connector runs next to the gateway and only dials out. The Worker is
// also the one place that knows the visitor's real IP (CF-Connecting-IP), so it passes it on in
// X-Vertex-Client-IP, overwriting anything the visitor sent, and the gateway rate-limits on it.

interface Env {
  ASSETS: Fetcher;
  GATEWAY: Fetcher;
  GATEWAY_ORIGIN: string; // the address the VPC service resolves, e.g. http://172.30.0.10:8088
}

const STRIP = ["cookie", "x-vertex-client-ip", "x-forwarded-for", "x-real-ip", "forwarded"];

async function proxy(request: Request, env: Env, url: URL): Promise<Response> {
  const headers = new Headers(request.headers);
  STRIP.forEach((h) => headers.delete(h));
  const ip = request.headers.get("CF-Connecting-IP");
  if (ip) headers.set("X-Vertex-Client-IP", ip);

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  try {
    const upstream = await env.GATEWAY.fetch(`${env.GATEWAY_ORIGIN}${url.pathname}${url.search}`, {
      method: request.method,
      headers,
      body: hasBody ? request.body : undefined,
      redirect: "manual",
    });
    const response = new Response(upstream.body, upstream);
    response.headers.set("Cache-Control", "no-store");
    response.headers.delete("Set-Cookie");
    return response;
  } catch {
    // The tunnel or the machine behind it is down. Say so in the same shape the gateway uses for its own errors.
    return Response.json(
      { error: "backend unreachable", message: "The Vertex server is not answering. It may be restarting." },
      { status: 503, headers: { "Cache-Control": "no-store", "Retry-After": "15" } },
    );
  }
}

/**
 * Next's static export writes a nested segment's prefetch data as `__next.demo/__PAGE__.txt`, but
 * the client asks for `__next.demo.__PAGE__.txt`. Retry the nested path before giving up.
 */
function nestedSegmentPath(pathname: string): string | null {
  const m = pathname.match(/^(.*\/__next\.[^/]+)\.([^./]+)\.txt$/);
  return m ? `${m[1]}/${m[2]}.txt` : null;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) return proxy(request, env, url);
    const response = await env.ASSETS.fetch(request);
    const nested = response.status === 404 ? nestedSegmentPath(url.pathname) : null;
    if (nested) {
      const retry = await env.ASSETS.fetch(new Request(new URL(nested + url.search, url), request));
      if (retry.ok) return retry;
    }
    return response;
  },
} satisfies ExportedHandler<Env>;
