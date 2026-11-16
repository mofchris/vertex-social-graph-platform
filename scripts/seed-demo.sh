#!/usr/bin/env bash
#
# Seeds demo data for Vertex by calling the services' real APIs, so every row is created the same
# way a user would create it (follows go through Graph's outbox and Kafka to Notify, posts fan out
# in Feed, and so on).
#
# Creates 80 fictional users (3 "celebrities" with enough followers to cross Feed's fan-out
# threshold), a random follow graph, some friendships, and posts. Posts are created after the follow
# graph exists so fan-out fills timelines.
#
# Every demo user signs in with the password in $PASSWORD (default: vertex-demo), e.g.
#   avery.okafor@vertex.demo / vertex-demo
#
# docker compose runs this automatically (the `seed` service). To run it by hand against services
# on localhost:8080-8085:   bash scripts/seed-demo.sh
set -euo pipefail

ID=${ID_URL:-http://localhost:8080}
PR=${PROFILE_URL:-http://localhost:8081}
GR=${GRAPH_URL:-http://localhost:8082}
FE=${FEED_URL:-http://localhost:8083}
NO=${NOTIFY_URL:-http://localhost:8084}
RE=${RECOMMEND_URL:-http://localhost:8085}

PASSWORD=${PASSWORD:-vertex-demo}
COUNT=${COUNT:-80}
CELEBS=3                 # users 0,1,2 are celebrities
CELEB_FOLLOWERS=30       # followers per celebrity (> Feed's threshold of 20)
FOLLOWS_PER_USER=5       # random follows each user makes
FRIEND_PAIRS=30

# Fictional names. First and last lists have co-prime-ish lengths (28 and 20), so the first 140
# combinations are unique.
FIRST=(Avery Bailey Casey Devon Ellis Finley Gray Harper Indie Jordan Kai Lane Max Noor \
       Quinn Riley Sage Tatum River Sky Reese Rowan Emerson Hayden Parker Drew Marlowe Phoenix)
LAST=(Okafor Lindqvist Moreau Tanaka Haddad Kowalski Ferreira Nakamura Osei Brennan \
      Varga Castillo Achebe Holm Rinaldi Sato Mensah Novak Duarte Iwu)
BIOS=("building things" "coffee, then code" "here for the people" "learning distributed systems" \
      "weekend hiker" "reads too many books" "say hi" "design and type nerd" "always sketching" "night owl")
POSTS=(
  "Morning, everyone." "Trying Vertex out today." "Anyone else reading about distributed systems?"
  "Coffee first, then code." "Weekend plan: a long walk and a longer book." "Finally fixed that flaky test."
  "What is everyone listening to this week?" "Learning how Kafka partitions work." "Sketching a new side project."
  "Good night from the other side of the world."
)

first_of() { echo "${FIRST[$(($1 % ${#FIRST[@]}))]}"; }
last_of()  { echo "${LAST[$(($1 % ${#LAST[@]}))]}"; }
email_of() { local f l; f=$(first_of "$1"); l=$(last_of "$1"); echo "${f,,}.${l,,}@vertex.demo"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
user_id() { sed -n 's/.*"user":{"id":"\([^"]*\)".*/\1/p'; }

login() {
  curl -s -X POST "$ID/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$(email_of "$1")\",\"password\":\"$PASSWORD\"}"
}

if [ "${SKIP_IF_SEEDED:-0}" = "1" ] && [ -n "$(login 0 | field accessToken)" ]; then
  echo "Demo data already present ($(email_of 0) can sign in); nothing to do."
  exit 0
fi

declare -a TOK
declare -a USERID

echo "==> Creating $COUNT users ($ID)"
for i in $(seq 0 $((COUNT - 1))); do
  f=$(first_of "$i"); l=$(last_of "$i")
  resp=$(curl -s -X POST "$ID/v1/auth/signup" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$(email_of "$i")\",\"username\":\"${f,,}_${l,,}\",\"password\":\"$PASSWORD\",\"displayName\":\"$f $l\"}")
  TOK[$i]=$(printf '%s' "$resp" | field accessToken)
  USERID[$i]=$(printf '%s' "$resp" | user_id)
  if [ -z "${USERID[$i]}" ]; then
    resp=$(login "$i")   # already exists (re-run): sign in instead
    TOK[$i]=$(printf '%s' "$resp" | field accessToken)
    USERID[$i]=$(printf '%s' "$resp" | user_id)
  fi
  [ -z "${USERID[$i]}" ] && { echo "signup/login failed for user $i: $resp"; exit 1; }
done
echo "    done"

echo "==> Setting profiles ($PR)"
for i in $(seq 0 $((COUNT - 1))); do
  vis=PUBLIC
  [ $((i % 5)) -eq 0 ] && vis=FRIENDS
  [ $((i % 11)) -eq 0 ] && vis=PRIVATE
  [ "$i" -lt "$CELEBS" ] && vis=PUBLIC   # celebrities are the first faces a new visitor sees
  curl -s -o /dev/null -X PUT "$PR/v1/me/profile" -H "Authorization: Bearer ${TOK[$i]}" \
    -H 'Content-Type: application/json' \
    -d "{\"displayName\":\"$(first_of "$i") $(last_of "$i")\",\"bio\":\"${BIOS[$((i % ${#BIOS[@]}))]}\",\"visibility\":\"$vis\"}"
done
echo "    done"

echo "==> Building the follow graph ($GR)"
# Followers for each celebrity, so they cross Feed's fan-out threshold.
for c in $(seq 0 $((CELEBS - 1))); do
  for i in $(seq $CELEBS $((CELEBS + CELEB_FOLLOWERS - 1))); do
    [ "$i" -ge "$COUNT" ] && break
    curl -s -o /dev/null -X POST "$GR/v1/follow/${USERID[$c]}" -H "Authorization: Bearer ${TOK[$i]}"
  done
done
# Random follows between everyone.
for i in $(seq 0 $((COUNT - 1))); do
  for _ in $(seq 1 $FOLLOWS_PER_USER); do
    j=$((RANDOM % COUNT))
    [ "$j" -ne "$i" ] && curl -s -o /dev/null -X POST "$GR/v1/follow/${USERID[$j]}" -H "Authorization: Bearer ${TOK[$i]}"
  done
done
echo "    done"

echo "==> Creating up to $FRIEND_PAIRS friendships"
for _ in $(seq 1 $FRIEND_PAIRS); do
  a=$((RANDOM % COUNT)); b=$((RANDOM % COUNT))
  [ "$a" -eq "$b" ] && continue
  curl -s -o /dev/null -X POST "$GR/v1/friends/${USERID[$b]}/request" -H "Authorization: Bearer ${TOK[$a]}"
  curl -s -o /dev/null -X POST "$GR/v1/friends/${USERID[$a]}/accept"  -H "Authorization: Bearer ${TOK[$b]}"
done
echo "    done"

echo "==> Posting; fan-out happens now ($FE)"
for i in $(seq 0 $((COUNT - 1))); do
  n=2; [ "$i" -lt "$CELEBS" ] && n=4   # celebrities post more
  for _ in $(seq 1 $n); do
    msg="${POSTS[$((RANDOM % ${#POSTS[@]}))]}"
    curl -s -o /dev/null -X POST "$FE/v1/posts" -H "Authorization: Bearer ${TOK[$i]}" \
      -H 'Content-Type: application/json' -d "{\"content\":\"$msg\"}"
  done
done
echo "    done"

echo
echo "==> Summary"
echo "$(email_of 0) counts:        $(curl -s "$GR/v1/counts/${USERID[0]}" -H "Authorization: Bearer ${TOK[0]}")"
echo "$(email_of 0) notifications: $(curl -s "$NO/v1/notifications?limit=1" -H "Authorization: Bearer ${TOK[0]}" | head -c 240)"
echo "$(email_of 40) feed:         $(curl -s "$FE/v1/feed?limit=2" -H "Authorization: Bearer ${TOK[40]}" | head -c 240)"
echo "$(email_of 40) suggestions:  $(curl -s "$RE/v1/recommendations?limit=3" -H "Authorization: Bearer ${TOK[40]}" | head -c 240)"
echo
echo "Seed complete. Sign in as any demo user, e.g. $(email_of 5) / $PASSWORD"
