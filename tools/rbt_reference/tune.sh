#!/usr/bin/env bash
# One tuning iteration: render the tuning tile list with given settings and compare against RBT.
# usage: tools/rbt_reference/tune.sh <label> <rbtSmoothing> [extra vectorvector args...]
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ $# -lt 2 ]]; then
  echo 'usage: tune.sh <label> <rbtSmoothing> [extra vectorvector args...]' >&2
  exit 2
fi
label=$1; smoothing=$2; shift 2
if [[ ! "$label" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo 'label must start with a letter or digit and contain only letters, digits, dot, underscore or hyphen' >&2
  exit 2
fi
out=out/tune/$label
mkdir -p out/tune
# Preserve previous evidence; choose a fresh label instead of recursively deleting results.
mkdir "$out"
start=$(date +%s)
java -Xmx8g -jar target/vectorvector-cli.jar --profile rbt --dataFormat mapterhorn \
  --pmtiles data/iceland/mapterhorn-iceland-z0-12.pmtiles --glaciers data/iceland/iceland-latest.osm.pbf \
  --bbox=-24.6,63.2,-13.3,66.7 -minZ 9 -maxZ 13 --tileList tools/rbt_reference/tuning-tiles.txt \
  --rbtSmoothing "$smoothing" -f mbtiles -o "$out" "$@" > "$out/run.log" 2>&1
echo "render: $(( $(date +%s) - start ))s"
"${PYTHON:-python}" -I tools/rbt_reference/compare.py data/iceland/rbt-reference-iceland.mbtiles "$out/contours.mbtiles" \
  --out "$out/compare" --max-tiles 200 --png-samples 2 --label "$label" > "$out/compare.log" 2>&1
grep -E "^\| [0-9]+ \|" "$out/compare/compare-report.md"
tail -1 "$out/compare.log"
