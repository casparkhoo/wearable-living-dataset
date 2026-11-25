#!/bin/sh
#
# Runs inside the broker-accounts container of deployment/local: keeps the
# broker's password file in step with the users table, once a minute.
#
# Same scheme as scripts/sync_mqtt_users.sh on the cloud server: one account per
# participant, u<id>, with password sha256(MQTT_USER_SECRET:mqtt:<id>), which
# the backend hands to a signed-in phone. Nothing stores plaintext passwords.
# The container shares the broker's process namespace (pid: service:mosquitto),
# so it reloads the broker with a HUP to its process, without dropping phones.

set -eu
# The MariaDB client, plus the connector plugin that speaks MySQL 8's default
# login method (caching_sha2_password); without it every query fails.
apk add --no-cache mariadb-client mariadb-connector-c >/dev/null 2>&1 \
  || { echo "broker accounts: could not install the database client" >&2; exit 1; }

: "${MQTT_USER_SECRET:?}" "${MQTT_PASSWORD:?}" "${MYSQL_ROOT_PASSWORD:?}"
MQTT_USERNAME="${MQTT_USERNAME:-wearable_backend}"
PASSWORD_FILE="${PASSWORD_FILE:-/mosquitto/accounts/passwordfile}"
STATE_FILE="$PASSWORD_FILE.state"

hash() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

sync_once() {
  # Before the schema exists (first start) there are no users yet; the backend
  # account is still written so the backend can connect.
  # TLS without verifying MySQL's self-signed certificate: the connection never
  # leaves the compose network, and TLS lets the login method work after a restart.
  if ! users=$(mariadb --ssl --skip-ssl-verify-server-cert -h "$MYSQL_HOST" -u root -p"$MYSQL_ROOT_PASSWORD" \
      -N -B "$MYSQL_DATABASE" -e "SELECT id FROM users WHERE status = 'active' ORDER BY id" 2>/tmp/query.err); then
    if grep -q "doesn't exist" /tmp/query.err; then
      users=""   # first start: no schema yet, only the backend's account
    else
      echo "broker accounts: database query failed: $(head -c 300 /tmp/query.err)" >&2
      return 1
    fi
  fi
  state=$(printf '%s\n%s\n%s\n' "$(hash "$MQTT_USER_SECRET")" "$MQTT_USERNAME:$(hash "$MQTT_PASSWORD")" "$users" | sha256sum | cut -d' ' -f1)
  [ -s "$PASSWORD_FILE" ] && [ "$(cat "$STATE_FILE" 2>/dev/null)" = "$state" ] && return 0

  tmp=$(mktemp)
  {
    printf '%s:%s\n' "$MQTT_USERNAME" "$MQTT_PASSWORD"
    for id in $users; do
      printf 'u%s:%s\n' "$id" "$(hash "$MQTT_USER_SECRET:mqtt:$id")"
    done
  } > "$tmp"
  mosquitto_passwd -U "$tmp"
  # Written into the existing file: the broker keeps the file it opened.
  cat "$tmp" > "$PASSWORD_FILE"
  rm -f "$tmp"
  chown 1883:1883 "$PASSWORD_FILE" 2>/dev/null || true
  chmod 0600 "$PASSWORD_FILE"
  printf '%s' "$state" > "$STATE_FILE"
  # PID 1 of the shared namespace is the broker.
  kill -HUP 1 2>/dev/null || true
  echo "$(date -u +%FT%TZ) broker accounts: $(printf '%s\n' "$users" | grep -c . || true) participant(s)"
}

while true; do
  sync_once || echo "broker accounts: sync failed, retrying" >&2
  sleep "${SYNC_INTERVAL_S:-60}"
done
