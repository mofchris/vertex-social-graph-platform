// Serve the production build (out/) the way the Worker does, for local QA without Cloudflare:
// static files, the designed 404, and /api/* proxied (streaming) to a local gateway.
//   npm run build && npm run preview           # http://localhost:4400, gateway on :8088
import http from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import { extname, join, normalize } from "node:path";

const root = join(import.meta.dirname, "..", "out");
const port = Number(process.env.PORT ?? 4400);
const gateway = new URL(process.env.VERTEX_GATEWAY_URL ?? "http://localhost:8088");
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml",
  ".png": "image/png", ".webp": "image/webp", ".woff2": "font/woff2", ".txt": "text/plain", ".xml": "application/xml", ".json": "application/json" };

function file(path) {
  const p = normalize(join(root, decodeURIComponent(path))).replace(/[\\/]+$/, "");
  if (!p.startsWith(root)) return null;
  for (const c of [p, `${p}.html`, join(p, "index.html")]) if (existsSync(c) && statSync(c).isFile()) return c;
  return null;
}

http
  .createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    if (url.pathname.startsWith("/api/")) {
      const up = http.request(
        { host: gateway.hostname, port: gateway.port, path: url.pathname + url.search, method: req.method,
          headers: { ...req.headers, host: gateway.host } },
        (r) => {
          res.writeHead(r.statusCode ?? 502, r.headers);
          r.pipe(res);
        },
      );
      up.on("error", () => {
        res.writeHead(503, { "content-type": "application/json" });
        res.end('{"error":"backend unreachable"}');
      });
      req.pipe(up);
      return;
    }
    // Same nested-segment fallback as worker/index.ts.
    const m = url.pathname.match(/^(.*\/__next\.[^/]+)\.([^./]+)\.txt$/);
    const f = file(url.pathname) ?? (m ? file(`${m[1]}/${m[2]}.txt`) : null);
    if (!f) {
      res.writeHead(404, { "content-type": types[".html"] });
      return createReadStream(join(root, "404.html")).pipe(res);
    }
    res.writeHead(200, { "content-type": types[extname(f)] ?? "application/octet-stream" });
    createReadStream(f).pipe(res);
  })
  .listen(port, () => console.log(`serving out/ on http://localhost:${port}, /api -> ${gateway.origin}`));
