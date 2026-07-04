#!/usr/bin/env bash
# run.sh — runs the full producer -> consumer NxM round-trip matrix.
# Used both for local dev and by the CI workflow (same script, same
# behaviour in both places).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p fixtures/out
rm -f fixtures/out/*.json

versions=(5-16 5-26 5-34 latest)

echo "=== producing ==="
for v in "${versions[@]}"; do
  echo "--- produce-$v ---"
  docker compose run --rm "produce-$v"
done

echo "=== consuming (NxM round-trip) ==="
fail=0
for v in "${versions[@]}"; do
  echo "--- consume-$v ---"
  docker compose run --rm "consume-$v" || fail=1
done

docker compose down -v >/dev/null 2>&1 || true
exit $fail
