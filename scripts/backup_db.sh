#!/usr/bin/env bash
#
# Nightly database backup, on the server.
#
# Dumps the living dataset with mysqldump inside the MySQL container (a
# consistent snapshot via --single-transaction, so recording can continue),
# compresses it, writes a SHA-256 next to it, and deletes dumps older than
# BACKUP_KEEP_DAYS. scripts/install_server_timers.sh runs it every night.
#
# A backup on the same machine does not survive losing that machine. Copy the
# dumps off it with scripts/pull_backups.sh (from your own computer), or point
# BACKUP_DIR at mounted object storage.
#
# Settings, from deployment/cloud/.env:
#   BACKUP_DIR        where dumps go (default ~/wearable-backups)
#   BACKUP_KEEP_DAYS  how long they are kept (default 14)
#
# Erased participants' data remains in older dumps until they age out; the
# consent form should say how long that is.

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/wearable-app}"
cd "$APP_DIR/deployment/cloud"
set -a
# shellcheck disable=SC1091
. ./.env
set +a

BACKUP_DIR="${BACKUP_DIR:-$HOME/wearable-backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$BACKUP_DIR/wearable-$STAMP.sql.gz"
TMP="$OUT.partial"

echo "$(date -u +%FT%TZ) dumping $MYSQL_DATABASE"
docker compose exec -T mysql sh -c \
  'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --triggers --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"' \
  2>/dev/null | gzip -6 > "$TMP"

# An empty or truncated dump must not replace a good one.
if ! gzip -t "$TMP" || [ "$(gzip -dc "$TMP" | tail -n 1 | grep -c 'Dump completed')" != 1 ]; then
  echo "backup failed: dump incomplete; keeping previous backups" >&2
  rm -f "$TMP"
  exit 1
fi
mv "$TMP" "$OUT"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "$(date -u +%FT%TZ) wrote $OUT ($(du -h "$OUT" | cut -f1))"

find "$BACKUP_DIR" -name 'wearable-*.sql.gz*' -mtime +"$BACKUP_KEEP_DAYS" -print -delete
