#!/usr/bin/env bash
set -euo pipefail

# Simple gcc/cc wrapper that injects CFLAGS for legacy C (c89/gnu89) builds.
# Usage: this script is used by setting CC to point at it in CI or local env.

REAL_CC="$(command -v gcc || true)"
if [ -z "$REAL_CC" ]; then
  REAL_CC="/usr/bin/gcc"
fi

# Default flags to enforce legacy GNU89 compatibility and relax modern warnings
: ${ALIRE_LEGACY_CFLAGS:="-std=gnu89 -O1 -fcommon -fno-strict-aliasing -Wno-implicit-function-declaration -Wno-int-conversion -Wno-incompatible-pointer-types -Wno-error"}

# Allow caller to provide additional flags with EXTRA_CFLAGS env var
FINAL_FLAGS="${ALIRE_LEGACY_CFLAGS} ${EXTRA_CFLAGS-}"

args=()
injected=false
for a in "$@"; do
  # if the caller already passes -std or -fcommon, avoid double-injecting
  case "$a" in
    -std=*|-fcommon|-fno-strict-aliasing|-Wno-implicit-function-declaration|-Wno-int-conversion|-Wno-incompatible-pointer-types|-Wno-error)
      injected=true
      ;;
  esac
  args+=("$a")
done

if [ "$injected" = false ] && [ -n "$FINAL_FLAGS" ]; then
  # split FINAL_FLAGS into positional words
  # shellcheck disable=SC2206
  flags_arr=($FINAL_FLAGS)
  set -- "${flags_arr[@]}" "${args[@]}"
else
  set -- "${args[@]}"
fi

exec "$REAL_CC" "$@"
