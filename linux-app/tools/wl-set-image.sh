#!/bin/sh
# Own the Wayland clipboard as image/png until replaced.
# Usage: wl-set-image.sh /path/to/file.png
set -eu
file="${1:?png file required}"
wl_copy="${WL_COPY:-/usr/bin/wl-copy}"

# Drop any previous offer we own (best-effort).
"$wl_copy" --clear 2>/dev/null || true

# Stay in foreground so the compositor can stream image/png. Caller should
# background this process and leave it alive.
exec "$wl_copy" --type image/png --foreground < "$file"
