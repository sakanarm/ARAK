#!/usr/bin/env bash
# Re-pull the vendored OpenMetadata design system at a given tag.
#   usage: scripts/sync-om-design-system.sh 2.0.1-release
set -euo pipefail
TAG="${1:?usage: $0 <openmetadata-tag>   e.g. 2.0.1-release}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PRE="openmetadata-ui-core-components/src/main/resources/ui"
RAW="https://raw.githubusercontent.com/open-metadata/OpenMetadata/$TAG"
API="https://api.github.com/repos/open-metadata/OpenMetadata/git/trees/$TAG?recursive=1"
DEST="$ROOT/frontend/ui-core-components"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

echo "==> fetching tree for $TAG"
curl -sfS -m 120 "$API" -o "$TMP/tree.json"

python - "$TMP/tree.json" "$PRE" "$TMP/files.txt" <<'PY'
import json, sys
tree, pre, out = sys.argv[1], sys.argv[2], sys.argv[3]
t = json.load(open(tree))
if "tree" not in t:
    sys.exit("GitHub API returned no tree (rate limited? bad tag?): %s" % str(t)[:200])
keep = [x["path"] for x in t["tree"]
        if x["type"] == "blob"
        and x["path"].startswith(pre + "/src/")
        and "/src/stories/" not in x["path"]]
keep += [f"{pre}/{f}" for f in ("package.json", "tsconfig.json", "vite.config.ts", ".nvmrc")]
open(out, "w").write("\n".join(keep))
print(f"{len(keep)} files")
PY

echo "==> downloading into $DEST"
while read -r f; do
  rel="${f#"$PRE"/}"; o="$DEST/$rel"
  mkdir -p "$(dirname "$o")"
  curl -sfS -m 60 "$RAW/$f" -o "$o"
done < "$TMP/files.txt"
curl -sfS -m 30 "$RAW/LICENSE" -o "$DEST/LICENSE"

sed -i -E "s|^Tag:.*|Tag:    \`$TAG\`|; s|^Pulled:.*|Pulled: $(date +%Y-%m-%d)|" "$DEST/VENDORED.md"
echo "==> done. review 'git diff $DEST' before committing."
