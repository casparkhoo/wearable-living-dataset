# Hosting your own instance

One script runs the whole system, either on your own computer or on a server you rent:

```bash
scripts/deploy.sh local                  # Docker on this computer
scripts/deploy.sh vm ubuntu@203.0.113.10 # a fresh Debian/Ubuntu server, over SSH
```

Both are safe to run again (they keep your secrets and data, and a re-run is also how you update),
and both take `--dry-run` to print the plan without changing anything. Nothing here depends on the
original deployment; every value is yours.

| | `local` | `vm` |
|---|---|---|
| Good for | trying it, development, pilots, your own recordings | studies with participants |
| Runs on | your computer, in Docker | a server with a public IP |
| Time | 10 minutes (first build 5 to 15) | 30 to 60 minutes (mostly the first image build) |
| Cost | free | free tier or about 5 to 12 USD a month ([sizing](#sizing-and-cost)) |
| HTTPS for phones | LAN address, or Tailscale | automatic, through Caddy |
| Detail | [LOCAL_HOSTING.md](LOCAL_HOSTING.md) | this page |

```text
 phones ──WSS──▶ gateway / Caddy ──▶ Mosquitto ──▶ backend ──▶ MySQL
 console ─HTTPS─▶ gateway / Caddy ──▶ backend ──▶ PaPaGei worker
 sign-in ───────▶ Supabase (accounts only; roles and data stay on your server)
```

## Prerequisites

- **Both modes:** bash (built in on Linux and macOS; Git Bash on Windows), and **Docker** running on
  the machine you run the script on (Docker Desktop, or Docker Engine with the compose plugin).
- **A free [Supabase](https://supabase.com) project for sign-in.** It holds accounts only, never
  sensor data. See [Sign-in](#sign-in-supabase) below; you need its project URL and
  *publishable* key.
- **`vm` mode also:** `ssh`, `tar` and `gzip` on your computer; a Debian or Ubuntu server you can
  reach over SSH with a key, with passwordless `sudo` (the default on cloud Ubuntu images); and
  ports 80 and 443 open to the internet in the provider's firewall. Docker does not need to be
  installed on the server; the script installs it.
- To record: an Android phone, and the app ([Phone setup](#phone-setup)).

## Sign-in (Supabase)

1. Create a project on the **Free** plan, in the region closest to your participants (identity
   data lives there; the region cannot be changed later).
2. **Authentication → Providers**: keep Email enabled. Decide whether to require email
   confirmation (recommended for a study).
3. **Project Settings → API**: note the project URL (`https://<ref>.supabase.co`) and the
   **publishable** key (`sb_publishable_...`). Never use the secret or service-role key anywhere
   in this system; the script refuses it.
4. After the console has an address (below), put it into **Authentication → URL configuration**
   as the Site URL and a redirect URL.

Free projects pause after about a week without sign-ins; a paused project cannot issue tokens, so
nobody can sign in. Sign in weekly during data collection, or upgrade for the study window. See
[reference/STUDY_API.md](reference/STUDY_API.md#supabase-configuration-and-keeping-it-free).

## One command: on your computer

```bash
scripts/deploy.sh local --supabase-url https://<ref>.supabase.co --supabase-key sb_publishable_...
```

(Without the flags it asks, or reads them from `deployment/local/.env`.) It checks Docker, creates
`deployment/local/.env` with random passwords (gitignored; never commit it), builds and starts the
stack, waits until the backend is healthy and prints the addresses. Options: `--port`, `--project`,
`--set KEY=VALUE`; see `scripts/deploy.sh --help`.

You get the console at `http://localhost:8088`, the API and the broker behind the same port, and
`scripts/local.sh` for everyday control (`status`, `logs`, `down`, `backup`, `restore`).

## One command: on a server

```bash
scripts/deploy.sh vm ubuntu@203.0.113.10 --key ~/.ssh/id_ed25519 \
    --supabase-url https://<ref>.supabase.co --domain study.example.org
```

- **`--domain study.example.org`** serves `https://api.study.example.org` and
  `wss://mqtt.study.example.org/mqtt`. Create two `A` records pointing at the server first.
  Without `--domain` the script uses `api.<ip>.nip.io` and `mqtt.<ip>.nip.io`, which resolve to the
  IP for free (reserve the IP first if your provider makes it ephemeral, because the hostnames,
  the certificates and every built app embed it).
- **`--no-papagei`** leaves out the model worker (about 300 MB); use it on a server under 2 GB.
- Run it with `--dry-run` first to read the plan; it contacts nothing.

What it does, in order:

1. checks the server (Debian/Ubuntu, sudo, CPU, RAM) and installs Docker and the compose plugin
   from apt if missing; opens 22/80/443 if `ufw` is active; adds 2 GB of swap on a small server;
2. builds the backend (and PaPaGei) image **on your computer** for the server's CPU and ships it,
   because compiling the backend on a 1 GB server takes about forty minutes;
3. copies `deployment/cloud`, `scripts/` and the schema to `~/wearable-app` on the server (from
   the last commit; never your `.env` or `deploy.env`);
4. creates `deployment/cloud/.env` **on the server** with random secrets and your hostnames,
   only if it does not exist (mode 600, never printed, never on your computer);
5. starts MySQL, Mosquitto, the backend, PaPaGei and Caddy (TLS certificates are automatic) and
   waits for the backend;
6. creates the broker accounts and installs the systemd timers: broker accounts for new
   participants every minute, and a database dump nightly at 03:15 that keeps 14 days;
7. checks HTTPS and prints a checklist. The part the script cannot do: open ports 80 and 443 in the
   provider's firewall, deploy the console, make yourself admin.

Running it against the same server again updates files and images and leaves `.env`, data and
secrets alone. To ship only a new backend build, use [DEPLOY_RUNBOOK.md](DEPLOY_RUNBOOK.md).

### The console

The console is a static site in `frontend/` (the `local` mode serves it itself). For a server, host
it on Vercel, Netlify or any static host: import the repository, set the root directory to
`frontend`, build command `npm run build`, output `dist`, and these variables:

```text
VITE_BACKEND_URL=https://<API_DOMAIN>
VITE_SUPABASE_URL=https://<ref>.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

Then put its address in Supabase's Site URL (sign-in step 4).

## First run

1. Open the console and sign up. New accounts start as `participant`.
2. Make yourself the platform administrator:

   ```bash
   scripts/local.sh admin you@example.org                    # local mode
   ssh ubuntu@<server> 'cd ~/wearable-app/deployment/cloud && \
     docker compose exec backend wearable-backend set-platform-role you@example.org platform_admin'
   ```
3. Create a study and invite people: [STUDY_MANAGER_GUIDE.md](STUDY_MANAGER_GUIDE.md).

## Phone setup

One APK serves any instance. The console's **Get the app** tab offers the APK together with a
**setup link and QR code** that tells the installed app which server to use (the link carries no
secrets; the app shows the host and asks before saving it). Full details:
[APK_DISTRIBUTION.md](APK_DISTRIBUTION.md).

- **Server:** install the APK, then open `<console address>/setup` on the phone and follow the link.
  To publish the APK, build it with `scripts/release_apk.sh` and set `VITE_APK_URL` (and the
  version, checksum and size variables it prints) on the console host.
- **Local, same Wi-Fi:** `scripts/local.sh phone http://<computer's LAN address>:8088` builds the app
  for it and installs it over USB. Some networks (university, guest Wi-Fi) block devices from
  reaching each other; use a tunnel.
- **Local, from anywhere:** put HTTPS in front. With Tailscale on the computer and the phone,
  `tailscale serve --bg 8088` keeps it to your own devices; `tailscale funnel --bg 8088` gives a
  public address (turn it off with `tailscale funnel --bg 8088 off`). Then open
  `https://<machine>.<tailnet>.ts.net/setup` on the phone. [LOCAL_HOSTING.md](LOCAL_HOSTING.md)
  also covers Cloudflare Tunnel and switching one app between servers.
- Building the app yourself: `cd android-application/android`, copy `instance.properties.example`
  to `instance.properties` (gitignored), set `supabaseUrl`, `supabasePublishableKey`,
  `cloudBackendUrl` and `cloudBrokerUrl`, then `./gradlew :app:assembleDebug`. If you build from the
  command line where Android Studio never opened the project, set `ANDROID_HOME` or add
  `sdk.dir=/path/to/Android/Sdk` to `local.properties`. For anyone outside your team, distribute a
  release-signed build rather than a debug APK.

## Check it end to end

1. Sign in on the phone with a test participant; within a minute the app connects to the broker
   (a timer or the `broker-accounts` container creates its account).
2. As a researcher, create a study, invite the test account, consent as the participant.
3. Record a minute with a ring; watch it arrive live in the console.
4. Export the experiment and recompute it ([RESEARCHER_DATA_GUIDE.md](RESEARCHER_DATA_GUIDE.md)).
5. Take a backup and prove it restores (below).

## Updating

- **Local:** `git pull`, then `scripts/deploy.sh local` (it rebuilds what changed).
- **Server, everything:** `git pull`, then run the same `scripts/deploy.sh vm ...` command again.
- **Server, a new backend only (fast):** `scripts/deploy_cloud_backend.sh` builds locally, ships
  the image and restarts only the backend. Copy `deployment/cloud/deploy.env.example` to
  `deploy.env` (gitignored) and set `VM_HOST` and `VM_KEY`. Procedure, verification and rollback:
  [DEPLOY_RUNBOOK.md](DEPLOY_RUNBOOK.md).
- **Database migrations** in `backend/migrations/` are applied by hand when an upgrade adds one.
  The backend refuses to start, naming the file, if a migration it needs is missing; each
  migration is safe to re-run:

  ```bash
  docker compose exec -T mysql sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
    < ../../backend/migrations/<file>.sql
  ```

## Backups and restore

| | Local | Server |
|---|---|---|
| Take one | `scripts/local.sh backup` | automatic, nightly 03:15, kept `BACKUP_KEEP_DAYS` (14) in `~/wearable-backups`; or `scripts/backup_db.sh` on the server |
| Copy off the machine | copy `backups/` | `scripts/pull_backups.sh` from your computer (needs `deploy.env`), weekly |
| Restore | `scripts/local.sh restore <dump.sql.gz>` | `scripts/restore_test.sh` proves a dump restores into a scratch database |

A dump on the same disk does not survive losing the disk, so copy dumps elsewhere. Local and server
dumps share one format, so data moves both ways; a restore replaces tables of the same name, so
restore into a fresh stack or take a backup first. Run `COMPARE_LIVE=1 scripts/restore_test.sh`
every few weeks. Erased participants' data remains in older dumps until they rotate out; say how
long that is in the consent form.

## Sizing and cost

| Machine | Use |
|---|---|
| **2 GB RAM or more, 2 vCPU, 20 GB disk** (recommended minimum) | MySQL, backend, PaPaGei and Caddy with headroom for a handful of concurrent participants |
| 1 GB (the original demo VM) | Works only with swap and `--no-papagei` or the 300 MB PaPaGei cap, and runs permanently at its memory limit. |
| Your own computer | Docker needs about 4 GB of RAM free for the first build and 2 GB afterwards |

The cloud demo VM has 954 MB of RAM and runs in constant swap. It showed no outage, but it slows
down under a heavy recording and recordings coincided with kernel memory-pressure messages; see
[ORACLE_OVERLOAD_NOTES.md](ORACLE_OVERLOAD_NOTES.md). **Use at least 2 GB** for anything with
participants. One hour of ring capture is roughly 240 MB in the database, so budget disk for
the study length.

Typical cost: a 2 GB VPS is about 5 to 12 USD a month (Hetzner, DigitalOcean, Lightsail and
similar). Oracle Cloud's Always Free tier is free: the `VM.Standard.E2.1.Micro` is the 1 GB
machine above; the `VM.Standard.A1.Flex` Ampere shape (up to 24 GB) is much more comfortable, and
`deploy.sh` builds for the server's CPU automatically. Supabase's free tier is enough (mind the
pause rule above); Vercel's free tier hosts the console.

## Secrets

`.env`, `deploy.env`, `instance.properties`, SSH keys and Empatica keys are gitignored; keep them
that way. `deploy.sh` generates every password on the machine it belongs to and never prints it.
Rotating `MQTT_USER_SECRET` invalidates every participant's broker password: run
`scripts/sync_mqtt_users.sh --force`, and phones fetch theirs again on next sign-in. Set
`AUTH_ALLOW_SHARED_TOKEN=false` (the server default here) so every request belongs to a signed-in
person.

## Doing it by hand

`deploy.sh vm` automates [deployment/cloud](../deployment/cloud/README.md); to do it manually:
install Docker, copy the repository, `cp deployment/cloud/.env.example deployment/cloud/.env`
(every `replace-with-…` value a new `openssl rand -hex 32`; set `API_DOMAIN`, `MQTT_DOMAIN`,
`SUPABASE_URL`, `AUTH_ALLOW_SHARED_TOKEN=false`), `touch deployment/cloud/mosquitto/passwordfile`,
`docker compose up -d --build` (about 40 minutes on 1 GB), then
`scripts/sync_mqtt_users.sh --force` and
`sudo APP_DIR=$HOME/wearable-app RUN_AS=$USER scripts/install_server_timers.sh`.

## Troubleshooting

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) covers the usual problems (phone offline, sign-in
failing, broker accounts, certificates). Quick checks: `scripts/local.sh status` and
`scripts/local.sh logs backend` locally; on a server `cd ~/wearable-app/deployment/cloud &&
docker compose ps && docker compose logs --tail=80 backend`. A `/health` answer of `401` is
normal: it needs a signed-in user.

**Reference device (optional).** To score heart rate against an Empatica EmbracePlus, save your
organisation's access keys outside the repository (`scripts/save_empatica_keys.ps1` writes
`%USERPROFILE%\.thesis\empatica.env`) and use `scripts/empatica_s3.py`; see the
[researcher guide](RESEARCHER_DATA_GUIDE.md).
