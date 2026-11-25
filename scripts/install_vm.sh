#!/usr/bin/env bash
#
# The server side of `scripts/deploy.sh vm <user@host>`. Not meant to be run by hand: deploy.sh
# streams this file to the server over SSH and calls one function of it:
#
#   facts        print what the server is (OS, CPU, RAM, sudo)
#   prereqs      install Docker and the compose plugin, firewall, swap (needs sudo)
#   check_dns    warn if API_DOMAIN / MQTT_DOMAIN do not point at this server
#   configure    create deployment/cloud/.env with random secrets, if it does not exist
#   start        docker compose up, then wait for the backend
#   timers       broker accounts and the nightly backup (scripts/sync_mqtt_users.sh,
#                scripts/install_server_timers.sh)
#   check_https  ask whether https://API_DOMAIN answers
#
# Settings arrive as environment variables. Secrets are generated here, on the server, and are
# never printed. Every function is safe to run again.

set -euo pipefail

say() { printf '%s\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

# deploy.sh passes the default as the text '$HOME/wearable-app'.
expand_app() {
  local app="${APP_DIR:-\$HOME/wearable-app}"
  case "$app" in
    '$HOME'*) app="$HOME${app#\$HOME}" ;;
  esac
  printf '%s' "$app"
}

ram_mb() { awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo; }

facts() {
  # shellcheck disable=SC1091
  . /etc/os-release 2>/dev/null || true
  say "os=${PRETTY_NAME:-unknown}"
  case "${ID:-}${ID_LIKE:-}" in *debian*|*ubuntu*) say "supported=yes" ;; *) say "supported=no" ;; esac
  say "arch=$(uname -m)"
  say "ram_mb=$(ram_mb)"
  say "swap_mb=$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo)"
  if sudo -n true 2>/dev/null; then say "sudo=yes"; else say "sudo=no"; fi
  if command -v docker >/dev/null 2>&1; then say "docker=yes"; else say "docker=no"; fi
}

prereqs() {
  local run_as="${RUN_AS:-$(id -un)}"
  export DEBIAN_FRONTEND=noninteractive
  if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    say "installing Docker (apt)"
    sudo -n apt-get update -qq
    sudo -n apt-get install -y -qq docker.io curl ca-certificates openssl >/dev/null
    if ! docker compose version >/dev/null 2>&1; then
      sudo -n apt-get install -y -qq docker-compose-v2 >/dev/null 2>&1 \
        || sudo -n apt-get install -y -qq docker-compose-plugin >/dev/null 2>&1 \
        || die "could not install the Docker compose plugin from apt. Install Docker Engine with the compose plugin by hand (https://docs.docker.com/engine/install/), then run deploy.sh again"
    fi
  else
    say "docker and compose already installed"
  fi
  sudo -n systemctl enable --now docker >/dev/null 2>&1 || true
  if ! id -nG "$run_as" | tr ' ' '\n' | grep -qx docker; then
    sudo -n usermod -aG docker "$run_as"
    say "added $run_as to the docker group (takes effect on the next login; deploy.sh reconnects)"
  fi

  if command -v ufw >/dev/null 2>&1 && sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
    sudo -n ufw allow 22/tcp >/dev/null; sudo -n ufw allow 80/tcp >/dev/null; sudo -n ufw allow 443/tcp >/dev/null
    say "firewall (ufw): ports 22, 80, 443 allowed"
  fi

  # A small server runs out of memory without swap. Add 2 GB if it has none.
  if [ "$(ram_mb)" -lt 2000 ] && [ "$(awk '/SwapTotal/ {print $2}' /proc/meminfo)" = 0 ]; then
    say "creating 2 GB of swap (server has under 2 GB RAM and no swap)"
    sudo -n fallocate -l 2G /swapfile 2>/dev/null || sudo -n dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    sudo -n chmod 600 /swapfile
    sudo -n mkswap /swapfile >/dev/null
    sudo -n swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo -n tee -a /etc/fstab >/dev/null
  fi
  # Reachable through sg so this works even before the next login.
  sg docker -c 'docker version --format "docker {{.Server.Version}} ready"' 2>/dev/null || docker version --format "docker {{.Server.Version}} ready" 2>/dev/null || true
}

check_dns() {
  local own n got
  own=$(curl -fsS4 --max-time 8 https://api.ipify.org 2>/dev/null || true)
  [ -n "$own" ] || { say "could not determine this server's public IP; DNS not checked"; return 0; }
  for n in "${API_DOMAIN:?}" "${MQTT_DOMAIN:?}"; do
    got=$(getent ahostsv4 "$n" 2>/dev/null | awk 'NR==1{print $1}')
    if [ "$got" = "$own" ]; then say "DNS ok: $n -> $got"
    else say "WARNING: $n resolves to '${got:-nothing}', but this server is $own. Caddy cannot get certificates until it does."; fi
  done
}

# put KEY VALUE FILE: replace KEY=..., or a commented "# KEY=..." line, or append. The value
# is never printed.
put() {
  local key="$1" val="$2" file="$3" tmp
  tmp=$(mktemp)
  if grep -qE "^$key=" "$file"; then
    awk -v k="$key" -v v="$val" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' "$file" > "$tmp"
  elif grep -qE "^# *$key=" "$file"; then
    awk -v k="$key" -v v="$val" '$0 ~ "^# *"k"=" && !done {print k"="v; done=1; next} {print}' "$file" > "$tmp"
  else
    cat "$file" > "$tmp"; printf '%s=%s\n' "$key" "$val" >> "$tmp"
  fi
  cat "$tmp" > "$file"; rm -f "$tmp"
}

rand() { openssl rand -hex 24; }

configure() {
  local app dir env
  app="$(expand_app)"; dir="$app/deployment/cloud"; env="$dir/.env"
  [ -d "$dir" ] || die "$dir does not exist; the copy step failed"
  mkdir -p "$dir/mosquitto"
  # Docker would create a directory here if the file did not exist when the stack first starts.
  [ -f "$dir/mosquitto/passwordfile" ] || { : > "$dir/mosquitto/passwordfile"; }
  chmod 666 "$dir/mosquitto/passwordfile" 2>/dev/null || true
  if [ -f "$env" ]; then
    say "keeping the existing .env (secrets unchanged)"
    grep -q "^API_DOMAIN=${API_DOMAIN:?}\$" "$env" || say "note: API_DOMAIN in the existing .env differs from $API_DOMAIN; edit it by hand if you meant to change it"
    return 0
  fi
  cp "$dir/.env.example" "$env"
  chmod 600 "$env"
  local k
  for k in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD MQTT_PASSWORD MQTT_ANDROID_PASSWORD API_TOKEN MQTT_USER_SECRET; do
    put "$k" "$(rand)" "$env"
  done
  put API_DOMAIN "$API_DOMAIN" "$env"
  put MQTT_DOMAIN "${MQTT_DOMAIN:?}" "$env"
  put SUPABASE_URL "${SUPABASE_URL:?}" "$env"
  put AUTH_ALLOW_SHARED_TOKEN false "$env"
  put BACKUP_DIR "$HOME/wearable-backups" "$env"
  if [ "${PAPAGEI:-1}" = 1 ]; then put ML_INFERENCE_ENABLED true "$env"; else put ML_INFERENCE_ENABLED false "$env"; fi
  mkdir -p "$HOME/wearable-backups"
  say "created $env with new random secrets (mode 600; not shown)"
}

start() {
  local app dir code=000 i
  app="$(expand_app)"; dir="$app/deployment/cloud"
  cd "$dir"
  # shellcheck disable=SC2086
  docker compose up -d --no-build ${SERVICES:?} </dev/null
  printf 'waiting for the backend'
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/health || true)
    case "$code" in 200|401) say " ok (health $code)"; docker compose ps; return 0 ;; esac
    printf '.'; sleep 3
  done
  say; docker compose ps
  die "the backend did not answer (last status $code)"
}

timers() {
  local app run_as
  app="$(expand_app)"; run_as="${RUN_AS:?}"
  cd "$app"
  APP_DIR="$app" bash scripts/sync_mqtt_users.sh --force </dev/null
  sudo -n env APP_DIR="$app" RUN_AS="$run_as" bash scripts/install_server_timers.sh </dev/null
}

check_https() {
  local code=000 i
  for i in $(seq 1 20); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://${API_DOMAIN:?}/health" || true)
    case "$code" in 200|401) say "https://$API_DOMAIN answered $code (401 is correct: sign-in is required)"; return 0 ;; esac
    sleep 3
  done
  say "https://$API_DOMAIN did not answer (last status $code)"
  return 1
}

fn="${1:-}"
case "$fn" in
  facts|prereqs|check_dns|configure|start|timers|check_https) "$fn" ;;
  *) die "unknown function '$fn'; this file is called by scripts/deploy.sh" ;;
esac
