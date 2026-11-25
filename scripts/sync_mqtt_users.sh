#!/usr/bin/env bash
#
# Give every participant their own broker account.
#
# Participants otherwise share one MQTT credential, which means any phone could
# subscribe to sensors/# and read every other participant's stream. The ACL in
# deployment/cloud/mosquitto/aclfile restricts each username to its own
# namespace; this script creates those usernames.
#
# Passwords are derived, not stored: password = sha256(MQTT_USER_SECRET + ":mqtt:" + user_id),
# the same derivation the backend uses to hand a participant their credential
# from GET /api/me/mqtt-credentials. Nothing here keeps a list of plaintext
# passwords, and the file can be rebuilt from the users table at any time.
#
# It is cheap when nothing has changed: it compares the set of accounts with
# the last run and exits without touching the broker. That makes it safe to
# run every minute, which scripts/install_server_timers.sh sets up, so a new
# participant can stream within a minute of first signing in.
#
#   scripts/sync_mqtt_users.sh           # on the server
#   scripts/sync_mqtt_users.sh --force   # rebuild even if nothing changed
#
# Rotating MQTT_USER_SECRET invalidates every participant credential at once;
# run with --force afterwards, and each phone re-fetches its own after signing in.

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/wearable-app}"
CLOUD_DIR="$APP_DIR/deployment/cloud"
cd "$CLOUD_DIR"

set -a
# shellcheck disable=SC1091
. ./.env
set +a

if [ -z "${MQTT_USER_SECRET:-}" ]; then
  echo "MQTT_USER_SECRET is not set in $CLOUD_DIR/.env." >&2
  echo "Generate one with: openssl rand -hex 32" >&2
  exit 1
fi

USERS=$(docker compose exec -T mysql sh -c \
  "exec mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N -B \"\$MYSQL_DATABASE\" \
   -e \"SELECT id FROM users WHERE status = 'active' ORDER BY id\"" 2>/dev/null | tr -d '\r')

# The state records which accounts, and which secret, the current file was
# built from, without recording the secret itself.
STATE_FILE="mosquitto/.passwordfile.state"
STATE=$(printf '%s\n%s\n%s\n%s\n' "$(printf '%s' "$MQTT_USER_SECRET" | sha256sum | cut -c1-16)" \
  "$MQTT_USERNAME:$(printf '%s' "$MQTT_PASSWORD" | sha256sum | cut -c1-16)" \
  "${MQTT_ANDROID_USERNAME:-}" "$USERS" | sha256sum | cut -d' ' -f1)
if [ "${1:-}" != "--force" ] && [ -f mosquitto/passwordfile ] && [ "$(cat "$STATE_FILE" 2>/dev/null)" = "$STATE" ]; then
  exit 0
fi

echo "$(date -u +%FT%TZ) rebuilding broker accounts for $(printf '%s' "$USERS" | grep -c . || true) participant(s)"

# The password file is bind-mounted into the broker as a single file, which
# pins the file itself, not its name: renaming a new file over it would leave
# the running broker reading the old one until its next restart. So the
# user:password lines are piped into the broker's container, hashed there in a
# private temporary file, and written into the existing file. A failure before
# the last step leaves the old file untouched, and no plaintext touches the
# server's disk.
[ -f mosquitto/passwordfile ] || { echo "mosquitto/passwordfile is missing: touch it and restart the broker once" >&2; exit 1; }
{
  printf '%s:%s
' "$MQTT_USERNAME" "$MQTT_PASSWORD"
  if [ -n "${MQTT_ANDROID_USERNAME:-}" ] && [ -n "${MQTT_ANDROID_PASSWORD:-}" ]; then
    # Legacy shared phone account; harmless to keep, and the ACL gives it nothing
    # once AUTH_ALLOW_SHARED_TOKEN=false retires the shared token.
    printf '%s:%s
' "$MQTT_ANDROID_USERNAME" "$MQTT_ANDROID_PASSWORD"
  fi
  for user_id in $USERS; do
    password=$(printf '%s:mqtt:%s' "$MQTT_USER_SECRET" "$user_id" | sha256sum | cut -d' ' -f1)
    printf 'u%s:%s
' "$user_id" "$password"
  done
} | docker compose exec -T mosquitto sh -c '
  set -e
  umask 077
  tmp=/tmp/passwordfile.new
  cat > "$tmp"
  mosquitto_passwd -U "$tmp"
  [ -s "$tmp" ]
  cat "$tmp" > /mosquitto/config/passwordfile
  rm -f "$tmp"
  chown mosquitto:mosquitto /mosquitto/config/passwordfile
  chmod 640 /mosquitto/config/passwordfile'

# Mosquitto re-reads its password and ACL files on SIGHUP, so connected phones
# are not interrupted.
docker compose kill -s HUP mosquitto >/dev/null
printf '%s' "$STATE" > "$STATE_FILE"
echo "$(date -u +%FT%TZ) broker reloaded"
