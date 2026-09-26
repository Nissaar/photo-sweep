#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Nissaar
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Exercises the app against a real Nextcloud server: installs it, indexes a library,
# walks every endpoint, deletes and restores files in both modes, and insists the
# server's log stays clean throughout.
#
# The two worst bugs this app has had — a keep verdict that could never be saved, and
# a restored photo that never came back to the index — both passed the unit tests and
# static analysis. Only this catches them.
#
#   Usage: tests/integration/acceptance.sh [nextcloud-version] [port]
#
# Set DOCKER=sudo\ docker where the daemon needs it.

set -uo pipefail

VERSION="${1:-31}"
PORT="${2:-8080}"
CT="photosweep-accept-$VERSION"
DOCKER="${DOCKER:-docker}"
ADMIN_PASS="acceptance-pass-123"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

B="http://localhost:$PORT/ocs/v2.php/apps/photosweep/api/v1"
A=(-u "admin:$ADMIN_PASS" -H OCS-APIRequest:true -H Accept:application/json -H Content-Type:application/json -s)

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); printf "  \033[32mPASS\033[0m %s\n" "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf "  \033[31mFAIL\033[0m %s\n" "$1"; }
step() { printf "\n\033[1m%s\033[0m\n" "$1"; }
check() { [ "$2" = "$3" ] && ok "$1 ($3)" || bad "$1 — expected [$3], got [$2]"; }

occ()  { $DOCKER exec -u www-data "$CT" php occ "$@" 2>&1; }
data() { python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['ocs']['data']))"; }
meta() { python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['meta']['statuscode'])"; }
sql()  { $DOCKER exec "$CT" php -r "\$d=new PDO('sqlite:/var/www/html/data/nextcloud.db'); echo \$d->query(\"$1\")->fetchColumn();"; }

cleanup() { $DOCKER rm -f "$CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

step "0. Start a clean Nextcloud $VERSION"
cleanup
$DOCKER run -d --name "$CT" -p "$PORT:80" \
  -e SQLITE_DATABASE=nextcloud \
  -e NEXTCLOUD_ADMIN_USER=admin \
  -e NEXTCLOUD_ADMIN_PASSWORD="$ADMIN_PASS" \
  -e NEXTCLOUD_TRUSTED_DOMAINS=localhost \
  "nextcloud:$VERSION-apache" >/dev/null || { echo "could not start the container"; exit 1; }

printf "  waiting for the installer"
for _ in $(seq 1 120); do
  if curl -s -m 5 "http://localhost:$PORT/status.php" 2>/dev/null | grep -q '"installed":true'; then break; fi
  printf "."
  sleep 5
done
echo
SERVER=$(curl -s "http://localhost:$PORT/status.php" | python3 -c "import json,sys; print(json.load(sys.stdin)['versionstring'])" 2>/dev/null)
[ -n "$SERVER" ] && ok "Nextcloud $SERVER is up" || { bad "the server never came up"; exit 1; }

step "1. Install the app"
$DOCKER exec "$CT" rm -rf /var/www/html/custom_apps/photosweep
$DOCKER cp "$ROOT/build/photosweep" "$CT":/var/www/html/custom_apps/photosweep >/dev/null
$DOCKER exec "$CT" chown -R www-data:www-data /var/www/html/custom_apps/photosweep
OUT=$(occ app:enable photosweep)
echo "$OUT" | grep -q enabled && ok "app:enable" || bad "app:enable — $OUT"

# Dropping an app into a server that is already running can leave OCS routes cached
# without it, and every API call then answers 998 "invalid query". A real install goes
# through Nextcloud itself and does not hit this, but the harness has to be
# deterministic, so bounce the container and let the route table rebuild.
bounce() {
  $DOCKER restart "$CT" >/dev/null
  for _ in $(seq 1 60); do
    curl -s -m 5 "http://localhost:$PORT/status.php" 2>/dev/null | grep -q '"installed":true' && break
    sleep 3
  done
  curl -s -m 5 "http://localhost:$PORT/status.php" | grep -q '"installed":true' \
    && ok "server came back after the restart" || bad "the server did not come back"
}
bounce

# Everything after this point must leave the log clean.
$DOCKER exec "$CT" sh -c ': > /var/www/html/data/nextcloud.log'

step "2. The migration created its tables"
TBLS=$(sql "SELECT group_concat(name, ' ') FROM (SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'oc_photosweep%' ORDER BY name)")
check "tables" "$TBLS" "oc_photosweep_decisions oc_photosweep_media oc_photosweep_scans"

step "3. Put a test library in place"
FIXTURES=$(mktemp -d)
python3 "$ROOT/tests/integration/fixtures.py" "$FIXTURES" >/dev/null
$DOCKER exec -u www-data "$CT" mkdir -p /var/www/html/data/admin/files/Photos
for f in "$FIXTURES"/*; do
  $DOCKER cp "$f" "$CT:/var/www/html/data/admin/files/Photos/$(basename "$f")" >/dev/null
done
rm -rf "$FIXTURES"
$DOCKER exec "$CT" chown -R www-data:www-data /var/www/html/data/admin/files/Photos
occ files:scan admin >/dev/null && ok "files:scan"

step "4. Index the library"
OUT=$(occ photosweep:index admin --until-complete)
echo "$OUT" | grep -q "index complete" && ok "occ photosweep:index" || bad "index — $OUT"

step "5. Each date came from the strategy it should have"
dated() {
  GOT=$(sql "SELECT year_month||' '||date_source FROM oc_photosweep_media WHERE name='$1'")
  check "$1" "$GOT" "$2"
}
NOW_MONTH=$(date +%Y-%m)
dated "IMG_20240712_140325.jpg"        "2024-07 filename"
dated "PXL_20230815_143022123.jpg"     "2023-08 filename"
dated "Screenshot_20240103-091500.jpg" "2024-01 filename"
dated "2022-05-19-08-30-00.jpg"        "2022-05 filename"
dated "Vineyard.jpg"                   "2018-05 exif"
dated "holiday photo.jpg"              "$NOW_MONTH mtime"
# The traps. PHP turns 20241312 into January 2025 unless it is stopped, and an
# eight-digit serial parses just as happily — either would invent a phantom month.
dated "20241312.jpg"                   "$NOW_MONTH mtime"
dated "84021599.jpg"                   "$NOW_MONTH mtime"

step "6. Read endpoints"
curl "${A[@]}" "$B/index" | grep -qE '"complete": *true' && ok "GET /index reports a complete scan" || bad "GET /index"
MN=$(curl "${A[@]}" "$B/months" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['ocs']['data']['months']))")
[ "$MN" -ge 8 ] && ok "GET /months returned $MN months" || bad "GET /months returned only $MN"
FID=$(curl "${A[@]}" "$B/months/2024-07" | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['items'][0]['fileId'])")
[ -n "$FID" ] && ok "GET /months/2024-07 -> fileId $FID" || bad "GET /months/2024-07"

step "7. Verdicts"
KEEPID=$(curl "${A[@]}" "$B/months/2018-05" | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['items'][0]['fileId'])")
# A keep is the case that used to insert a null verdict and be rejected, while a
# delete inserted cleanly — so half the app looked fine.
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$KEEPID,\"verdict\":\"keep\"}" | grep -qE '"verdict": *"keep"' \
  && ok "record a keep" || bad "record a keep"
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$FID,\"verdict\":\"delete\"}" | grep -qE '"verdict": *"delete"' \
  && ok "record a delete" || bad "record a delete"

read -r B1 B2 <<<"$(curl "${A[@]}" "$B/months/2012-06" | python3 -c "
import json,sys
print(' '.join(str(i['fileId']) for i in json.load(sys.stdin)['ocs']['data']['items'][:2]))")"
curl "${A[@]}" -X POST "$B/decisions" \
  -d "{\"verdicts\":[{\"fileId\":$B1,\"verdict\":\"keep\"},{\"fileId\":$B2,\"verdict\":\"delete\"}]}" \
  | grep -qE '"recorded": *2' && ok "batch record (the phone's offline queue)" || bad "batch record"

P=$(curl "${A[@]}" "$B/decisions/pending" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['ocs']['data']['decisions']))")
check "pending deletes" "$P" "2"

step "8. Undo a pending verdict"
curl "${A[@]}" -X DELETE "$B/decisions/$B2" >/dev/null
P=$(curl "${A[@]}" "$B/decisions/pending" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['ocs']['data']['decisions']))")
check "pending after undo" "$P" "1"
# Progress is counted by joining verdicts to the index, which only a real database
# exercises: the keep is counted, the undone delete is not.
REV=$(curl "${A[@]}" "$B/months" | python3 -c "
import json,sys
print([m['reviewed'] for m in json.load(sys.stdin)['ocs']['data']['months'] if m['month'] == '2012-06'][0])")
check "reviewed in 2012-06" "$REV" "1"

step "9. Trash mode: apply, then restore"
curl "${A[@]}" -X POST "$B/apply" -d '{}' | grep -qE '"succeeded": *1' && ok "apply moved one file" || bad "apply"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files/Photos/ | grep -q IMG_20240712 \
  && bad "the file is still in the library" || ok "the file left the library"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files_trashbin/files/ 2>/dev/null | grep -q IMG_20240712 \
  && ok "the file is in the trash" || bad "the file is not in the trash"
curl "${A[@]}" -X POST "$B/restore" -d "{\"fileIds\":[$FID]}" | grep -qE '"restored": *1' \
  && ok "restore reported success" || bad "restore"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files/Photos/ | grep -q IMG_20240712 \
  && ok "the file is back in the library" || bad "the file did not come back"
# It has to be reviewable again, not merely present on disk.
BACK=$(curl "${A[@]}" "$B/months/2024-07" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['ocs']['data']['items']))")
check "the restored photo is back in its month" "$BACK" "1"

step "10. Folder mode: apply, then restore"
curl "${A[@]}" -X PUT "$B/config" -d '{"mode":"folder","targetFolder":"/To Be Deleted"}' | grep -qE '"mode": *"folder"' \
  && ok "switched to folder mode" || bad "config update"
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$FID,\"verdict\":\"delete\"}" >/dev/null
curl "${A[@]}" -X POST "$B/apply" -d '{}' | grep -qE '"succeeded": *1' && ok "apply moved one file" || bad "apply (folder)"
$DOCKER exec "$CT" ls "/var/www/html/data/admin/files/To Be Deleted/" 2>/dev/null | grep -q IMG_20240712 \
  && ok "the file is in the collection folder" || bad "the file is not in the collection folder"
curl "${A[@]}" -X POST "$B/restore" -d "{\"fileIds\":[$FID]}" | grep -qE '"restored": *1' \
  && ok "restore from the folder" || bad "restore (folder)"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files/Photos/ | grep -q IMG_20240712 \
  && ok "the file is back where it was" || bad "the file did not come back"

step "11. The collection folder stays out of the index"
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$FID,\"verdict\":\"delete\"}" >/dev/null
curl "${A[@]}" -X POST "$B/apply" -d '{}' >/dev/null
occ photosweep:index admin --full --until-complete >/dev/null
IN=$(sql "SELECT COUNT(*) FROM oc_photosweep_media WHERE path LIKE '/To Be Deleted%'")
check "indexed rows under the collection folder" "$IN" "0"
# Changing the setting must not turn every photo already collected into a "restore".
TF=$(curl "${A[@]}" -X PUT "$B/config" -d '{"targetFolder":"/Later"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['targetFolder'])")
check "changed the collection folder" "$TF" "/Later"
KEPT=$(curl "${A[@]}" "$B/decisions/applied" | python3 -c "import json,sys; print(sum(1 for d in json.load(sys.stdin)['ocs']['data']['decisions'] if d['fileId'] == $FID))")
check "the collected photo keeps its undo" "$KEPT" "1"
occ photosweep:index admin --full --until-complete >/dev/null
IN=$(sql "SELECT COUNT(*) FROM oc_photosweep_media WHERE path LIKE '/To Be Deleted%'")
check "the old collection folder stays out of the index" "$IN" "0"

step "12. Bad input is refused"
check "GET /months/2024-13"        "$(curl "${A[@]}" "$B/months/2024-13" | meta)" "400"
check "record an unknown fileId"   "$(curl "${A[@]}" -X POST "$B/decisions" -d '{"fileId":999999,"verdict":"delete"}' | meta)" "404"
check "record an invalid verdict"  "$(curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$FID,\"verdict\":\"maybe\"}" | meta)" "400"
check "set an invalid mode"        "$(curl "${A[@]}" -X PUT "$B/config" -d '{"mode":"nonsense"}' | meta)" "400"
check "unauthenticated request"    "$(curl -s -o /dev/null -w '%{http_code}' -H OCS-APIRequest:true "$B/months")" "401"
check "a folder path with .."      "$(curl "${A[@]}" -X PUT "$B/config" -d '{"targetFolder":"/Photos/../Documents"}' | meta)" "400"
check "collect into the library"   "$(curl "${A[@]}" -X PUT "$B/config" -d '{"targetFolder":"/","sourceFolder":"/"}' | meta)" "400"

step "13. Apply acts only on the photos that were confirmed"
first_id() { curl "${A[@]}" "$B/months/$1" | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['items'][0]['fileId'])"; }
C1=$(first_id 2023-08)
C2=$(first_id 2024-01)
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$C1,\"verdict\":\"delete\"}" >/dev/null
curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$C2,\"verdict\":\"delete\"}" >/dev/null
# The second verdict stands for one given on another device after this list loaded.
curl "${A[@]}" -X POST "$B/apply" -d "{\"fileIds\":[$C1]}" | grep -qE '"succeeded": *1' \
  && ok "apply with a list carried out one" || bad "apply with a list"
LEFT=$(curl "${A[@]}" "$B/decisions/pending" | python3 -c "import json,sys; print(' '.join(str(d['fileId']) for d in json.load(sys.stdin)['ocs']['data']['decisions']))")
check "the verdict not in the list is still pending" "$LEFT" "$C2"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files/Photos/ | grep -q Screenshot_20240103 \
  && ok "the photo not in the list was not touched" || bad "the photo not in the list was moved"

step "14. With the trash off, a permanent delete needs a yes"
occ app:disable files_trashbin >/dev/null
# Newer servers keep the list of enabled apps in the web server's memory cache, which
# occ cannot reach, so the web side goes on believing the trash is there until it
# restarts.
bounce
TA=$(curl "${A[@]}" "$B/index" | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['trashAvailable'])")
check "the server reports the trash as off" "$TA" "False"
curl "${A[@]}" -X PUT "$B/config" -d '{"mode":"trash"}' >/dev/null
check "apply is refused" "$(curl "${A[@]}" -o /dev/null -w '%{http_code}' -X POST "$B/apply" -d '{}')" "409"
ERR=$(curl "${A[@]}" -X POST "$B/apply" -d '{}' | python3 -c "import json,sys; print(json.load(sys.stdin)['ocs']['data']['error'])")
check "the refusal says why" "$ERR" "trash_unavailable"
$DOCKER exec "$CT" ls /var/www/html/data/admin/files/Photos/ | grep -q Screenshot_20240103 \
  && ok "nothing was deleted" || bad "the photo was deleted anyway"
curl "${A[@]}" -X DELETE "$B/decisions/$C2" >/dev/null
occ app:enable files_trashbin >/dev/null
bounce

step "15. A second user, and a share between them"
BOB_PASS="acceptance-bob-456"
BA=(-u "bob:$BOB_PASS" -H OCS-APIRequest:true -H Accept:application/json -H Content-Type:application/json -s)
$DOCKER exec -u www-data -e OC_PASS="$BOB_PASS" "$CT" php occ user:add --password-from-env bob >/dev/null 2>&1 \
  && ok "created bob" || bad "could not create bob"
# The first request sets up bob's home.
curl "${BA[@]}" "$B/index" >/dev/null
FIXTURES=$(mktemp -d)
python3 "$ROOT/tests/integration/fixtures.py" "$FIXTURES" >/dev/null
$DOCKER exec -u www-data "$CT" mkdir -p /var/www/html/data/bob/files/Holiday
$DOCKER cp "$FIXTURES/IMG_20240712_140325.jpg" "$CT:/var/www/html/data/bob/files/Holiday/IMG_20240712_140325.jpg" >/dev/null
rm -rf "$FIXTURES"
$DOCKER exec "$CT" chown -R www-data:www-data /var/www/html/data/bob/files/Holiday
occ files:scan bob >/dev/null
SHARE=$(curl -s -u "bob:$BOB_PASS" -H OCS-APIRequest:true -H Accept:application/json \
  -X POST "http://localhost:$PORT/ocs/v2.php/apps/files_sharing/api/v1/shares" \
  -d path=/Holiday -d shareType=0 -d shareWith=admin -d permissions=31 | meta)
check "bob shares /Holiday with admin, with delete rights" "$SHARE" "200"

occ photosweep:index bob --until-complete >/dev/null
BOBFID=$(sql "SELECT file_id FROM oc_photosweep_media WHERE user_id='bob' AND path='/Holiday/IMG_20240712_140325.jpg'")
[ -n "$BOBFID" ] && ok "bob's photo is in bob's index ($BOBFID)" || bad "bob's photo is not in bob's index"

occ photosweep:index admin --full --until-complete >/dev/null
IN=$(sql "SELECT COUNT(*) FROM oc_photosweep_media WHERE user_id='admin' AND path LIKE '/Holiday%'")
check "admin's index holds none of bob's shared photos" "$IN" "0"

check "admin cannot mark bob's photo" \
  "$(curl "${A[@]}" -X POST "$B/decisions" -d "{\"fileId\":$BOBFID,\"verdict\":\"delete\"}" | meta)" "404"
curl "${A[@]}" -X POST "$B/apply" -d "{\"fileIds\":[$BOBFID]}" | grep -qE '"succeeded": *0' \
  && ok "admin naming bob's photo in an apply does nothing" || bad "admin's apply acted on bob's photo"

curl "${BA[@]}" -X POST "$B/decisions" -d "{\"fileId\":$BOBFID,\"verdict\":\"delete\"}" | grep -qE '"verdict": *"delete"' \
  && ok "bob marks his own photo" || bad "bob could not mark his own photo"
AP=$(curl "${A[@]}" "$B/decisions/pending" | python3 -c "import json,sys; print(sum(1 for d in json.load(sys.stdin)['ocs']['data']['decisions'] if d['fileId'] == $BOBFID))")
check "bob's verdict is not in admin's pending list" "$AP" "0"
curl "${A[@]}" -X POST "$B/apply" -d '{}' >/dev/null
$DOCKER exec "$CT" ls /var/www/html/data/bob/files/Holiday/ | grep -q IMG_20240712 \
  && ok "admin's apply left bob's photo alone" || bad "admin's apply deleted bob's photo"
BP=$(curl "${BA[@]}" "$B/decisions/pending" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['ocs']['data']['decisions']))")
check "bob's own pending list" "$BP" "1"

step "16. The background job runs"
JOB=$(sql "SELECT id FROM oc_jobs WHERE class LIKE '%PhotoSweep%'")
if [ -n "$JOB" ]; then
  OUT=$(occ background-job:execute "$JOB" --force-execute)
  echo "$OUT" | grep -qiE "error|exception|fatal" && bad "background job — $OUT" || ok "background job executed"
else
  bad "the background job was never registered"
fi

step "17. The server's log is clean"
ERRS=$($DOCKER exec "$CT" cat /var/www/html/data/nextcloud.log 2>/dev/null | python3 -c "
import sys, json
count = 0
for line in sys.stdin:
    line = line.strip()
    if not line.startswith('{'):
        continue
    try:
        entry = json.loads(line)
    except ValueError:
        continue
    # 2 = warning, 3 = error, 4 = fatal. Anything at or above is a failure.
    if entry.get('level', 0) >= 2:
        count += 1
        print('   ', entry.get('level'), entry.get('app'), '|', str(entry.get('message'))[:140], file=sys.stderr)
print(count)
")
check "warnings and errors in the log" "$ERRS" "0"

printf "\n\033[1m==== Nextcloud %s: %d passed, %d failed ====\033[0m\n" "$SERVER" "$PASS" "$FAIL"
exit $((FAIL > 0 ? 1 : 0))
