#!/usr/bin/env bash
# Seeds demo users, follows and posts through the public API (exercises the real upload pipeline).
#   ./scripts/seed.sh [apiBaseUrl]      default http://localhost:8080
# Log in as "demo" / "password123" afterwards.
set -euo pipefail
API="${1:-http://localhost:8080}/api"
IMG="$(cd "$(dirname "$0")/.." && pwd)/postman/fixtures/sample.jpg"
SIZE=$(wc -c < "$IMG" | tr -d ' ')
json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

token_for() { # register, or log in if the user already exists
  local u=$1 name=$2 body
  body="{\"username\":\"$u\",\"email\":\"$u@example.com\",\"password\":\"password123\",\"displayName\":\"$name\"}"
  curl -sf -X POST "$API/auth/register" -H 'Content-Type: application/json' -d "$body" 2>/dev/null | json '["token"]' \
    || curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' \
         -d "{\"username\":\"$u\",\"password\":\"password123\"}" | json '["token"]'
}
id_of() { curl -sf "$API/users/me" -H "Authorization: Bearer $1" | json '["userId"]'; }

post() { # token caption
  local init url mid
  init=$(curl -sf -X POST "$API/media/uploads" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"contentType\":\"image/jpeg\",\"sizeBytes\":$SIZE}")
  url=$(echo "$init" | json '["uploadUrl"]'); mid=$(echo "$init" | json '["mediaId"]')
  curl -sf -X PUT -H 'Content-Type: image/jpeg' --data-binary @"$IMG" "$url" >/dev/null
  curl -sf -X POST "$API/posts" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"caption\":\"$2\",\"mediaIds\":[\"$mid\"]}" | json '["status"]'
}

echo "Creating users…"
DEMO=$(token_for demo "Demo User")
MAYA=$(token_for maya "Maya Okafor");   MAYA_ID=$(id_of "$MAYA")
THEO=$(token_for theo "Theo Lindqvist"); THEO_ID=$(id_of "$THEO")

for t in "$DEMO"; do
  for f in "$MAYA_ID" "$THEO_ID"; do
    curl -sf -X POST "$API/follows" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "{\"followeeId\":\"$f\"}" >/dev/null
  done
done

echo "Posting…"
post "$MAYA" "First light over the harbour. The fog burned off by nine."
post "$THEO" "Test strip from the darkroom, third try at the exposure."
post "$MAYA" "Blue hour, no filter."
echo "Done. Log in as demo / password123"
