import type { NextConfig } from "next";

// Production is a static export (out/) served by the Cloudflare Worker in worker/, which also
// proxies /api/* to the gateway. In `next dev` the same /api/* path is rewritten to a local
// gateway instead, so the browser code never needs to know where the backend lives.
const isDev = process.env.NODE_ENV === "development";
const devGateway = process.env.VERTEX_GATEWAY_URL ?? "http://localhost:8088";

const nextConfig: NextConfig = {
  output: isDev ? undefined : "export",
  trailingSlash: true,
  images: { unoptimized: true },
  poweredByHeader: false,
  ...(isDev && {
    async rewrites() {
      return [{ source: "/api/:path*", destination: `${devGateway}/api/:path*` }];
    },
  }),
};

export default nextConfig;
