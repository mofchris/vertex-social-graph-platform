# Deploying Vertex

Production is one Oracle Cloud Always Free VM (Ampere A1, ARM64, 2 OCPU / 12 GB, Ubuntu 24.04)
running the same `docker-compose.yml` as local development, plus `compose.prod.yml` from this
folder. Visitors never reach the VM directly:

```
browser -> Cloudflare Worker (web/) -> Workers VPC service -> Cloudflare Tunnel -> cloudflared -> gateway
```

`cloudflared` only dials out, so the VM's firewall needs no inbound rule except SSH.

## First deploy

1. **VM.** Create the instance (Ubuntu 24.04, `VM.Standard.A1.Flex`, 2 OCPU, 12 GB, 100 GB boot
   volume) with your SSH key.
2. **Bootstrap.** On the VM, as `ubuntu`:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/mofchris/vertex-social-graph-platform/main/deploy/bootstrap-vm.sh | bash
   ```
   It installs Docker, adds 4 GB of swap, clones the repo to `/opt/vertex`, creates
   `deploy/.env` from the example and enables the systemd units.
3. **Tunnel.** Create a Cloudflare Tunnel (`wrangler tunnel create vertex`), then a Workers VPC
   service pointing at the gateway's fixed address on the compose network:
   ```bash
   wrangler vpc service create vertex-gateway --type http \
     --tunnel-id <tunnel id> --ipv4 172.30.0.10 --http-port 8088
   ```
   Put the service id in `web/wrangler.jsonc` (`vpc_services`).
4. **Secrets.** Fill in `/opt/vertex/deploy/.env`: two random values (`openssl rand -hex 32`) and
   the tunnel token (Cloudflare dashboard: Networking > Tunnels > vertex > Add a replica; the
   token is the `eyJ...` string in the install command). Keep it `chmod 600`; it is git-ignored.
5. **Start.** `sudo systemctl start vertex`. The first build takes 20 to 30 minutes on 2 OCPU;
   `deploy/vertex.sh status` shows progress. The `seed` job runs once everything is healthy.
6. **Site.** From your machine: `cd web && npm run deploy` (builds and runs `wrangler deploy`).

## Redeploy

- Backend: on the VM, `cd /opt/vertex && deploy/vertex.sh redeploy` (pulls, rebuilds what
  changed, restarts it).
- Site: `cd web && npm run deploy`.

## Operating it

| Task | Command (on the VM, in `/opt/vertex`) |
|---|---|
| Health and memory | `deploy/vertex.sh status` |
| Logs | `deploy/vertex.sh logs gateway` (any service) |
| Reset demo data now | `deploy/vertex.sh reset` |
| Reset schedule | `systemctl list-timers vertex-reset.timer` (daily 04:00 UTC) |
| Anything else | `deploy/vertex.sh compose <args>` |

After a reboot, Docker starts and `vertex.service` brings the stack back; every container also has
`restart: unless-stopped`.

## Safety for a public demo

- Only the gateway is reachable, and only through the tunnel. Service ports, Postgres, Redis and
  Kafka are not published (`ports: !reset []`).
- The gateway refuses each service's actuator and Notify's raw event endpoint from outside, and
  rate-limits on the visitor's real IP (the Worker sets `X-Vertex-Client-IP` from
  `CF-Connecting-IP` and overwrites anything the visitor sent).
- Buckets: general per IP and per user; sign-in and sign-up 10 at once then 1 every 5 s per IP;
  writes 30 then 1 every 2 s per user; replay/poison 6 then 3 a minute per user.
- Everything strangers create is wiped nightly by `reset-demo.sh`.
- Secrets live only in `deploy/.env` on the VM.

## Free-tier limits that apply

- Oracle Always Free A1: 2 OCPU and 12 GB in total, 200 GB of block storage, 10 TB of outbound
  data a month. Oracle may reclaim an Always Free instance that stays idle (CPU, network and memory
  all under 20%) for 7 days; the stack keeps about 3 GB of 12 GB in use.
- Cloudflare Workers free plan: 100,000 requests a day. Workers VPC is free while in beta.
