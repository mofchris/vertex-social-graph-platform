#!/usr/bin/env bash
#
# Run the production stack. Usage, from the repo root on the server:
#
#   deploy/vertex.sh up        # build if needed and start everything (also used at boot)
#   deploy/vertex.sh redeploy  # git pull, rebuild what changed, restart it
#   deploy/vertex.sh status    # container health and memory
#   deploy/vertex.sh logs gateway
#   deploy/vertex.sh reset     # wipe and reseed the demo data (what the nightly timer runs)
set -euo pipefail

cd "$(dirname "$0")/.."
compose() {
  docker compose --project-name vertex --env-file deploy/.env \
    -f docker-compose.yml -f deploy/compose.prod.yml "$@"
}

case "${1:-}" in
  up)
    compose up -d --build --remove-orphans
    ;;
  redeploy)
    git pull --ff-only
    compose up -d --build --remove-orphans
    docker image prune -f > /dev/null
    ;;
  status)
    compose ps --format 'table {{.Service}}\t{{.Status}}'
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
    ;;
  logs)
    shift
    compose logs --tail 200 -f "$@"
    ;;
  reset)
    exec deploy/reset-demo.sh
    ;;
  compose)
    shift
    compose "$@"
    ;;
  *)
    echo "usage: deploy/vertex.sh up|redeploy|status|logs [service]|reset|compose <args>" >&2
    exit 2
    ;;
esac
