# Vertex web

The site and live demo for Vertex: a Next.js 16 static export served by a Cloudflare Worker,
which also forwards `/api/*` to the gateway. The browser only ever calls same-origin `/api`.

```bash
npm install
npm run dev              # http://localhost:3000; /api is rewritten to the gateway on :8088
npm run build            # static export into out/
npm run preview          # serve out/ like the Worker does, /api -> VERTEX_GATEWAY_URL (default :8088)
npm run lint
npm run typecheck:worker
npm run deploy           # build, then wrangler deploy (Worker + assets)
```

Start the backend first (`docker compose up -d --build` in the repo root).

## Layout

```
src/app/            routes: / (overview), /demo (the bench), 404, sitemap, robots, icon
src/components/     header, footer, live status, the trace schematic, lanes
src/components/demo the demo: session and actions (useDemo), capture maths, UI
src/lib/api.ts      gateway client: timing, Server-Timing, refresh, SSE over fetch
src/engine/         motion: one GSAP clock, Lenis, reveals, page transition
worker/index.ts     the Cloudflare Worker: assets + /api proxy over Workers VPC
DESIGN.md           concept, palette, type and motion rules
```

Every number the demo shows comes from the API or is measured in the browser; see
`src/components/demo/capture.ts` for how timings are placed on one axis.
