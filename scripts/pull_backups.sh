#!/usr/bin/env bash
#
# Copy the server's database backups to this computer.
#
# Backups on the server do not survive losing the server. Run this from your
# own machine (manually, or from a scheduled task) to keep an off-server copy.
# Uses VM_HOST / VM_KEY from deployment/cloud/deploy.env.
#
#   scripts/pull_backups.sh [destination]     # default ./backups (gitignored)

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$ROOT/deployment/cloud/deploy.env" ] && . "$ROOT/deployment/cloud/deploy.env"
VM_HOST="${VM_HOST:?set VM_HOST in deployment/cloud/deploy.env}"
VM_KEY="${VM_KEY:?set VM_KEY in deployment/cloud/deploy.env}"
REMOTE_DIR="${REMOTE_BACKUP_DIR:-wearable-backups}"
DEST="${1:-$ROOT/backups}"
mkdir -p "$DEST"

# Only files not already here are copied; dumps are never modified once written.
for file in $(ssh -i "$VM_KEY" -o BatchMode=yes "$VM_HOST" "cd $REMOTE_DIR && ls -1 wearable-*.sql.gz wearable-*.sql.gz.sha256 2>/dev/null"); do
  if [ ! -f "$DEST/$file" ]; then
    scp -q -i "$VM_KEY" "$VM_HOST:$REMOTE_DIR/$file" "$DEST/$file"
    echo "copied $file"
  fi
done
(cd "$DEST" && for sum in wearable-*.sha256; do [ -f "$sum" ] && sha256sum -c --quiet "$sum"; done) && echo "checksums ok in $DEST"
