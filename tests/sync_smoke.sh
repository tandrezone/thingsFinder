#!/usr/bin/env bash
# Smoke test for the Android sync API (token auth + POST /api/sync).
#
#   php -S 127.0.0.1:8765 -t web router.php &   # with a fresh data/ dir and two users:
#   php -r 'require "includes/helpers.php"; require "includes/db.php";
#           $p = get_db(); create_user($p, "tiago", "secret123"); create_user($p, "other", "secret123");'
#   BASE=http://127.0.0.1:8765 tests/sync_smoke.sh
#
# Needs curl and jq. Exits non-zero on the first failed check.
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8765}"
pass=0
check() { # check "description" <actual> <expected>
  if [[ "$2" == "$3" ]]; then pass=$((pass + 1)); echo "  ok   $1"; else echo "  FAIL $1: got '$2', expected '$3'"; exit 1; fi
}
req() { # req METHOD PATH [TOKEN] [JSON] -> prints "status body" (body on following lines)
  local m="$1" p="$2" t="${3:-}" d="${4:-}"
  local args=(-s -o /tmp/tf_body -w '%{http_code}' -X "$m" "$BASE$p" -H 'Content-Type: application/json')
  [[ -n "$t" ]] && args+=(-H "Authorization: Bearer $t")
  [[ -n "$d" ]] && args+=(-d "$d")
  curl "${args[@]}"
}
body() { cat /tmp/tf_body; }

echo "auth"
check "login with wrong password is 401" "$(req POST /api/auth/login '' '{"username":"tiago","password":"nope"}')" 401
check "login without fields is 400" "$(req POST /api/auth/login '' '{}')" 400
check "login ok is 201" "$(req POST /api/auth/login '' '{"username":"tiago","password":"secret123","device_name":"Pixel 8"}')" 201
TOKEN=$(body | jq -r .token)
check "token is 64 hex chars" "$(echo -n "$TOKEN" | grep -cE '^[0-9a-f]{64}$')" 1
check "no token is 401" "$(req GET /api/me)" 401
check "bad token is 401" "$(req GET /api/me 0123456789abcdef0123456789abcdef)" 401
check "GET /api/me with token" "$(req GET /api/me "$TOKEN")" 200
check "me is tiago" "$(body | jq -r .user.username)" tiago
check "every JSON response parses" "$(body | jq -e . >/dev/null && echo yes)" yes
req POST /api/auth/login '' '{"username":"other","password":"secret123"}' >/dev/null
OTHER=$(body | jq -r .token)

echo "first sync pushes everything"
T0=1790000000000
PUSH=$(cat <<EOF
{"since":0,
 "places":[{"uuid":"p-garage-0001","name":"Garage","updated_at":$T0}],
 "boxes":[{"uuid":"b-bin-000001","place_uuid":"p-garage-0001","name":"Tools bin","share_token":"aaaabbbbccccdddd0000111122223333","updated_at":$T0}],
 "items":[{"uuid":"i-hammer-001","box_uuid":"b-bin-000001","name":"Hammer","quantity":1,"updated_at":$T0},
          {"uuid":"i-rake-00001","place_uuid":"p-garage-0001","name":"Rake","quantity":2,"updated_at":$T0},
          {"uuid":"i-orphan-001","box_uuid":"b-missing-01","name":"Lost","updated_at":$T0}],
 "deleted":[],
 "barcodes":[{"barcode":"4006381333931","name":"Glue gun"}]}
EOF
)
check "sync 200" "$(req POST /api/sync "$TOKEN" "$PUSH")" 200
CURSOR=$(body | jq .server_time)
check "3 changes echo places/boxes/items" "$(body | jq -r '[(.changes.places|length),(.changes.boxes|length),(.changes.items|length)]|map(tostring)|join(",")')" "1,1,2"
check "orphan item skipped" "$(body | jq -r '.skipped[0].reason')" "parent not found"
check "share token kept" "$(body | jq -r '.changes.boxes[0].share_token')" aaaabbbbccccdddd0000111122223333
check "loose item keeps place_uuid" "$(body | jq -r '.changes.items[] | select(.uuid=="i-rake-00001") | .place_uuid')" p-garage-0001
check "boxed item has no place_uuid" "$(body | jq -r '.changes.items[] | select(.uuid=="i-hammer-001") | .place_uuid')" null
check "barcode stored" "$(body | jq -r '.changes.barcodes[0].name')" "Glue gun"
check "data visible through the normal API" "$(req GET '/api/search?q=hammer' "$TOKEN")" 200
check "search finds Hammer in Tools bin" "$(body | jq -r '.results[0].box.name')" "Tools bin"

echo "incremental sync"
check "nothing new since cursor" "$(req POST /api/sync "$TOKEN" "{\"since\":$CURSOR}")" 200
check "no changes returned" "$(body | jq '(.changes.places+.changes.boxes+.changes.items)|length')" 0

echo "last write wins"
OLD=$((T0 - 1000))
req POST /api/sync "$TOKEN" "{\"since\":$CURSOR,\"items\":[{\"uuid\":\"i-hammer-001\",\"box_uuid\":\"b-bin-000001\",\"name\":\"Old name\",\"updated_at\":$OLD}]}" >/dev/null
check "older phone edit is ignored" "$(req GET '/api/search?q=hammer' "$TOKEN"; body | jq -r '.results|length')" "2001"
NEW=$((T0 + 1000))
req POST /api/sync "$TOKEN" "{\"since\":$CURSOR,\"items\":[{\"uuid\":\"i-hammer-001\",\"box_uuid\":\"b-bin-000001\",\"name\":\"Claw hammer\",\"quantity\":3,\"updated_at\":$NEW}]}" >/dev/null
check "newer phone edit applied" "$(body | jq -r '.changes.items[] | select(.uuid=="i-hammer-001") | "\(.name) x\(.quantity)"')" "Claw hammer x3"
CURSOR=$(body | jq .server_time)

echo "web edits and deletes come back down"
# Simulate the web UI: plain SQL without touching uuid/updated_at, exactly like index.php does.
php -r 'require "includes/helpers.php"; require "includes/db.php"; $p=get_db();
  $p->exec("UPDATE places SET name = '"'"'Big garage'"'"' WHERE uuid = '"'"'p-garage-0001'"'"'");
  $p->exec("DELETE FROM items WHERE uuid = '"'"'i-rake-00001'"'"'");
  $pid = (int)$p->query("SELECT id FROM places WHERE uuid = '"'"'p-garage-0001'"'"'")->fetchColumn();
  $p->prepare("INSERT INTO items (place_id, name, quantity) VALUES (?, ?, 1)")->execute([$pid, "Web shovel"]);'
check "sync after web edits" "$(req POST /api/sync "$TOKEN" "{\"since\":$CURSOR}")" 200
check "renamed place comes down" "$(body | jq -r '.changes.places[0].name')" "Big garage"
check "web-created item has a uuid" "$(body | jq -r '.changes.items[] | select(.name=="Web shovel") | (.uuid|length > 8)')" true
check "web delete arrives as tombstone" "$(body | jq -r '.changes.deleted[] | select(.uuid=="i-rake-00001") | .kind')" items
CURSOR=$(body | jq .server_time)

echo "phone deletes"
FUTURE=$(( $(date +%s) * 1000 ))
check "phone deletes the box" "$(req POST /api/sync "$TOKEN" "{\"since\":$CURSOR,\"deleted\":[{\"kind\":\"boxes\",\"uuid\":\"b-bin-000001\",\"deleted_at\":$FUTURE}]}")" 200
check "box and its item are gone" "$(req GET '/api/search?q=hammer' "$TOKEN"; body | jq '.results|length')" "2000"

echo "isolation between accounts"
check "other user's sync is 200" "$(req POST /api/sync "$OTHER" '{"since":0}')" 200
check "other user sees none of tiago's places" "$(body | jq '.changes.places|length')" 0
check "other user can't hijack a uuid" "$(req POST /api/sync "$OTHER" "{\"since\":0,\"places\":[{\"uuid\":\"p-garage-0001\",\"name\":\"Mine now\",\"updated_at\":$FUTURE}]}")" 200
check "hijack attempt skipped" "$(body | jq -r '.skipped[0].reason')" "uuid in use"
req GET /api/places "$TOKEN" >/dev/null
check "tiago's place untouched" "$(body | jq -r '.places[0].name')" "Big garage"

echo "validation & logout"
check "GET /api/sync is 405" "$(req GET /api/sync "$TOKEN")" 405
check "garbage body still 200 with no changes applied" "$(req POST /api/sync "$TOKEN" '{"places":"nope","items":[1,2,{"uuid":"x"}]}')" 200
check "logout" "$(req POST /api/auth/logout "$TOKEN")" 200
check "token revoked" "$(req GET /api/me "$TOKEN")" 401

echo "all $pass checks passed"
