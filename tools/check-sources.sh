#!/usr/bin/env bash
#
# Canary for the upstream sources the store lists are built from.
#
# None of these are supported APIs. BuyMe serves an undocumented JSON endpoint and HTZone
# embeds its merchant list in page markup; either can change without notice. This script is
# how that gets noticed deliberately, rather than by opening the app at a checkout counter
# and finding an empty store list.
#
# CI runs it before every refresh. A non-zero exit means the seed snapshots need
# regenerating, or a card type needs pointing at a different source.

set -uo pipefail

UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

# Attempts before declaring a source broken. A canary that fires on a dropped connection
# gets ignored, and an ignored canary is worse than none at all.
ATTEMPTS=3

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0

# fetch <url> <outfile> <pattern-that-must-appear>
# Retries until the response both arrives and looks like what we expect.
fetch() {
  url="$1"; out="$2"; needle="$3"
  for attempt in $(seq 1 "$ATTEMPTS"); do
    if curl -sS --fail --max-time 120 -H "User-Agent: $UA" "$url" -o "$out" 2>/dev/null; then
      if grep -q "$needle" "$out" 2>/dev/null; then
        return 0
      fi
    fi
    [ "$attempt" -lt "$ATTEMPTS" ] && sleep 3
  done
  return 1
}

echo "--- BuyMe (siteapi/brands) ---"

# id : name : minimum brands expected
SUPPLIERS=(
  "13438757:BUYME ALL:1000"
  "17574075:BUYME TOGETHER:300"
  "7565407:BUYME STYLE:150"
)

for entry in "${SUPPLIERS[@]}"; do
  id="${entry%%:*}"; rest="${entry#*:}"; name="${rest%%:*}"; floor="${rest##*:}"
  out="$WORK/buyme-$id.json"

  if ! fetch "https://buyme.co.il/siteapi/brands/$id" "$out" '"searchTerms"'; then
    # An HTML body here means Cloudflare served a challenge instead of JSON.
    if grep -qi "<!DOCTYPE html" "$out" 2>/dev/null; then
      echo "FAIL  $name ($id): got HTML, not JSON — bot protection tightened"
    else
      echo "FAIL  $name ($id): no usable response after $ATTEMPTS attempts"
    fi
    fail=1
    continue
  fi

  count=$(grep -o '"searchTerms"' "$out" | wc -l | tr -d ' ')
  if [ "$count" -lt "$floor" ]; then
    echo "FAIL  $name ($id): only $count brands, expected at least $floor"
    fail=1
  else
    echo "ok    $name ($id): $count brands"
  fi
done

echo "--- HTZone (business_arr in page markup) ---"

# voucher-zone id : name : minimum entries expected
ZONES=(
  "10:All-In Zone:600"
  "6:SuperZone:600"
  "4:GiftZone:600"
  "1:ChefZone:60"
  "3:SpaZone:15"
)

for entry in "${ZONES[@]}"; do
  id="${entry%%:*}"; rest="${entry#*:}"; name="${rest%%:*}"; floor="${rest##*:}"
  out="$WORK/zone-$id.html"

  if ! fetch "https://www.htzone.co.il/voucher-zone/$id" "$out" 'business_arr'; then
    echo "FAIL  $name (voucher-zone/$id): no business_arr after $ATTEMPTS attempts — markup changed"
    fail=1
    continue
  fi

  # Entries repeat once per filter they match, so this over-counts: it is a floor, not a total.
  count=$(grep -o '"is_active"' "$out" | wc -l | tr -d ' ')
  if [ "$count" -lt "$floor" ]; then
    echo "FAIL  $name (voucher-zone/$id): only $count entries, expected at least $floor"
    fail=1
  else
    echo "ok    $name (voucher-zone/$id): $count entries"
  fi
done

if [ $fail -ne 0 ]; then
  cat <<'MSG'

An upstream source has changed shape.

Nothing is broken for users yet. The app falls back to the last published lists, then to
the snapshots compiled into the APK, so it keeps working on older data. What is lost is
freshness, until the source is repaired.

If the endpoint merely moved or changed its JSON shape, that is a catalog edit and needs no
app release. If it stopped serving machine-readable data altogether, it needs a new provider
in the app.
MSG
  exit 1
fi

echo ""
echo "All sources healthy."
