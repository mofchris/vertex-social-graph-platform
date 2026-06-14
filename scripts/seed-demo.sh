#!/usr/bin/env bash
#
# Seeds demo data for the Vertex platform across all four services.
#
# Creates ~80 users (3 of them "celebrities" with enough followers to trip the Feed
# fan-out threshold), a random follow graph, some friendships, and posts. Posts are
# created AFTER the follow graph exists so fan-out populates timelines.
#
# Prerequisites: identity (8080), profile (8081), graph (8082), feed (8083), notify (8084),
# recommend (8085) running with the SAME APP_JWT_SECRET (the dev default works). Run each
# service with `./mvnw spring-boot:run` (embedded H2) or via docker-compose.
#
# Usage:  bash scripts/seed-demo.sh
set -euo pipefail

ID=${ID_URL:-http://localhost:8080}
PR=${PROFILE_URL:-http://localhost:8081}
GR=${GRAPH_URL:-http://localhost:8082}
FE=${FEED_URL:-http://localhost:8083}
NO=${NOTIFY_URL:-http://localhost:8084}
RE=${RECOMMEND_URL:-http://localhost:8085}

COUNT=${COUNT:-80}
CELEBS=3                 # users 0,1,2 are celebrities
CELEB_FOLLOWERS=30       # followers per celebrity (> Feed's threshold of 20)
FOLLOWS_PER_USER=5       # random follows each user makes
FRIEND_PAIRS=30

NAMES=(Avery Bailey Casey Devon Ellis Finley Gray Harper Indie Jordan Kai Lane Max Noor \
       Quinn Riley Sage Tatum River Sky Reese Rowan Emerson Hayden Parker Drew Marlowe Phoenix)
BIOS=("building things" "coffee + code" "here for the memes" "distributed systems nerd" \
      "ship it" "professional lurker" "say hi" "opinions my own" "touch grass" "wagmi")

field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

declare -a TOK
declare -a USERID

echo "==> Creating $COUNT users on identity ($ID)"
for i in $(seq 0 $((COUNT - 1))); do
  resp=$(curl -s -X POST "$ID/v1/auth/signup" -H 'Content-Type: application/json' \
    -d "{\"email\":\"demo$i@vertex.local\",\"username\":\"demo_user$i\",\"password\":\"password123\",\"displayName\":\"${NAMES[$((i % ${#NAMES[@]}))]} $i\"}")
  TOK[$i]=$(printf '%s' "$resp" | field accessToken)
  USERID[$i]=$(printf '%s' "$resp" | sed -n 's/.*"user":{"id":"\([^"]*\)".*/\1/p')
  if [ -z "${USERID[$i]}" ]; then
    # Already exists (re-run): log in instead.
    resp=$(curl -s -X POST "$ID/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"demo$i@vertex.local\",\"password\":\"password123\"}")
    TOK[$i]=$(printf '%s' "$resp" | field accessToken)
    USERID[$i]=$(printf '%s' "$resp" | sed -n 's/.*"user":{"id":"\([^"]*\)".*/\1/p')
  fi
  [ -z "${USERID[$i]}" ] && { echo "signup/login failed for user $i: $resp"; exit 1; }
done
echo "    done ($COUNT users)"

echo "==> Setting profiles ($PR)"
for i in $(seq 0 $((COUNT - 1))); do
  vis=PUBLIC
  [ $((i % 5)) -eq 0 ] && vis=FRIENDS
  [ $((i % 11)) -eq 0 ] && vis=PRIVATE
  curl -s -o /dev/null -X PUT "$PR/v1/me/profile" -H "Authorization: Bearer ${TOK[$i]}" \
    -H 'Content-Type: application/json' \
    -d "{\"displayName\":\"${NAMES[$((i % ${#NAMES[@]}))]} $i\",\"bio\":\"${BIOS[$((i % ${#BIOS[@]}))]}\",\"visibility\":\"$vis\"}"
done
echo "    done"

echo "==> Building follow graph ($GR)"
# Everyone follows the celebrities (so they cross the fan-out threshold).
for c in $(seq 0 $((CELEBS - 1))); do
  for i in $(seq $CELEBS $((CELEBS + CELEB_FOLLOWERS - 1))); do
    [ "$i" -ge "$COUNT" ] && break
    curl -s -o /dev/null -X POST "$GR/v1/follow/${USERID[$c]}" -H "Authorization: Bearer ${TOK[$i]}"
  done
done
# Random follows between regular users.
for i in $(seq 0 $((COUNT - 1))); do
  for _ in $(seq 1 $FOLLOWS_PER_USER); do
    j=$((RANDOM % COUNT))
    [ "$j" -ne "$i" ] && curl -s -o /dev/null -X POST "$GR/v1/follow/${USERID[$j]}" -H "Authorization: Bearer ${TOK[$i]}"
  done
done
echo "    done"

echo "==> Creating $FRIEND_PAIRS friendships"
for p in $(seq 1 $FRIEND_PAIRS); do
  a=$((RANDOM % COUNT)); b=$((RANDOM % COUNT))
  [ "$a" -eq "$b" ] && continue
  curl -s -o /dev/null -X POST "$GR/v1/friends/${USERID[$b]}/request" -H "Authorization: Bearer ${TOK[$a]}"
  curl -s -o /dev/null -X POST "$GR/v1/friends/${USERID[$a]}/accept"  -H "Authorization: Bearer ${TOK[$b]}"
done
echo "    done"

echo "==> Posting (fan-out happens now) ($FE)"
POSTS=(
  "gm" "shipping a new feature today" "anyone else debugging prod right now"
  "hot take: tabs > spaces" "the social graph is harder than it looks"
  "p99 latency down 30%, feeling good" "weekend project incoming"
  "just hit a million edges" "caching fixed everything" "rewrote it in Rust (kidding)"
)
for i in $(seq 0 $((COUNT - 1))); do
  n=2; [ "$i" -lt "$CELEBS" ] && n=4   # celebrities post more
  for k in $(seq 1 $n); do
    msg="${POSTS[$((RANDOM % ${#POSTS[@]}))]}"
    curl -s -o /dev/null -X POST "$FE/v1/posts" -H "Authorization: Bearer ${TOK[$i]}" \
      -H 'Content-Type: application/json' -d "{\"content\":\"$msg\"}"
  done
done
echo "    done"

echo "==> Generating notifications ($NO)"
# Each celebrity's followers emit a FOLLOW event -> one coalesced notification per celebrity.
for c in $(seq 0 $((CELEBS - 1))); do
  for i in $(seq $CELEBS $((CELEBS + CELEB_FOLLOWERS - 1))); do
    [ "$i" -ge "$COUNT" ] && break
    curl -s -o /dev/null -X POST "$NO/v1/events" -H "Authorization: Bearer ${TOK[$i]}" \
      -H 'Content-Type: application/json' \
      -d "{\"recipientId\":\"${USERID[$c]}\",\"type\":\"FOLLOW\"}"
  done
done
echo "    done"

echo
echo "==> Summary"
echo "celebrity demo_user0 counts:        $(curl -s "$GR/v1/counts/${USERID[0]}" -H "Authorization: Bearer ${TOK[0]}")"
echo "celebrity demo_user0 notifications: $(curl -s "$NO/v1/notifications" -H "Authorization: Bearer ${TOK[0]}" | head -c 240)"
echo "regular  demo_user40 feed:          $(curl -s "$FE/v1/feed?limit=5" -H "Authorization: Bearer ${TOK[40]}" | head -c 300)"
echo "regular  demo_user40 PYMK:          $(curl -s "$RE/v1/recommendations?limit=5" -H "Authorization: Bearer ${TOK[40]}" | head -c 240)"
echo
echo "Seed complete. Try:"
echo "  curl \"$FE/v1/feed\" -H \"Authorization: Bearer <token>\""
echo "  (tokens are ephemeral; re-run signup/login on identity to get a fresh one)"
