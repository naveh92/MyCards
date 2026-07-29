#!/usr/bin/env bash
#
# Canary for the undocumented BuyMe endpoint.
#
# The merchant data comes from an endpoint nobody promised us. This script is how you find
# out it changed shape deliberately, rather than by opening the app at a checkout counter
# and discovering an empty store list.
#
# Run it periodically; a non-zero exit means the seed snapshots need regenerating or the
# catalog needs a new source.

set -uo pipefail

UA="Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
ENDPOINT="https://buyme.co.il/siteapi/brands"

# supplier id : friendly name : minimum brand count we expect to still see
SUPPLIERS=(
  "13438757:BUYME ALL:1000"
  "17574075:BUYME TOGETHER:300"
  "7565407:BUYME STYLE:150"
)

fail=0

for entry in "${SUPPLIERS[@]}"; do
  id="${entry%%:*}"
  rest="${entry#*:}"
  name="${rest%%:*}"
  floor="${rest##*:}"

  body=$(curl -sS --max-time 60 -H "User-Agent: $UA" "$ENDPOINT/$id" 2>/dev/null)
  status=$?

  if [ $status -ne 0 ] || [ -z "$body" ]; then
    echo "FAIL  $name ($id): request failed"
    fail=1
    continue
  fi

  # Cloudflare serves an HTML challenge instead of JSON when it dislikes the client.
  if printf '%s' "$body" | head -c 200 | grep -qi "<!DOCTYPE html"; then
    echo "FAIL  $name ($id): got an HTML challenge, not JSON — bot protection tightened"
    fail=1
    continue
  fi

  count=$(printf '%s' "$body" | grep -o '"searchTerms"' | wc -l | tr -d ' ')

  if [ "$count" -lt "$floor" ]; then
    echo "FAIL  $name ($id): only $count brands, expected at least $floor"
    fail=1
  else
    echo "ok    $name ($id): $count brands"
  fi
done

if [ $fail -ne 0 ]; then
  echo ""
  echo "The BuyMe source has changed. The app still works from its cached and bundled"
  echo "snapshots, but they are now frozen in time. Regenerate them, or point the card"
  echo "type at a static_list source in the catalog."
  exit 1
fi

echo ""
echo "All BuyMe sources healthy."
