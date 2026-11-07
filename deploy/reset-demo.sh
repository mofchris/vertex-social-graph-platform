#!/usr/bin/env bash
#
# Wipe everything strangers created and put the demo back to its seeded state. Run nightly by
# vertex-reset.timer; safe to run by hand (deploy/vertex.sh reset). Takes about two minutes, during
# which the site shows "the backend is starting".
#
# Each service owns its schema and recreates it with Flyway on start, so a reset is: stop the
# services, drop and recreate their databases, clear Redis and the Kafka topics, start the services,
# and run the same seed job compose runs on first boot.
set -euo pipefail

cd "$(dirname "$0")/.."
compose() {
  docker compose --project-name vertex --env-file deploy/.env \
    -f docker-compose.yml -f deploy/compose.prod.yml "$@"
}
APPS=(gateway recommend feed profile notify graph identity)

echo "$(date -u +%FT%TZ) reset: stopping services"
compose stop "${APPS[@]}"

echo "$(date -u +%FT%TZ) reset: recreating databases"
for db in identity profile graph feed notify; do
  compose exec -T postgres psql -U vertex -d vertex -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS $db WITH (FORCE);" -c "CREATE DATABASE $db;"
done

echo "$(date -u +%FT%TZ) reset: clearing Redis and Kafka"
compose exec -T redis redis-cli FLUSHALL > /dev/null
for topic in social.events social.events.DLT; do
  compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --if-exists --topic "$topic"
done
for group in notify notify-dlt-capture; do
  compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --delete --group "$group" > /dev/null 2>&1 || true
done

echo "$(date -u +%FT%TZ) reset: starting services and reseeding"
compose up -d --wait --wait-timeout 600 "${APPS[@]}"   # returns once every service is healthy
compose run --rm --no-deps seed
echo "$(date -u +%FT%TZ) reset: done"
