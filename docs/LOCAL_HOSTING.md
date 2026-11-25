# Running everything on your own computer

The whole system (database, broker, backend, PaPaGei worker and web console) can run on one
computer in Docker, with one command: `scripts/deploy.sh local`. This page is the detail behind the
`local` mode of [SELF_HOSTING.md](SELF_HOSTING.md), the main guide. This is the quickest way to try the system, to develop
on it, or to collect your own recordings without renting a server.

| | Your computer | A cloud server ([SELF_HOSTING.md](SELF_HOSTING.md)) |
|---|---|---|
| Setup | about 10 minutes | about an hour |
| Cost | free | free (Oracle Always Free) or about 5 to 12 USD a month |
| Capacity | as much as the computer has | at least 2 GB RAM recommended ([sizing](SELF_HOSTING.md#sizing-and-cost)) |
| Reachable | while the computer is on and awake | always |
| Good for | development, pilots, your own data | studies with participants |

Both use the same backend, database schema and phone app, so recordings move between them as
ordinary backups (step 6).

## What you need

- **Docker Desktop** (Windows or macOS) or Docker Engine with the compose plugin (Linux), running.
- **bash**: built in on Linux and macOS; on Windows, Git Bash (installed with Git for Windows).
- **A Supabase project** for sign-in. It holds only accounts, never sensor data, and the free
  tier is enough. If you already have a cloud instance, reuse its project: the same accounts then
  sign in to both. ([SELF_HOSTING.md](SELF_HOSTING.md#sign-in-supabase) explains creating one.)
- To record from a phone: the Android SDK (or Android Studio) to build the app, and either the
  same Wi-Fi network as the computer or a tunnel (step 4).

## 1. Settings

`scripts/deploy.sh local --supabase-url <url> --supabase-key <key>` writes these for you (it asks if you
leave the flags out). To do it by hand:

```bash
cp deployment/local/.env.example deployment/local/.env
```

Edit `deployment/local/.env` and set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` (Supabase →
Project Settings → API). Both are public by design. Leave every password empty: the next step
generates them. If `android-application/android/instance.properties` already exists, step 2 reads
the Supabase values from it and you can skip this step.

Never put a Supabase *secret* or *service_role* key here.

`LIVE_HR_SOURCE` chooses the heart rate participants see: `papagei` (the default, falling back
to the rules when the model has no fresh estimate) or `rules`. Both are stored either way.

## 2. Start it

```bash
scripts/deploy.sh local        # checks Docker, then runs scripts/local.sh up
```

The first run builds the images (about 5 minutes) and generates random passwords into `.env`.
When the backend is healthy it prints where to find everything:

```text
Console on this computer:   http://localhost:8088
From a phone on the same network (pick this computer's Wi-Fi address):
    http://192.168.1.217:8088
```

Open the console, create an account or sign in, then make yourself an administrator so you can
create studies:

```bash
scripts/local.sh admin you@example.org
```

Everything is behind one port (8088 by default):

| Path | What |
|---|---|
| `/` | web console |
| `/api/…`, `/health` | backend |
| `/mqtt` | broker over WebSockets, used by the phone |

Participants get their own broker account automatically within a minute of first signing in
(the `broker-accounts` container does this; on a cloud server a timer does).

## 3. The phone app

Build and install the app pointed at this computer, with the phone connected over USB with
debugging on:

```bash
scripts/local.sh phone http://192.168.1.217:8088
```

Use the address `up` printed for your Wi-Fi adapter. Without an address the script picks the
first one it finds. The app then signs in, fetches its broker account and records exactly as it
does against a cloud server.

If the phone cannot connect: the computer's firewall may block port 8088 from the network. On
Windows, allow Docker Desktop through the firewall for private networks when it asks, or add an
inbound rule for TCP 8088. Some networks (university, guest Wi-Fi) block devices from reaching
each other; use a tunnel instead.

## 4. Reaching it from anywhere (tunnel)

A phone on mobile data, or on another network, needs a tunnel. Both options below give an HTTPS
address with a valid certificate, and need no router changes.

**Tailscale** (recommended for your own phones): install Tailscale on the computer and the phone
and sign in to both with the same account. Then, on the computer:

```bash
tailscale serve --bg 8088
tailscale serve status        # shows https://<machine>.<tailnet>.ts.net
scripts/local.sh phone https://<machine>.<tailnet>.ts.net
```

Only devices on your tailnet can reach it.

**Cloudflare Tunnel** (for other people's phones): `cloudflared tunnel --url http://localhost:8088`
prints a public `https://….trycloudflare.com` address; build the app with it the same way. Anyone
with the address can reach the sign-in page, so use it only while you need it.

### Switching the phone between cloud and local

You do not need a second build. One installed app keeps named servers ("profiles"):

1. Open the app and tap **Switch server** (sign-in screen) or the server line at the top of the
   main screen, or **Settings > Switch server**. The current server is always shown there.
2. **Add server**, then paste a setup link or type this computer's address
   (for example the `https://<machine>.<tailnet>.ts.net` address from Tailscale). The app shows the
   host and asks before using it. Leave **Save as profile** ticked and call it "Local".
3. From then on, tapping **Cloud** or **Local** in the list switches in one step. The app signs
   you out, removes the stored broker login and token of the old server, and shows the sign-in
   screen; sign in again on the new server. A recording must be stopped first.

The built-in **Cloud** entry exists only when the APK was built with a default server
(`scripts/release_apk.sh --backend ...`). Profiles are stored on the phone only.

### A hosted console pointed at this computer

The console is also a static site, so it can be hosted (for example on Vercel) and pointed
at this computer, which is useful for showing it on another machine:

1. Make the instance reachable from the internet: `tailscale funnel --bg 8088` gives a public
   HTTPS address (turn it off with `tailscale funnel --bg 8088 off`).
2. Build the console with `VITE_BACKEND_URL` set to that address, and `VITE_SUPABASE_URL` and
   `VITE_SUPABASE_PUBLISHABLE_KEY` set as in `.env` (without these two, sign-in is hidden).
   An already hosted console can instead be pointed at it under Settings.

The backend accepts requests from any origin, with the sign-in header and private-network
access allowed explicitly (browsers do not let a wildcard cover them). A browser on the
computer that runs the instance reaches it over Tailscale's private address rather than
through Funnel; if a hosted console misbehaves there, open the instance's own address instead
(it serves the same console), or test from a device without Tailscale.

## 5. Everyday commands

```bash
scripts/local.sh status          # containers, health, addresses
scripts/local.sh logs backend    # follow one service's log (mysql, mosquitto, gateway, papagei, broker-accounts)
scripts/local.sh down            # stop; recordings are kept
scripts/local.sh up              # start again (also after pulling new code: it rebuilds)
```

The computer must stay on and awake while recording. On a laptop, set it not to sleep when
plugged in.

## 6. Backups and moving data

```bash
scripts/local.sh backup                                   # backups/local-<time>.sql.gz and checksum
scripts/local.sh restore backups/wearable-<time>.sql.gz   # load a backup, from here or a cloud server
```

Backups from a cloud server (`scripts/pull_backups.sh`) and from this stack have the same format,
so data moves both ways. A restore replaces tables of the same name, so restore into a fresh
stack, or take a backup first. Copy backups off the computer as well: a backup on the same disk
does not survive losing the disk.

To delete everything local (every recording and setting in Docker):

```bash
scripts/local.sh reset           # asks you to type DELETE
```

## 7. Tests and tools against the local stack

The broker's plain MQTT port and the backend are also published on this computer only
(`127.0.0.1:11883`, `127.0.0.1:18080`; uncommon ports, so they do not clash with a broker or server you already run), for scripts such as the fault-injection test and the
dataset exports described in [RESEARCHER_DATA_GUIDE.md](RESEARCHER_DATA_GUIDE.md).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ports are not available` on `up` | Another program uses 8088, 11883 or 18080. Set `GATEWAY_PORT`, `MQTT_HOST_PORT` or `BACKEND_HOST_PORT` in `.env` and run `up` again. |
| Console: "the backend did not recognise the session" | The backend could not check your sign-in: see `scripts/local.sh logs backend`. Check `SUPABASE_URL` matches the project you signed in to. |
| Phone: "offline" | Wrong address in the app, firewall, or the computer is asleep. Open `http://<address>:8088/health` in the phone's browser: any answer (even 401) means it can reach the computer. |
| Phone signs in but nothing records | The broker account is created within a minute of first sign-in; wait, then try again. `scripts/local.sh logs broker-accounts` shows it. |
| Settings changed in `.env` but nothing happened | Run `scripts/local.sh up` again; it recreates what changed. Changing the Supabase values needs `up` too, because they are built into the console. |
