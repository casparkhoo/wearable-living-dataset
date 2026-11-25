#!/usr/bin/env bash
#
# Run the whole system on this computer in Docker. See docs/LOCAL_HOSTING.md.
#
#   scripts/local.sh up                  create settings if needed, build, start, wait, print addresses
#   scripts/local.sh status              containers, health and addresses
#   scripts/local.sh logs [service]      follow logs (backend, mysql, mosquitto, gateway, ...)
#   scripts/local.sh admin <email>       make an account platform administrator (after it signs in once)
#   scripts/local.sh restore <dump.gz>   load a backup (from scripts/backup_db.sh or local.sh backup)
#   scripts/local.sh backup              dump the database to backups/local-<time>.sql.gz
#   scripts/local.sh phone [url]         build and install the Android app pointed at this instance
#   scripts/local.sh down                stop (data is kept)
#   scripts/local.sh reset               stop and DELETE all local data (asks first)
#
# Works in bash on Linux and macOS, and in Git Bash on Windows, with Docker running.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/deployment/local"
ENV_FILE="$DIR/.env"
compose() { docker compose --env-file "$ENV_FILE" -f "$DIR/docker-compose.yml" "$@"; }
die() { echo "error: $*" >&2; exit 1; }

secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else python3 -c 'import secrets; print(secrets.token_hex(24))' 2>/dev/null || python -c 'import secrets; print(secrets.token_hex(24))'
  fi
}

env_value() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\r'; }

set_env() { # key value: replace or append, without printing the value
  local tmp; tmp=$(mktemp)
  if grep -qE "^$1=" "$ENV_FILE"; then
    awk -v k="$1" -v v="$2" 'BEGIN{FS=OFS="="} $1==k{$0=k"="v} {print}' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"; printf '%s=%s\n' "$1" "$2" >> "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
}

ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    cp "$DIR/.env.example" "$ENV_FILE"
    chmod 600 "$ENV_FILE" 2>/dev/null || true
    echo "created deployment/local/.env"
  fi
  for key in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD MQTT_PASSWORD MQTT_USER_SECRET API_TOKEN; do
    [ -n "$(env_value "$key")" ] || set_env "$key" "$(secret)"
  done
  # Identity provider: reuse the values the Android build or console already
  # have in this checkout, if the settings file still holds the placeholder.
  if ! env_value SUPABASE_URL | grep -qE '^https://[a-z0-9]+\.supabase\.co$'; then
    local props="$ROOT/android-application/android/instance.properties"
    local url="" key=""
    if [ -f "$props" ]; then
      url=$(grep -E '^supabaseUrl=' "$props" | cut -d= -f2- | tr -d '\r' || true)
      key=$(grep -E '^supabasePublishableKey=' "$props" | cut -d= -f2- | tr -d '\r' || true)
    fi
    if [ -n "$url" ] && [ -n "$key" ]; then
      set_env SUPABASE_URL "$url"; set_env SUPABASE_PUBLISHABLE_KEY "$key"
      echo "identity provider settings taken from android-application/android/instance.properties"
    else
      die "set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in deployment/local/.env (docs/LOCAL_HOSTING.md, step 1)"
    fi
  fi
}

port() { env_value GATEWAY_PORT | grep . || echo 8088; }

lan_addresses() {
  if command -v ipconfig >/dev/null 2>&1 && ipconfig 2>/dev/null | grep -q "IPv4"; then
    # Windows. Docker and WSL adapters (172.16-31.x) are not reachable from a phone.
    ipconfig | grep -E "IPv4" | grep -oE "[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+" \
      | grep -vE '^(127\.|169\.254\.|172\.(1[6-9]|2[0-9]|3[01])\.)' || true
  elif command -v ip >/dev/null 2>&1; then
    ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | grep -vE '^172\.(1[7-9]|2[0-9]|3[01])\.' || true
  else
    ifconfig 2>/dev/null | awk '/inet / && $2 !~ /^127\./ {print $2}' || true
  fi
}

wait_healthy() {
  local url="http://127.0.0.1:$(port)/health" code=000
  printf 'waiting for the backend'
  for _ in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)
    case "$code" in 200|401) echo " ok"; return 0;; esac
    printf '.'; sleep 2
  done
  echo; compose ps; die "backend did not become healthy ($url answered $code); see: scripts/local.sh logs backend"
}

print_addresses() {
  local p; p=$(port)
  echo
  echo "Console on this computer:   http://localhost:$p"
  local ips; ips=$(lan_addresses)
  if [ -n "$ips" ]; then
    echo "From a phone on the same network (pick this computer's Wi-Fi address):"
    for ip in $ips; do echo "    http://$ip:$p"; done
    echo "Build the phone app for it:  scripts/local.sh phone http://<address>:$p"
  fi
  echo "From anywhere: put HTTPS in front with a tunnel, e.g.  tailscale serve --bg $p"
  echo "    then:  scripts/local.sh phone https://<machine>.<tailnet>.ts.net"
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  up)
    command -v docker >/dev/null || die "Docker is not installed"
    docker info >/dev/null 2>&1 || die "Docker is not running; start Docker Desktop first"
    ensure_env
    compose up -d --build
    wait_healthy
    print_addresses
    echo
    echo "First time? Sign in once in the console, then:  scripts/local.sh admin <your email>"
    ;;
  status)
    compose ps
    curl -s -o /dev/null -w "health: %{http_code}\n" "http://127.0.0.1:$(port)/health" || true
    print_addresses
    ;;
  logs) compose logs -f --tail=100 "$@" ;;
  admin)
    [ $# -ge 1 ] || die "usage: scripts/local.sh admin <email> [role]"
    compose exec -T backend wearable-backend set-platform-role "$1" "${2:-platform_admin}"
    ;;
  restore)
    [ $# -ge 1 ] && [ -f "$1" ] || die "usage: scripts/local.sh restore <dump.sql.gz>"
    if [ -f "$1.sha256" ]; then (cd "$(dirname "$1")" && sha256sum -c "$(basename "$1").sha256"); fi
    echo "restoring $(basename "$1") (replaces tables of the same name)"
    gzip -dc "$1" | compose exec -T mysql sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD"' 2>&1 | grep -v "Using a password" || true
    # The dump comes with its own database user; make sure ours can use it.
    compose exec -T mysql sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "GRANT ALL ON \`$MYSQL_DATABASE\`.* TO \`$MYSQL_USER\`@\`%\`"' 2>&1 | grep -v "Using a password" || true
    compose restart backend broker-accounts >/dev/null
    wait_healthy
    for t in users experiments sessions raw_packets stream_samples derived_windows; do
      printf '%-16s ' "$t"
      compose exec -T mysql sh -c "exec mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N -B \"\$MYSQL_DATABASE\" -e 'SELECT COUNT(*) FROM $t'" 2>/dev/null | tr -d '\r'
    done
    ;;
  backup)
    mkdir -p "$ROOT/backups"
    out="$ROOT/backups/local-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
    compose exec -T mysql sh -c 'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --triggers --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"' 2>/dev/null | gzip -6 > "$out"
    (cd "$(dirname "$out")" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256")
    echo "wrote $out"
    ;;
  phone)
    base="${1:-}"
    if [ -z "$base" ]; then
      first=$(lan_addresses | head -n1)
      [ -n "$first" ] || die "usage: scripts/local.sh phone <http://address:port | https://tunnel-host>"
      base="http://$first:$(port)"
    fi
    base="${base%/}"
    case "$base" in
      https://*) broker="wss://${base#https://}/mqtt" ;;
      http://*)  broker="ws://${base#http://}/mqtt" ;;
      *) die "the address must start with http:// or https://" ;;
    esac
    echo "building the app for $base (broker $broker)"
    (cd "$ROOT/android-application/android" && ./gradlew :app:assembleDebug -q \
      -PcloudBackendUrl="$base" -PcloudBrokerUrl="$broker" \
      -PsupabaseUrl="$(env_value SUPABASE_URL)" -PsupabasePublishableKey="$(env_value SUPABASE_PUBLISHABLE_KEY)")
    apk="$ROOT/android-application/android/app/build/outputs/apk/debug/app-debug.apk"
    if command -v adb >/dev/null && [ "$(adb devices | grep -c 'device$')" -ge 1 ]; then
      adb install -r -t "$apk" | tail -n1
    else
      echo "no phone on adb; install $apk yourself"
    fi
    ;;
  down) compose down ;;
  reset)
    printf 'This deletes every local recording, account link and setting in Docker. Type DELETE: '
    read -r answer
    [ "$answer" = DELETE ] || die "cancelled"
    compose down -v
    ;;
  *)
    sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
esac
