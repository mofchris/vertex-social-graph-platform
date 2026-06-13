# Vertex — Distributed Social Graph & Identity Platform

A scalable backend platform that manages user **identities**, **relationships**, and
**real-time social interactions** for millions of users — built as a microservices system
to demonstrate distributed-systems design, caching strategy, cloud-native deployment, and
operating services reliably at scale.

> Vertex is the product name for this platform. The repo includes a polished frontend
> showcase plus the full systems-design documentation behind it.

---

## Why this exists

This project is a focused study in backend engineering at scale: identity and auth, a
sharded social graph, hybrid feed fan-out, Redis caching, a Kafka event pipeline, and the
observability and reliability patterns that keep it all running. The
[edge cases](./EDGE_CASES.md) doc captures the failure modes a real system has to handle.

## Repository structure

```
.
├── web/            Next.js + TypeScript + Tailwind frontend showcase (live)
├── PROJECT.md      Full stack, architecture, and feature breakdown
├── EDGE_CASES.md   Edge cases by service (interview-grade reference)
└── services/       Backend microservices (in progress)
```

## Tech stack

- **Backend:** Java (Spring Boot) + Python (FastAPI), gRPC internal / REST public
- **Data:** PostgreSQL (sharded), Redis (cache, sessions, rate limiting), Apache Kafka
- **Infra:** Docker, Kubernetes, AWS/GCP, Terraform, GitHub Actions
- **Observability:** Prometheus, Grafana, OpenTelemetry
- **Frontend:** Next.js 16, React 19, TypeScript, Tailwind CSS v4, shadcn/ui

See [PROJECT.md](./PROJECT.md) for the complete breakdown.

## Architecture

```
Clients → API Gateway → Microservices → PostgreSQL / Redis / Kafka
                         (Identity · Profile · Graph · Feed · Recommend · Notify)
```

Stateless services scale independently behind a gateway, talk over gRPC for synchronous
calls and Kafka for asynchronous events, and degrade gracefully under load.

## Run the frontend

```bash
cd web
npm install
npm run dev
# http://localhost:3000
```

Routes: `/` (landing), `/login` (sign in), `/signup` (create account).

## Status

- ✅ Frontend showcase — landing, architecture overview, auth pages
- ✅ Systems-design docs — `PROJECT.md`, `EDGE_CASES.md`
- 🚧 Backend services — being built out service by service (Identity first)
