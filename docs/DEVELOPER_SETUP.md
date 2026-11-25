# Developer setup

How to get each part of the system building, running and tested on a development machine.
If you only want to run the whole system, use `scripts/local.sh up`
([LOCAL_HOSTING.md](LOCAL_HOSTING.md)); you need nothing below for that except Docker and bash.

Commands are given for bash (Linux, macOS, Git Bash on Windows). Run them from the repository
root unless a `cd` says otherwise.

## What to install

| Component | Needs | Version used in this repo |
|---|---|---|
| Whole stack in Docker | Docker with the compose plugin, bash | Docker Desktop or Engine; images `mysql:8.4`, `eclipse-mosquitto:2`, `caddy:2` |
| Backend | Rust toolchain (rustup). A MySQL 8 server only to run it, not to test it | CI and the Docker image use Rust 1.88; the backend builds with that or newer |
| Web console | Node.js and npm | Node 20 (the Docker build and CI use it) |
| Android app | JDK 17 or newer, Android SDK (platform 34), Gradle wrapper (included) | Gradle 9.0.0 (wrapper), Android Gradle Plugin 8.13.2, `compileSdk 34`, `minSdk 26`, Java 8 source level |
| PaPaGei worker and scripts | Python | 3.11 (the worker image uses it) |

Gradle 9 needs a JDK 17 or newer to run; the app is compiled at Java 8 source level, so you do not
need an old JDK. Set `ANDROID_HOME` (or put `sdk.dir=...` in
`android-application/android/local.properties`, which is gitignored) and accept the SDK licences
(`sdkmanager --licenses`).

## Get the code

```bash
git clone --recurse-submodules <your clone URL>
```

`ml-inference-server/papagei` is a git submodule (Nokia Bell Labs' PaPaGei code, BSD 3-Clause).
If you cloned without `--recurse-submodules`, run `git submodule update --init`. The Rust, Android
and console builds do not need it; the worker and the head-training scripts do.

## Configuration files (never committed)

| File | Created from | Holds |
|---|---|---|
| `deployment/local/.env` | `deployment/local/.env.example`, filled by `scripts/local.sh up` | Passwords for the local stack, Supabase URL and publishable key |
| `backend/.env` | `backend/.env.example` | MySQL, MQTT, Supabase and model-worker settings for a backend run by hand |
| `frontend/.env.local` | `frontend/.env.example` | `VITE_BACKEND_URL`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY` |
| `android-application/android/instance.properties` | `instance.properties.example` | The Supabase project, backend URL and broker URL the app is built for |
| `deployment/cloud/deploy.env` | `deploy.env.example` | Where and how to deploy to a server |

All of these are listed in `.gitignore`. Instance addresses, keys and passwords come from these
files or from the environment, never from source code.

## Run the whole stack (quickest way to a working system)

```bash
cp deployment/local/.env.example deployment/local/.env     # set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY
scripts/local.sh up          # build and start MySQL, Mosquitto, backend, PaPaGei, gateway; prints addresses
scripts/local.sh status
scripts/local.sh logs backend
scripts/local.sh down
```

The console is then on http://localhost:8088. The broker's plain MQTT port and the backend are
also published on this computer only (`127.0.0.1:11883`, `127.0.0.1:18080`), which is what
`scripts/live_fault_injection.py` and other scripts use. After changing backend code,
`scripts/local.sh up` rebuilds the image.

## Backend (Rust)

```bash
cd backend
cargo build
cargo test            # unit tests; no database, broker or network needed
```

To run it outside Docker you need MySQL 8 and a broker:

1. Copy `backend/.env.example` to `backend/.env` and set the MySQL and MQTT values.
2. Create the schema. On Windows, `backend/setup_database.ps1` reads `backend/.env`, applies
   `schema.sql` and the idempotent migrations with the `mysql` client. Elsewhere, load
   `backend/schema.sql` yourself (`mysql ... < backend/schema.sql`); `migrations/` only matters for
   a database created before the migration was added.
3. `cargo run` starts the server on `BACKEND_PORT` (8080 by default).

Leaving `SUPABASE_URL` unset runs it without user sign-in, for local development. The
`ML_INFERENCE_ENABLED`, `ML_INFERENCE_URL` and `ML_LIVE_HEAD_ID` settings switch the PaPaGei
worker on; the backend works without it. The binary also has subcommands (`recompute`, `export`,
`set-platform-role`, `erase-participant`, `retention-report`); run it with an unknown argument to
see the usage text.

Where things are: `src/main.rs` (routes, ingest, config), `src/live.rs` (Live Analysis Engine),
`src/derived.rs` (derived windows and recompute), `src/studies.rs`, `src/auth.rs`,
`src/erasure.rs`, `src/export.rs`. Unit tests sit beside the code in `#[cfg(test)]` modules.

## Web console (React, TypeScript, Vite)

```bash
cd frontend
npm ci
cp .env.example .env.local     # then set the three VITE_ values
npm run dev                    # Vite dev server
npm run build                  # type-check (tsc) and production build into dist/
```

Point `VITE_BACKEND_URL` at a running backend, for example `http://localhost:8088` for the local
stack. Without both Supabase values the sign-in is hidden. The console is also packaged as a
desktop app with Tauri (`npm run tauri:dev`, `npm run tauri:build`); that needs the Rust toolchain
plus Tauri's platform prerequisites. If `package.json` defines a `test` script, `npm test` runs the
console's unit tests ([TESTING.md](TESTING.md)).

## Android app

```bash
cd android-application/android
cp instance.properties.example instance.properties     # your Supabase project and server addresses
./gradlew :app:testDebugUnitTest      # JVM unit tests; no phone or Android device needed
./gradlew :app:assembleDebug          # build a debug APK
```

`instance.properties` is read at build time and compiled into the app, so an APK only works for
the instance it was built for ([TROUBLESHOOTING.md](TROUBLESHOOTING.md)). For the local stack
use `scripts/local.sh phone <url>`, which builds with the right addresses and installs over adb
if a phone is connected. The unit tests build without `instance.properties` too (the settings
then compile in as empty strings).

Source is under `app/src/main/java/com/wearable/controller/`; wearable adapters are in
`controller/impl/` ([ADDING_A_DEVICE.md](ADDING_A_DEVICE.md)).

## PaPaGei worker and Python scripts

The worker is a Flask app (`ml-inference-server/app.py`). In Docker it runs on ONNX Runtime with no
PyTorch (`deployment/local/docker-compose.yml`, `deployment/cloud/papagei.Dockerfile`). To run it
by hand:

```bash
cd ml-inference-server
python -m venv venv && . venv/bin/activate         # Windows: venv\Scripts\activate
pip install -r requirements.txt                     # PyTorch route; requirements-onnx.txt for ONNX only
python download_weights.py                          # weights/papagei_s.pt
python export_onnx.py                               # optional: weights/papagei_s.onnx, then no PyTorch is imported
python app.py                                       # port 5000; PAPAGEI_PORT, PAPAGEI_RUNTIME=auto|onnx|torch
```

Check it with `curl localhost:5000/health` and `curl localhost:5000/heads`. Point the backend at
it with `ML_INFERENCE_URL`.

The scripts in `scripts/` that talk to a database or a broker (`live_fault_injection.py`,
`train_head_from_rules.py`, `score_methods.py`, `align_reference.py`) need `numpy` and, for
fault injection, `paho-mqtt`; install what a script's imports ask for into your own virtualenv.
`scripts/test_processor_contract.py` and `scripts/validate_processor_contract.py` use only the
standard library.

## Checks before you commit

```bash
cd backend && cargo test
cd ../frontend && npm run build
cd .. && python scripts/test_processor_contract.py
cd android-application/android && ./gradlew :app:testDebugUnitTest
scripts/test_erasure.sh       # only if you touched code that stores or deletes data; needs Docker
```

What each suite covers, and what CI runs, is in [TESTING.md](TESTING.md). If something does not
work, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
