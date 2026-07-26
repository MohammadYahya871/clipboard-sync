#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist/ClipboardSync.Linux"

export DOTNET_ROOT="${DOTNET_ROOT:-$HOME/.dotnet}"
export PATH="$DOTNET_ROOT:$PATH"

if [[ ! -x "$DIST" ]]; then
  echo "Publishing Linux app..."
  dotnet publish "$ROOT/src/ClipboardSync.Linux/ClipboardSync.Linux.csproj" \
    -c Release -r linux-x64 --self-contained false -o "$ROOT/dist"
fi

exec "$DIST" "$@"
