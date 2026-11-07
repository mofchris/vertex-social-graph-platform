# Deploying Vertex

The backend is one machine running the same `docker-compose.yml` as local development, plus
`compose.prod.yml` from this folder. Today that machine is the owner's Windows PC (Ubuntu under
WSL 2); the same files also run on a Linux VM such as Oracle Cloud's Always Free Ampere A1.
Visitors never reach the machine directly:

```
browser -> Cloudflare Worker (web/) -> Workers VPC service -> Cloudflare Tunnel -> cloudflared -> gateway
```

`cloudflared` only dials out, so nothing needs an inbound port. The Cloudflare side doesn't know or
care which machine holds the tunnel token, so moving the backend means starting it somewhere else.

When the backend is unreachable the site stays up, and the demo offers a **recorded run**: a real
run captured against these services (`web/src/data/recording.json`), labelled as a recording with
its capture time. See [Re-recording the offline run](#re-recording-the-offline-run).

## Running it on a Windows PC (current production)

Needs WSL 2 with Ubuntu, `systemd=true` in `/etc/wsl.conf`, and Docker Engine installed inside it
(not Docker Desktop). About 3 GB of the memory WSL is given goes to the stack.

1. **Tunnel and VPC service** as in step 3 of the VM path below (already done for `vertex`).
2. **Bootstrap**, from the repository on Windows (root inside WSL, so no sudo password):
   ```bash
   wsl -d Ubuntu -u root --exec env RUN_AS=<wsl user> REPO=/mnt/c/<path to repo> BRANCH=main \
     bash /mnt/c/<path to repo>/deploy/bootstrap-vm.sh
   ```
   It clones to `/opt/vertex` inside WSL, creates `deploy/.env` and enables `vertex.service` and
   the nightly `vertex-reset.timer` under WSL's systemd. It skips swap (WSL has its own).
3. **Secrets**: fill `/opt/vertex/deploy/.env` as in step 4 below.
4. **Start**: `wsl -d Ubuntu -u root --exec systemctl start vertex`.
5. **Keep WSL running.** WSL stops its VM when nothing uses it, which would stop the stack. A
   shortcut in the Windows Startup folder (`shell:startup`), `Vertex backend (WSL).lnk`, runs
   `conhost.exe --headless wsl.exe -d Ubuntu --exec sleep infinity` at log-in, with no window.
   WSL boots, systemd starts Docker and `vertex.service`, and the stack comes back by itself.
6. **Power settings** (yours to set): the demo is live only while the PC is awake, so set sleep
   to "Never" when plugged in if you want it up around the clock.

To take it down for good: delete the Startup shortcut, then
`wsl -d Ubuntu -u root --exec systemctl disable --now vertex vertex-reset.timer`.

## On a Linux VM (Oracle Cloud Always Free)

### First deploy

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

### Redeploy

- Backend: on the VM, `cd /opt/vertex && deploy/vertex.sh redeploy` (pulls, rebuilds what
  changed, restarts it).
- Site: `cd web && npm run deploy`.

## Operating it

On the VM, run these in `/opt/vertex`. On the PC, prefix them with
`wsl -d Ubuntu --cd /opt/vertex --exec` (and use `-u root` for `systemctl`).

| Task | Command |
|---|---|
| Health and memory | `deploy/vertex.sh status` |
| Logs | `deploy/vertex.sh logs gateway` (any service) |
| Reset demo data now | `deploy/vertex.sh reset` |
| Reset schedule | `systemctl list-timers vertex-reset.timer` (daily 04:00 UTC) |
| Anything else | `deploy/vertex.sh compose <args>` |

After a reboot (of the VM, or of the PC once you log in), Docker starts and `vertex.service`
brings the stack back; every container also has
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
- Secrets live only in `deploy/.env` on the machine that runs the stack.

## Free-tier limits that apply

- Cloudflare Workers free plan: 100,000 requests a day. Workers VPC is free while in beta.
- On the VM path, Oracle Always Free A1: 2 OCPU and 12 GB in total, 200 GB of block storage, 10 TB of outbound
  data a month. Keep the account on the Free Tier (never upgrade to Pay As You Go) and use only
  "Always Free-eligible" resources; a $1 budget alert in Billing acts as a tripwire.

### Idle reclamation (VM path only)

Oracle may reclaim an Always Free VM when, over 7 days, CPU (95th percentile), network **and**
memory (A1 shapes) are **all** under 20%. CPU and network are often low between visitors; memory
is the margin: each JVM commits and pre-touches a heap floor at start (`InitialRAMPercentage=40`,
`AlwaysPreTouch` in `docker-compose.yml`), so the stack keeps about 3 GB of 12 GB (about 25%)
resident whether or not anyone is visiting, and the nightly reset is
a real daily burst of CPU and network. Check with `deploy/vertex.sh status`; if memory ever drifts
toward 20%, raise the JVM heap floor rather than generating fake traffic.

If the VM is reclaimed anyway, only the VM is lost (the site stays up and reports the backend as
offline). Recovery takes about 30 minutes and needs no Cloudflare changes, because the gateway's
address lives inside Docker and the tunnel dials out from whichever VM holds its token:

1. Create a new VM (same shape, Ubuntu 24.04, your SSH key).
2. Run `deploy/bootstrap-vm.sh`.
3. Fill `deploy/.env` with the same `TUNNEL_TOKEN` and two new random secrets.
4. `sudo systemctl start vertex`. The seed job recreates the demo data.

## Re-recording the offline run

The recording should come from the real backend and match the current code. With the stack up:

```bash
cd web
npm run build
VERTEX_GATEWAY_URL=http://<gateway host>:8088 npm run preview   # in a second terminal
node scripts/record-run.mjs                                     # writes src/data/recording.json
npm run deploy
```

The script opens the demo with `?record`, runs all five steps as a new guest, and saves each
finished capture plus the panels as they stood (ids and display names only, never tokens).
