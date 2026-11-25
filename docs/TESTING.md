# Testing

What tests exist, where they live, how to run them, and what CI runs. Tests are found by
location pattern, so new ones are picked up without editing this page; if you add a new *kind*
of suite, add a row here.

| Suite | Where it lives | Run it | Needs |
|---|---|---|---|
| Backend unit tests | `#[cfg(test)]` modules at the bottom of `backend/src/*.rs` | `cd backend && cargo test` | Rust only |
| Console unit tests | `frontend/src/tests/*.test.ts` (Vitest) | `cd frontend && npm test` | Node, `npm ci` |
| Console type-check and build | `frontend/` | `cd frontend && npm run build` | Node |
| Android JVM unit tests | `android-application/android/app/src/test/java/` | `cd android-application/android && ./gradlew :app:testDebugUnitTest` | JDK 17+, Android SDK |
| Script tests | `scripts/tests/test_*.py` | `python -m unittest discover -s scripts/tests -v` | Python, numpy |
| Head manifest tests | `ml-inference-server/tests/test_*.py` | `python -m unittest discover -s ml-inference-server/tests -v` | Python, numpy |
| Processor contract tests | `scripts/test_processor_contract.py` | `python scripts/test_processor_contract.py` | Python standard library |
| Processor contract validation | `scripts/validate_processor_contract.py` | `python scripts/validate_processor_contract.py` | Python standard library |
| Erasure, end to end | `scripts/test_erasure.sh` | `scripts/test_erasure.sh` | Docker, built backend (`cargo build`) |
| Backup restore check | `scripts/restore_test.sh` | `scripts/restore_test.sh [dump.gz]` | Docker, a backup |
| Alert fault injection | `scripts/live_fault_injection.py` | see below | A running backend and broker |

All of the suites above the erasure row run offline: no database, broker, phone, network, model
weights or secrets. Those are the ones CI runs.

## Backend (Rust)

`cargo test` runs the unit tests in `backend/src/`: the Live Analysis Engine (`live.rs`:
signal quality, heart rate, alerts, hold and clear counts, model-estimate display), derived
windows and recompute inputs (`derived.rs`), ingest parsing including gzip batches and the
decompression cap (`main.rs`), topic ownership and broker-credential derivation, authentication
(`auth.rs`), studies and consent rules (`studies.rs`), erasure SQL building (`erasure.rs`) and export
(`export.rs`). They do not touch MySQL or MQTT; code that needs a database is covered by the
erasure script below, or by running the stack.

## Console

`npm test` runs Vitest (`vitest run`) over `frontend/src/tests/`. They test pure logic that the
console relies on (for example the hold-last-good merging of live summaries, and the backend and
app-setup URL helpers). `npm run build` also type-checks the whole console with `tsc`.

## Android

`./gradlew :app:testDebugUnitTest` runs JVM unit tests (JUnit) in
`app/src/test/java/com/wearable/controller/`: decoders and parsers (for example the standard Heart
Rate Measurement), the REST client, and the wearable device registry. They need no phone. The
instrumented-test configuration exists in `build.gradle`, but there are no tests under
`app/src/androidTest` to run. A new device adapter should come with a test that decodes recorded
bytes ([ADDING_A_DEVICE.md](ADDING_A_DEVICE.md)).

## Python

- `scripts/tests/` tests the pure functions of the analysis scripts (reference alignment,
  scoring, the head-training helpers such as time-stretching and ridge fitting, and a plausible-pulse
  check) with synthetic data.
- `ml-inference-server/tests/` checks every head in `ml-inference-server/heads/`: required
  manifest fields, weights shape against the manifest, that the head applies to an embedding of
  the declared size, that the declared label range is plausible, and that head ids are unique. A new head must pass it before you ship it
  ([ADDING_A_MODEL_HEAD.md](ADDING_A_MODEL_HEAD.md)).
- `scripts/test_processor_contract.py` and `scripts/validate_processor_contract.py` check the
  manifests in `backend/processors/` and the schemas and examples in `backend/contracts/`
  against the [processor contract](reference/PROCESSOR_CONTRACT.md), using only the standard
  library.

`python -m pytest <dir>` also works if pytest is installed.

## Erasure, end to end

`scripts/test_erasure.sh` starts a throwaway MySQL 8.4 container loaded with `backend/schema.sql`,
creates two participants in one study (plus a recording of the first in another study), runs
`wearable-backend erase-participant` as a dry run and then for real, and checks that exactly the
first participant's rows in that study are gone, everything else is untouched, and the erasure is
logged. Run it whenever you change code that stores or deletes data. It needs Docker and a built
backend.

## Fault injection (live alerts)

`scripts/live_fault_injection.py` plays a simulated ring into the backend the way a phone does
(REST to start a recording, MQTT for samples), injects scripted faults at known times, and listens
on the status topics. It reports whether each fault raised its alert, how long that took, alerts
raised outside any fault, and heart-rate error on clean segments. Results go to
`outputs/live_eval/<timestamp>/` as JSON and CSV. It is the regression test for alert timing:
re-run it after changing thresholds or `RECIPE_VERSION` in `backend/src/live.rs`.

It needs `paho-mqtt` and a backend with a broker. Against a backend you run yourself without
sign-in (`cargo run` with no `SUPABASE_URL`, and a local Mosquitto):

```bash
python scripts/live_fault_injection.py
```

Against the Docker stack from `scripts/local.sh up`, whose backend requires a signed-in user,
use the stack's published ports, a participant's broker account and an access token, the same flags as
the cloud example in the script's docstring:

```bash
python scripts/live_fault_injection.py --backend http://127.0.0.1:18080 --broker 127.0.0.1:11883 \
    --mqtt-user u<id> --mqtt-password <that account's password> --token "$ACCESS_TOKEN" \
    --topic-prefix sensors/u<id> --status-topic "status/u<id>/#"
```

All of the scripted faults should be detected, with no alerts outside them. Do not paste tokens or
passwords into shared logs or issues.

**Without a sign-in (what the recipe 1.1.0 run used).** The Docker stack's backend only accepts a
signed-in user's token, which needs an account at the hosted identity provider. To avoid that, start
a throwaway second instance on other ports: a fresh MySQL (load `backend/schema.sql` as root), a
Mosquitto with `allow_anonymous true`, and the stack's backend image with no `SUPABASE_URL` and no
`API_TOKEN`, publishing `127.0.0.1:28080` (API) and `127.0.0.1:21883` (broker), with
`ML_INFERENCE_ENABLED=false`. Then, three times with different seeds:

```bash
python scripts/live_fault_injection.py --backend http://127.0.0.1:28080 --broker 127.0.0.1:21883     --seed 42 --out outputs/fault_injection_1_1_0/runs      # also 43 and 44
```

`--poor-fit-extra-s N` lengthens the loose-sensor fault by N seconds (later faults shift). Results for
`live-ppg/1.1.0`, in `outputs/fault_injection_1_1_0/results.md`: 5 of 6 faults detected in 3 of 3
runs, no false alerts. The loose-sensor fault is missed, even at 90 s, because `ppg_quality_low`
needs 45 consecutive weak windows and the simulated noise only reaches 13 in a row. The 6 s stall is
caught 5.0-5.8 s after it starts, so it clears the 5 s threshold by under a second.

**Recipe `live-ppg/1.2.0`.** The same command was run for seeds 42-44 and 51-53 and for the loose-sensor
fault lengthened with `--poor-fit-extra-s 25` and `70` (results and caveats in
`outputs/fault_injection_1_2_0/results.md`). `ppg_quality_low` now counts poor windows over a 60 s span
(raise at 40, clear at 20), so a flickering weak signal is reported (after 56.5 s in the 90 s test) but
the standard 20 s loose-sensor fault is still, by design, not.

**Recipe `live-ppg/1.3.0`.** A poor signal for nearly all of a minute now raises `ppg_quality_low`
even while the wearer moves (57 poor windows of the last 60, as well as the old 40 while still).
Results and the false-alert cost are in `outputs/fault_injection_1_3_0/results.md`. Same commands, seeds
42-44 and 51-53, and `--poor-fit-extra-s 25` and `70` for the 45 s and 90 s loose-sensor runs.

**Replaying recorded sessions.** The alert logic can be run over a stored recording at full speed, with
no database or broker, to count alerts for a rule change on identical input:

```bash
cd backend
LIVE_REPLAY_TSV=path/to/session.tsv cargo test replay_recorded -- --ignored --nocapture
```

Each line of the TSV is `arrival_ms, green, ir, accel_x, accel_y, accel_z` (export read-only from
`stream_samples`, joining the accelerometer on the packet and sample index as
`scripts/export_ring_session.sh` does). Optional environment variables: `LIVE_REPLAY_DEVICE`
(default `OpenRing`; `OpenEarable` for an earbud), `LIVE_REPLAY_FS` (default 25; 50 for earbuds), a
seventh column with the red channel (earbuds), `LIVE_REPLAY_EVENTS=1` to print each raise and clear
with its time, and `LIVE_REPLAY_CSV=path` to write one line per second (time, sqi, perfusion,
quality, movement state, whether a rate was found). To compare with an older rule, replay the same
files from a copy of the older source: the counts are only comparable on identical input.

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `master` and on pull requests, with four independent
jobs and no secrets:

| Job | Runs |
|---|---|
| `backend` | `cargo test --locked` in `backend/` (Rust toolchain and cargo cache) |
| `frontend` | `npm ci`, `npm test --if-present`, `npm run build` in `frontend/` (Node 20, npm cache) |
| `android` | `./gradlew :app:testDebugUnitTest` (JDK 17, Gradle cache) |
| `python` | the script, head-manifest and processor-contract tests, and the contract validator (Python 3.11, numpy) |

CI does not build Docker images, run the erasure script or the fault-injection script, deploy
anything, or touch any instance. It needs no instance addresses or keys, and none may be added to
it: deployment values come from environment files on the machines that deploy
(`deployment/cloud/deploy.env`, `deployment/local/.env`), never from the repository.

Before opening a pull request, run the offline suites for the parts you touched; see
[DEVELOPER_SETUP.md](DEVELOPER_SETUP.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).

**Session 147 replay (24 Nov 2026).** A 60.9-minute ring recording with a real "Loose ring" label (149 s) was replayed
under 1.2.0 and 1.3.0 the same way: 1.2.0 raised no `ppg_quality_low` (as live), 1.3.0 raised three, one 55 s into the
label (cleared 204 s after it ended) and two others outside it, about 2 false raises per hour. This is the first
recording made after the rule was fixed. See `outputs/fault_injection_1_3_0/session147/results.md`. Note that
`cargo test` in a second source copy reuses the first copy's build unless it has its own `CARGO_TARGET_DIR`.
