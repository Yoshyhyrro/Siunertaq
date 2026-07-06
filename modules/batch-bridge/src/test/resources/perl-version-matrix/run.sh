#!/usr/bin/env bash
# run.sh — Runs the full producer -> consumer NxM round-trip matrix.
# Used both for local development and by the CI workflow, ensuring
# identical behavior across environments.
set -euo pipefail
cd "$(dirname "$0")"

# Prepare and clean up the fixture output directory
mkdir -p fixtures/out
rm -f fixtures/out/*.json

# Baseline Perl versions to test. 
# 5-16 is retained as the baseline to accurately reproduce and verify the legacy environment.
versions=(5-16 5-26 5-34 latest)

# Force-build all service containers before execution.
# This prevents Docker from silently using stale cached images, guaranteeing that
# any Dockerfile changes (especially the delicate source-build steps of perl-5.16
# with patchperl and CFLAGS) are validated on every single execution.
echo "=== building/verifying docker images ==="
docker compose build

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

# Perform post-run cleanup of containers and volumes
docker compose down -v >/dev/null 2>&1 || true
exit $fail