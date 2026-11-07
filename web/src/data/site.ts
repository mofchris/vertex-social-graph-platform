// All site copy and facts in one place. Every number here is true of the code in this repo or was
// measured on the running system; nothing is a projection.

export const site = {
  name: "Vertex",
  url: process.env.NEXT_PUBLIC_SITE_URL ?? "https://vertex.mofchris.workers.dev",
  github: "https://github.com/mofchris/vertex-social-graph-platform",
  author: "Christopher Mofunanya",
  description:
    "Vertex is a social network built as seven Java services on Postgres, Redis and Kafka, running live. Follow someone and trace the event through the gateway, an outbox, Kafka and an idempotent consumer.",
  resetUtc: "04:00 UTC",
};

export type Lane = { id: string; label: string; detail: string };

/** The lanes of a follow, in the order the event crosses them. */
export const followLanes: Lane[] = [
  { id: "browser", label: "Browser", detail: "You press Follow. The request leaves with your access token." },
  { id: "gateway", label: "Gateway", detail: "Checks the token and your rate-limit bucket in Redis, then forwards to Graph." },
  { id: "graph", label: "Graph", detail: "Writes the follow edge and an outbox row in one Postgres transaction." },
  { id: "outbox", label: "Outbox", detail: "A relay polls the outbox and publishes the row to Kafka, then marks it sent." },
  { id: "kafka", label: "Kafka", detail: "The event lands on social.events at a partition and offset." },
  { id: "notify", label: "Notify", detail: "Records the event id in its dedupe ledger and writes the notification." },
  { id: "partner", label: "Partner", detail: "The notification is pushed to the other person's open stream." },
];

export type Service = { name: string; port: number; owns: string; store: string };

export const services: Service[] = [
  { name: "Gateway", port: 8088, owns: "The only way in. Routes /api/<service>, verifies the JWT at the edge, and meters every client with token buckets run as one Lua script in Redis.", store: "Redis" },
  { name: "Identity", port: 8080, owns: "Sign-up and sign-in, 15-minute access tokens, refresh tokens that rotate on every use and revoke the whole family if an old one is replayed.", store: "Postgres" },
  { name: "Profile", port: 8081, owns: "Names and bios behind a read-through Redis cache. Privacy is applied when a profile is served, so a cached copy never leaks.", store: "Postgres, Redis" },
  { name: "Graph", port: 8082, owns: "Follows, friend requests and blocks. Each change and its event are committed together through a transactional outbox.", store: "Postgres, Kafka" },
  { name: "Feed", port: 8083, owns: "Posts and home timelines. Pushes to followers on write, but pulls accounts with more than 20 followers at read time.", store: "Postgres" },
  { name: "Notify", port: 8084, owns: "Consumes social.events at least once, deduplicates on the event id, parks unreadable records in a dead-letter topic, and streams over SSE.", store: "Postgres, Kafka" },
  { name: "Recommend", port: 8085, owns: "People you may know from friends of friends, capped at supernodes, topped up with popular accounts when you are new.", store: "Stateless" },
];

export const facts = [
  { value: "7", label: "Spring Boot services, each with its own database or none" },
  { value: "2", label: "Kafka topics: social.events and its dead-letter topic" },
  { value: "80", label: "Fictional people seeded through the real APIs" },
  { value: "0", label: "Inbound ports open on the server. Traffic arrives through a tunnel" },
];

export const hosting = [
  { name: "Oracle Cloud Always Free", what: "One Ampere ARM VM, 2 OCPU and 12 GB, runs the whole Docker Compose stack." },
  { name: "Cloudflare Tunnel", what: "An outbound-only connector on the VM. No public IP is exposed to visitors." },
  { name: "Cloudflare Workers", what: "Serves this site and forwards /api to the gateway over Workers VPC." },
];
