#!/usr/bin/env bash
#
# Writes manifest.json describing every published store list.
#
# Phones fetch this file (a couple of kilobytes) before anything else and compare hashes,
# so a weekly sync that finds nothing changed costs one small request instead of several
# megabytes. Without it, every phone would re-download every list every week to discover
# that nothing moved.
#
# Usage: tools/gen-manifest.sh <publish-dir>
#   <publish-dir> contains catalog.json and stores/*.json

set -euo pipefail

DIR="${1:?usage: gen-manifest.sh <publish-dir>}"
cd "$DIR"

if [ ! -f catalog.json ]; then
  echo "no catalog.json in $DIR" >&2
  exit 1
fi

hash_of() { sha256sum "$1" | cut -d' ' -f1; }
bytes_of() { wc -c < "$1" | tr -d ' '; }

# Merchant count, read straight from the file so the manifest cannot drift from reality.
count_of() { grep -o '"n":' "$1" | wc -l | tr -d ' '; }

{
  echo '{'
  echo '  "manifestVersion": 1,'
  echo "  \"generatedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo '  "catalog": {'
  echo "    \"sha256\": \"$(hash_of catalog.json)\","
  echo "    \"bytes\": $(bytes_of catalog.json)"
  echo '  },'
  echo '  "stores": {'

  first=1
  for f in stores/*.json; do
    [ -e "$f" ] || continue
    slug="$(basename "$f" .json)"
    [ $first -eq 1 ] || echo ','
    first=0
    printf '    "%s": { "sha256": "%s", "bytes": %s, "count": %s }' \
      "$slug" "$(hash_of "$f")" "$(bytes_of "$f")" "$(count_of "$f")"
  done
  echo ''
  echo '  }'
  echo '}'
} > manifest.json

echo "manifest.json written: $(grep -c '"sha256"' manifest.json) hashed files"
