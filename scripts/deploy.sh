#!/usr/bin/env bash
#
# One command to host the whole system. Full guide: docs/SELF_HOSTING.md.
#
#   scripts/deploy.sh local [options]            Docker on this computer
#   scripts/deploy.sh vm <user@host> [options]   a fresh Debian/Ubuntu server, over SSH
#
# Both modes are safe to run again: they keep existing secrets and data, and only
# change what differs (that is also how you update after pulling new code).
# Add --dry-run to any command to see what it would do without doing it.
#
# Common options
#   --dry-run              print the plan; change nothing, contact nothing
#   --supabase-url URL     identity project URL, https://<ref>.supabase.co
#                          (or SUPABASE_URL in the environment)
#   --supabase-key KEY     its publishable key, sb_publishable_... (local mode; or
#                          SUPABASE_PUBLISHABLE_KEY). Never use a secret/service_role key.
#   -h, --help
#
# local options
#   --port N               console/API/broker port (default 8088)
#   --project NAME         Docker Compose project name (default wearable-local)
#   --set KEY=VALUE        any other setting for deployment/local/.env (repeatable),
#                          for example --set MQTT_HOST_PORT=21883
#   Creates deployment/local/.env (gitignored) with random passwords, builds and starts
#   the stack through scripts/local.sh, waits until it is healthy and prints the addresses.
#
# vm options
#   --domain D             serve https://api.D and https://mqtt.D (A records must
#                          point at the server). Without it, api.<ip>.nip.io is used.
#   --ip ADDR              the server's public IPv4, if <host> is a name (nip.io fallback)
#   --key PATH             SSH private key
#   --app-dir DIR          checkout on the server (default ~/wearable-app)
#   --no-papagei           do not run the PaPaGei model worker (for servers under 2 GB)
#   --skip-images          do not rebuild and ship the images (configuration-only re-run)
#   Installs Docker if missing (apt), builds the backend image here and ships it (a 1 GB
#   server cannot build it), copies the deployment files, creates secrets on the server
#   (never here, never printed), starts Caddy for TLS, creates the broker accounts, and
#   installs the nightly backup and broker-account timers (scripts/install_server_timers.sh).
#
# Needs: bash and Docker here; for vm also ssh, tar, gzip and a server with passwordless sudo.
# Works in bash on Linux and macOS, and in Git Bash on Windows.
#
# Later updates of a running server: scripts/deploy_cloud_backend.sh (docs/DEPLOY_RUNBOOK.md).

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="$ROOT/deployment/local"
LOCAL_ENV="$LOCAL_DIR/.env"

DRY=0
SUPABASE_URL="${SUPABASE_URL:-}"
SUPABASE_KEY="${SUPABASE_PUBLISHABLE_KEY:-}"
PORT=""
PROJECT=""
SETS=()
DOMAIN=""
IP=""
KEY=""
APP_DIR=""
PAPAGEI=1
IMAGES=1

die()  { echo "error: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }
note() { printf '   %s\n' "$*"; }
plan() { printf '   [dry run] %s\n' "$*"; }
usage() { sed -n '3,43p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# Run a command, or only show it under --dry-run.
run() { if [ "$DRY" = 1 ]; then plan "$*"; else "$@"; fi; }

secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else python3 -c 'import secrets; print(secrets.token_hex(24))' 2>/dev/null \
    || python -c 'import secrets; print(secrets.token_hex(24))'
  fi
}

valid_supabase_url() { [[ "$1" =~ ^https://[a-z0-9]+\.supabase\.co$ ]]; }

# ---- argument parsing ------------------------------------------------------------------
MODE="${1:-}"
case "$MODE" in
  local|vm) shift ;;
  -h|--help|help|"") usage; [ -n "$MODE" ] && exit 0 || exit 1 ;;
  *) die "unknown mode '$MODE' (use: local, or vm <user@host>); see scripts/deploy.sh --help" ;;
esac

TARGET=""
if [ "$MODE" = vm ]; then
  case "${1:-}" in
    ""|-*) die "usage: scripts/deploy.sh vm <user@host> [options]" ;;
    *) TARGET="$1"; shift ;;
  esac
  [[ "$TARGET" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+$ ]] || die "the target must look like user@host, got '$TARGET'"
fi

need_arg() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --supabase-url) need_arg "$@"; SUPABASE_URL="$2"; shift 2 ;;
    --supabase-key) need_arg "$@"; SUPABASE_KEY="$2"; shift 2 ;;
    --port) need_arg "$@"; PORT="$2"; shift 2 ;;
    --project) need_arg "$@"; PROJECT="$2"; shift 2 ;;
    --set) need_arg "$@"; SETS+=("$2"); shift 2 ;;
    --domain) need_arg "$@"; DOMAIN="$2"; shift 2 ;;
    --ip) need_arg "$@"; IP="$2"; shift 2 ;;
    --key) need_arg "$@"; KEY="$2"; shift 2 ;;
    --app-dir) need_arg "$@"; APP_DIR="$2"; shift 2 ;;
    --no-papagei) PAPAGEI=0; shift ;;
    --skip-images) IMAGES=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option '$1'; see scripts/deploy.sh --help" ;;
  esac
done

if [ -n "$SUPABASE_URL" ]; then
  valid_supabase_url "${SUPABASE_URL%/}" || die "--supabase-url must look like https://<project-ref>.supabase.co"
  SUPABASE_URL="${SUPABASE_URL%/}"
fi
case "$SUPABASE_KEY" in
  ""|sb_publishable_*) ;;
  sb_secret_*|*service_role*) die "that is a secret key; give the PUBLISHABLE key (sb_publishable_...)" ;;
esac
[ -z "$PORT" ] || [[ "$PORT" =~ ^[0-9]+$ ]] || die "--port must be a number"
[ -z "$PROJECT" ] || [[ "$PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "--project must be lower-case letters, digits, - or _"
for kv in "${SETS[@]+"${SETS[@]}"}"; do
  [[ "$kv" =~ ^[A-Z][A-Z0-9_]*=[^[:space:]]*$ ]] || die "--set expects KEY=VALUE (upper-case key, no spaces), got '${kv%%=*}'"
done
[ "$MODE" = vm ] || { [ -z "$DOMAIN$IP$KEY$APP_DIR" ] || die "--domain, --ip, --key and --app-dir are for vm mode"; }
[ "$MODE" = local ] || { [ -z "$PORT$PROJECT" ] && [ ${#SETS[@]} -eq 0 ] || die "--port, --project and --set are for local mode"; }

# ============================================================================================
# local
# ============================================================================================
env_value() { grep -E "^$1=" "$LOCAL_ENV" 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\r' || true; }

set_env() { # key value: replace or append; the value is never printed
  local tmp; tmp=$(mktemp)
  if grep -qE "^$1=" "$LOCAL_ENV"; then
    awk -v k="$1" -v v="$2" 'BEGIN{FS=OFS="="} $1==k{$0=k"="v} {print}' "$LOCAL_ENV" > "$tmp"
  else
    cat "$LOCAL_ENV" > "$tmp"; printf '%s=%s\n' "$1" "$2" >> "$tmp"
  fi
  cat "$tmp" > "$LOCAL_ENV"; rm -f "$tmp"
}

tcp_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

mode_local() {
  [ -f "$ROOT/scripts/local.sh" ] || die "scripts/local.sh not found; run this from a full checkout"
  [ -f "$LOCAL_DIR/.env.example" ] || die "deployment/local/.env.example not found"
  [ -z "$PROJECT" ] || export COMPOSE_PROJECT_NAME="$PROJECT"
  local proj="${COMPOSE_PROJECT_NAME:-wearable-local}"

  step "1. Prerequisites"
  local missing=0
  command -v docker >/dev/null 2>&1 || { echo "   missing: docker (install Docker Desktop, or Docker Engine with the compose plugin)"; missing=1; }
  if command -v docker >/dev/null 2>&1; then
    docker compose version >/dev/null 2>&1 || { echo "   missing: the 'docker compose' plugin (Compose v2)"; missing=1; }
    docker info >/dev/null 2>&1 || { echo "   Docker is installed but not running: start Docker Desktop (or the docker service)"; missing=1; }
  fi
  command -v curl >/dev/null 2>&1 || { echo "   missing: curl"; missing=1; }
  command -v openssl >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 \
    || { echo "   missing: openssl or python (to generate passwords)"; missing=1; }
  if [ "$missing" = 1 ]; then
    [ "$DRY" = 1 ] && note "(dry run: continuing although something is missing)" || die "fix the items above and run this again"
  else
    note "docker, compose, curl: ok"
  fi

  step "2. Settings (deployment/local/.env, never committed)"
  local want_port="${PORT:-$(env_value GATEWAY_PORT)}"; want_port="${want_port:-8088}"
  if [ -f "$LOCAL_ENV" ]; then note "keeping the existing .env and its passwords"
  else run cp "$LOCAL_DIR/.env.example" "$LOCAL_ENV"; [ "$DRY" = 1 ] || { chmod 600 "$LOCAL_ENV" 2>/dev/null || true; note "created deployment/local/.env"; }; fi

  # Identity provider: flags/environment win; else what the file already holds; else the
  # Android build's instance.properties (scripts/local.sh reads that itself); else ask.
  local have_url=0
  if [ -n "$SUPABASE_URL" ]; then have_url=1
  elif [ -f "$LOCAL_ENV" ] && valid_supabase_url "$(env_value SUPABASE_URL)"; then have_url=1
  elif [ -f "$ROOT/android-application/android/instance.properties" ] \
       && grep -qE '^supabaseUrl=https://' "$ROOT/android-application/android/instance.properties"; then have_url=1
  fi
  if [ "$have_url" = 0 ]; then
    if [ "$DRY" = 1 ]; then
      plan "would need --supabase-url and --supabase-key (Supabase dashboard > Project Settings > API)"
    elif [ -t 0 ]; then
      echo "   Sign-in uses a free Supabase project (accounts only, no sensor data)."
      echo "   Supabase dashboard > Project Settings > API: copy the project URL and the PUBLISHABLE key."
      read -r -p "   Project URL (https://<ref>.supabase.co): " SUPABASE_URL
      valid_supabase_url "${SUPABASE_URL%/}" || die "that is not a Supabase project URL"
      SUPABASE_URL="${SUPABASE_URL%/}"
      read -r -p "   Publishable key (sb_publishable_...): " SUPABASE_KEY
      case "$SUPABASE_KEY" in sb_publishable_*) ;; *) die "expected a key starting with sb_publishable_" ;; esac
    else
      die "identity settings missing: pass --supabase-url and --supabase-key (docs/SELF_HOSTING.md, 'Sign-in')"
    fi
  fi
  if [ -n "$SUPABASE_URL" ]; then
    [ -n "$SUPABASE_KEY" ] || [ "$DRY" = 1 ] || die "--supabase-key is needed together with --supabase-url"
    if [ "$DRY" = 1 ]; then plan "write SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to deployment/local/.env"
    else set_env SUPABASE_URL "$SUPABASE_URL"; set_env SUPABASE_PUBLISHABLE_KEY "$SUPABASE_KEY"; fi
  fi
  if [ -n "$PORT" ]; then
    if [ "$DRY" = 1 ]; then plan "set GATEWAY_PORT=$PORT"; else set_env GATEWAY_PORT "$PORT"; fi
  fi
  for kv in "${SETS[@]+"${SETS[@]}"}"; do
    if [ "$DRY" = 1 ]; then plan "set ${kv%%=*}"; else set_env "${kv%%=*}" "${kv#*=}"; fi
  done
  if [ "$DRY" = 1 ]; then plan "generate random MYSQL_*, MQTT_* and API_TOKEN values where empty (done by scripts/local.sh up)"; fi

  step "3. Build and start (first run: about 5 to 15 minutes)"
  if [ "$DRY" = 0 ] && ! docker ps -q --filter "label=com.docker.compose.project=$proj" | grep -q . ; then
    local p
    for p in "$want_port" "$(env_value MQTT_HOST_PORT)" "$(env_value BACKEND_HOST_PORT)"; do
      [ -z "$p" ] || ! tcp_open "$p" || die "port $p on this computer is already in use; choose another with --port or --set (MQTT_HOST_PORT / BACKEND_HOST_PORT)"
    done
  fi
  if [ "$DRY" = 1 ]; then plan "bash scripts/local.sh up   (project $proj, console port $want_port)"
  else bash "$ROOT/scripts/local.sh" up; fi

  step "What you have"
  echo "   Console and API:   http://localhost:$want_port"
  echo "   Project:           $proj  (stop: bash scripts/local.sh down; data is kept)"
  echo
  echo "   First account (about 2 minutes):"
  echo "     1. Open the console above, sign up, and confirm the email if Supabase asks."
  echo "     2. bash scripts/local.sh admin you@example.org      # makes that account the platform admin"
  echo "     3. Create a study in the console (docs/STUDY_MANAGER_GUIDE.md)."
  echo
  echo "   Phone:"
  echo "     Same Wi-Fi:      use the http://<lan address>:$want_port line printed above."
  echo "                      Build for it with: bash scripts/local.sh phone http://<lan address>:$want_port"
  echo "     Anywhere (HTTPS) install Tailscale on the computer and phone, then"
  echo "                        tailscale serve --bg $want_port    # only your devices"
  echo "                        tailscale funnel --bg $want_port   # public address; turn off: tailscale funnel --bg $want_port off"
  echo "                      and open  https://<machine>.<tailnet>.ts.net/setup  on the phone for the app and setup link."
  echo "   Backups:           bash scripts/local.sh backup    (copy them off this computer too)"
  echo "   Troubleshooting:   docs/TROUBLESHOOTING.md and docs/LOCAL_HOSTING.md"
  [ "$DRY" = 0 ] || echo "   (dry run: nothing was changed)"
}

# ============================================================================================
# vm
# ============================================================================================
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
rssh() { ssh "${SSH_OPTS[@]}" "$TARGET" "$@"; }

# Run a function of scripts/install_vm.sh on the server; VAR=value pairs come first.
remote_fn() { # fn [VAR=value ...]
  local fn="$1"; shift
  local pre="" kv
  for kv in "$@"; do pre+="$(printf '%s=%q ' "${kv%%=*}" "${kv#*=}")"; done
  rssh "${pre}bash -s -- $fn" < "$ROOT/scripts/install_vm.sh"
}

mode_vm() {
  [ -f "$ROOT/scripts/install_vm.sh" ] || die "scripts/install_vm.sh not found"
  [ -z "$KEY" ] || { [ -f "$KEY" ] || die "SSH key not found: $KEY"; SSH_OPTS+=(-i "$KEY"); }
  local host="${TARGET#*@}" user="${TARGET%@*}"
  local app="${APP_DIR:-}"; [ -n "$app" ] || app='$HOME/wearable-app'
  [[ "$app" =~ ^[A-Za-z0-9_./\$-]+$ ]] || die "--app-dir may contain only letters, digits and _ . / - \$"
  [ -z "$SUPABASE_URL" ] && [ "$DRY" = 0 ] && die "--supabase-url is required: the backend verifies sign-ins against your Supabase project"
  if [ -n "$DOMAIN" ]; then
    [[ "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "--domain looks wrong: '$DOMAIN'"
  fi

  step "1. Prerequisites on this computer"
  local missing=0 c
  for c in ssh tar gzip git; do command -v "$c" >/dev/null 2>&1 || { echo "   missing: $c"; missing=1; }; done
  if [ "$IMAGES" = 1 ]; then
    command -v docker >/dev/null 2>&1 || { echo "   missing: docker (it builds the images that are shipped)"; missing=1; }
    command -v docker >/dev/null 2>&1 && ! docker info >/dev/null 2>&1 && { echo "   Docker is not running"; missing=1; }
  fi
  if [ "$missing" = 1 ]; then
    [ "$DRY" = 1 ] && note "(dry run: continuing although something is missing)" || die "fix the items above and run this again"
  else note "ssh, tar, gzip, git: ok"; fi
  if [ "$DRY" = 0 ] && [ -n "$(git -C "$ROOT" status --porcelain -- backend deployment scripts 2>/dev/null)" ]; then
    note "warning: uncommitted changes under backend/, deployment/ or scripts/. The server gets the last commit"
    note "         for files, but the image is built from the working copy. Deploy from a clean checkout."
  fi

  local arch="amd64" api_domain="" mqtt_domain=""
  step "2. Server $TARGET"
  if [ "$DRY" = 1 ]; then
    plan "ssh $TARGET: check Debian/Ubuntu, passwordless sudo, CPU architecture, RAM"
    plan "scripts/install_vm.sh prereqs (as root): install docker.io + the compose plugin if missing, add $user to the docker group,"
    plan "                                   open ports 22/80/443 if ufw is active, create 2 GB swap if the server has under 2 GB RAM and no swap"
  else
    rssh true 2>/dev/null || die "cannot SSH to $TARGET without a password prompt. Check the address, add your key (ssh-copy-id) or pass --key"
    local facts; facts=$(remote_fn facts) || die "could not read the server's details"
    echo "$facts" | sed 's/^/   /'
    grep -q '^supported=yes' <<<"$facts" || die "only Debian and Ubuntu servers (apt) are supported by this script; see docs/SELF_HOSTING.md for a manual setup"
    grep -q '^sudo=yes' <<<"$facts" || die "$user needs passwordless sudo on the server (the default on cloud Ubuntu images)"
    case "$(sed -n 's/^arch=//p' <<<"$facts")" in x86_64) arch=amd64 ;; aarch64) arch=arm64 ;; *) die "unsupported CPU architecture on the server" ;; esac
    local ram; ram=$(sed -n 's/^ram_mb=//p' <<<"$facts")
    if [ "${ram:-0}" -lt 1900 ]; then
      note "WARNING: this server has about ${ram} MB RAM. 2 GB or more is recommended (docs/SELF_HOSTING.md, Sizing)."
      note "         Consider --no-papagei, which saves about 300 MB."
    fi
    remote_fn prereqs RUN_AS="$user" || die "preparing the server failed (output above)"
  fi

  step "3. Addresses and TLS"
  if [ -n "$DOMAIN" ]; then api_domain="api.$DOMAIN"; mqtt_domain="mqtt.$DOMAIN"
  else
    local ip="$IP"
    if [ -z "$ip" ] && [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then ip="$host"; fi
    if [ -z "$ip" ] && [ "$DRY" = 0 ]; then ip=$(rssh 'curl -fsS4 --max-time 10 https://api.ipify.org' 2>/dev/null || true); fi
    [ -n "$ip" ] || { [ "$DRY" = 1 ] && ip="<server-ip>" || die "could not work out the server's public IP; pass --ip or --domain"; }
    [[ "$ip" =~ ^([0-9]+\.){3}[0-9]+$ || "$ip" = "<server-ip>" ]] || die "--ip must be an IPv4 address"
    api_domain="api.$ip.nip.io"; mqtt_domain="mqtt.$ip.nip.io"
    note "no --domain given: using nip.io names (they resolve to $ip automatically)"
  fi
  note "API:  https://$api_domain"
  note "MQTT: wss://$mqtt_domain/mqtt"
  if [ "$DRY" = 0 ] && [ -n "$DOMAIN" ]; then remote_fn check_dns API_DOMAIN="$api_domain" MQTT_DOMAIN="$mqtt_domain" || true; fi

  step "4. Copy the deployment files to the server"
  local files=(deployment/cloud scripts backend/schema.sql backend/migrations)
  if [ "$DRY" = 1 ]; then
    plan "tar of ${files[*]} (last commit; secrets and deploy.env excluded) -> $TARGET:$app"
  else
    rssh "mkdir -p $app"
    if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
      git -C "$ROOT" archive --format=tar HEAD "${files[@]}" | rssh "tar -x -C $app"
    else
      tar -C "$ROOT" -c --exclude='.env' --exclude='.env.*' --exclude='deploy.env' --exclude='passwordfile' \
        --exclude='__pycache__' "${files[@]}" | rssh "tar -x -C $app"
    fi
    note "copied (an existing .env on the server is never overwritten)"
  fi

  step "5. Secrets and settings (created on the server, only if .env does not exist)"
  if [ "$DRY" = 1 ]; then
    plan "$app/deployment/cloud/.env from .env.example: random MYSQL_*, MQTT_*, API_TOKEN, MQTT_USER_SECRET; API_DOMAIN=$api_domain; SUPABASE_URL=<yours>"
    plan "AUTH_ALLOW_SHARED_TOKEN=false, ML_INFERENCE_ENABLED=$([ "$PAPAGEI" = 1 ] && echo true || echo false), BACKUP_DIR under the server user's home; file mode 600"
  else
    remote_fn configure APP_DIR="$app" API_DOMAIN="$api_domain" MQTT_DOMAIN="$mqtt_domain" SUPABASE_URL="$SUPABASE_URL" \
      PAPAGEI="$PAPAGEI" || die "writing the server's settings failed (output above)"
  fi

  local services="mysql mosquitto backend caddy"; [ "$PAPAGEI" = 1 ] && services="$services papagei"
  step "6. Images"
  if [ "$IMAGES" = 0 ]; then note "skipped (--skip-images)"
  elif [ "$DRY" = 1 ]; then
    plan "docker build --platform linux/<server arch> -f deployment/cloud/Dockerfile -t cloud-backend:latest ."
    [ "$PAPAGEI" = 0 ] || plan "docker build --platform linux/<server arch> -f deployment/cloud/papagei.Dockerfile -t cloud-papagei:latest ."
    plan "docker save | gzip | ssh $TARGET 'docker load'   (previous images are kept as :previous for rollback)"
  else
    local sha; sha="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    local imgs=(cloud-backend:latest)
    ( cd "$ROOT" && docker build --platform "linux/$arch" --build-arg "GIT_SHA=$sha" \
        -f deployment/cloud/Dockerfile -t cloud-backend:latest . ) || die "backend image build failed"
    if [ "$PAPAGEI" = 1 ]; then
      ( cd "$ROOT" && docker build --platform "linux/$arch" -f deployment/cloud/papagei.Dockerfile -t cloud-papagei:latest . ) \
        || die "PaPaGei image build failed"
      imgs+=(cloud-papagei:latest)
    fi
    echo "   shipping ${imgs[*]} (a few minutes on a slow link)"
    docker save "${imgs[@]}" | gzip -1 | rssh \
      'for i in cloud-backend cloud-papagei; do docker tag $i:latest $i:previous 2>/dev/null || true; done; gunzip -c | docker load' \
      || die "shipping the images failed"
  fi

  step "7. Start the stack"
  if [ "$DRY" = 1 ]; then
    plan "docker compose up -d --no-build $services   (in $app/deployment/cloud)"
    plan "wait for the backend to answer on 127.0.0.1:8080/health (200 or 401)"
  else
    remote_fn start APP_DIR="$app" SERVICES="$services" || die "the stack did not become healthy; on the server: cd $app/deployment/cloud && docker compose logs --tail=80 backend"
  fi

  step "8. Broker accounts and scheduled jobs"
  if [ "$DRY" = 1 ]; then
    plan "bash scripts/sync_mqtt_users.sh --force   (writes the backend's broker account)"
    plan "sudo APP_DIR=$app RUN_AS=$user bash scripts/install_server_timers.sh   (nightly backup 03:15, broker accounts every minute)"
  else
    remote_fn timers APP_DIR="$app" RUN_AS="$user" || die "installing the broker accounts and timers failed (output above)"
  fi

  step "9. Check HTTPS"
  if [ "$DRY" = 1 ]; then plan "ask the server to fetch https://$api_domain/health (a 401 is correct: it needs a signed-in user)"
  else remote_fn check_https API_DOMAIN="$api_domain" || note "Not answering yet. Caddy needs ports 80 and 443 reachable from the internet; see the checklist below."; fi

  step "Checklist"
  cat <<EOF
   Server:    $TARGET   (files in $app)
   API:       https://$api_domain
   Broker:    wss://$mqtt_domain/mqtt

   Still to do by hand:
   [ ] Open TCP 80 and 443 (and 22) in your provider's firewall / security list. A server's own
       firewall is handled here; the cloud provider's is not.
   [ ] Console: deploy frontend/ to Vercel (or any static host) with
         VITE_BACKEND_URL=https://$api_domain
         VITE_SUPABASE_URL=${SUPABASE_URL:-https://<ref>.supabase.co}
         VITE_SUPABASE_PUBLISHABLE_KEY=<your publishable key>
       then add that address to Supabase > Authentication > URL configuration.
   [ ] Sign up once in the console, then make yourself administrator:
         ssh $TARGET 'cd $app/deployment/cloud && docker compose exec backend wearable-backend set-platform-role you@example.org platform_admin'
   [ ] Phone: install the app, then open  <console address>/setup  on the phone for the setup link.
       (docs/APK_DISTRIBUTION.md). To bake the server in: scripts/release_apk.sh --backend https://$api_domain --broker wss://$mqtt_domain/mqtt
   [ ] Off-server backups: put VM_HOST=$TARGET and VM_KEY=<your key path> in deployment/cloud/deploy.env
       (gitignored), then run scripts/pull_backups.sh now and then. Nightly dumps stay on the server for 14 days.
   [ ] Later updates: scripts/deploy_cloud_backend.sh (docs/DEPLOY_RUNBOOK.md), or run this command again.
   Details and troubleshooting: docs/SELF_HOSTING.md, docs/TROUBLESHOOTING.md
EOF
  [ "$DRY" = 0 ] || echo "   (dry run: nothing was changed and no server was contacted)"
}

case "$MODE" in
  local) mode_local ;;
  vm) mode_vm ;;
esac
