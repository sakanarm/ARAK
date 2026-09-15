#!/usr/bin/env bash
# Re-pin the OpenMetadata OpenAPI spec from a running instance.
#   usage: OM_BASE_URL=http://your-openmetadata-host:8585 scripts/sync-om-openapi.sh
set -euo pipefail
: "${OM_BASE_URL:?set OM_BASE_URL to your OpenMetadata instance, e.g. http://openmetadata.internal:8585}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

VER="$(curl -sfS -m 15 "$OM_BASE_URL/api/v1/system/version" \
       | python -c 'import sys,json;print(json.load(sys.stdin)["version"])')"
OUT="$ROOT/spec/openmetadata-$VER-swagger.json"

# /swagger.json is served unauthenticated; /api/swagger.json requires a token.
curl -sfS -m 120 "$OM_BASE_URL/swagger.json" -o "$OUT"
python -c "import json,sys; json.load(open(sys.argv[1]))" "$OUT"
echo "pinned $OUT ($(wc -c < "$OUT") bytes, OpenMetadata $VER)"
echo "NOTE: update <openmetadata.spec.file> in backend/dac-connector-openmetadata/pom.xml"
