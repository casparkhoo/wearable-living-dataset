#!/usr/bin/env bash
#
# Prove a backup can be restored.
#
# Checks the dump's checksum, loads it into a throwaway MySQL container (never
# the live database) and reports row counts for the identity, evidence and
# derived tables, side by side with the live database when run on the server.
# A backup that has never been restored is a hope, not a backup; run this after
# setting up backups and then every few weeks.
#
#   scripts/restore_test.sh                       # newest dump in BACKUP_DIR
#   scripts/restore_test.sh path/to/wearable-....sql.gz
#   COMPARE_LIVE=1 scripts/restore_test.sh        # on the server: compare with live

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/wearable-app}"
if [ -f "$APP_DIR/deployment/cloud/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$APP_DIR/deployment/cloud/.env"
  set +a
fi
BACKUP_DIR="${BACKUP_DIR:-$HOME/wearable-backups}"
DUMP="${1:-$(ls -1t "$BACKUP_DIR"/wearable-*.sql.gz 2>/dev/null | head -n 1)}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "no dump found (looked in $BACKUP_DIR)" >&2; exit 1; }

if [ -f "$DUMP.sha256" ]; then
  (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256")
fi

NAME=wearable-restore-test
DB="${MYSQL_DATABASE:-wearable_living_dataset}"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "== starting a throwaway MySQL"
docker run -d --name "$NAME" -e MYSQL_ROOT_PASSWORD=restoretest mysql:8.4 >/dev/null
# The image starts a temporary server for its init scripts, stops it, then starts the real one.
# A ping can succeed against the temporary server, so wait for the init to finish first.
for _ in $(seq 1 90); do
  docker logs "$NAME" 2>&1 | grep -q "MySQL init process done" && break
  sleep 2
done
for _ in $(seq 1 60); do
  docker exec "$NAME" mysqladmin ping -uroot -prestoretest --silent >/dev/null 2>&1 && break
  sleep 2
done
sleep 3

echo "== restoring $(basename "$DUMP")"
gzip -dc "$DUMP" | docker exec -i "$NAME" mysql -uroot -prestoretest 2>/dev/null

TABLES="users studies study_memberships experiments sessions raw_packets stream_samples annotations derived_windows erasure_log"
count() { # container, password, table
  docker exec "$1" mysql -uroot -p"$2" -N -B "$DB" -e "SELECT COUNT(*) FROM $3" 2>/dev/null || echo "-"
}

FAILED=0
printf '%-20s %12s' table restored
[ "${COMPARE_LIVE:-0}" = 1 ] && printf ' %12s' live
printf '\n'
for table in $TABLES; do
  restored=$(count "$NAME" restoretest "$table")
  printf '%-20s %12s' "$table" "$restored"
  if [ "${COMPARE_LIVE:-0}" = 1 ]; then
    live=$(cd "$APP_DIR/deployment/cloud" && docker compose exec -T mysql sh -c \
      "exec mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N -B \"\$MYSQL_DATABASE\" -e 'SELECT COUNT(*) FROM $table'" 2>/dev/null | tr -d '\r' || echo "-")
    printf ' %12s' "$live"
    # Rows added since the dump are expected; fewer rows in the restore than
    # the dump should hold would mean a broken backup, which the live count
    # cannot show, so only a missing table is treated as a failure.
  fi
  printf '\n'
  [ "$restored" = "-" ] && [ "$table" != erasure_log ] && [ "$table" != derived_windows ] && FAILED=1
done

if [ "$FAILED" = 0 ]; then echo "RESTORE OK"; else echo "RESTORE FAILED: core tables missing" >&2; exit 1; fi
