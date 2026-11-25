# Building on the system

The system is meant to be extended without touching the evidence schema. The rule that
makes this work: **evidence is stored as received, and anything computed from it is a
derived result with a method name and version.** A new device adds evidence; a new
algorithm or model adds derived results. Neither should need a migration.

## Add a wearable

[ADDING_A_DEVICE.md](ADDING_A_DEVICE.md) is the full guide, with a worked example
(`AndroidHeartRateDevice.java`, for any device with the standard Bluetooth Heart Rate
Service). In outline:

1. **One adapter class** in `android-application/.../controller/impl/`: recognise the
   device's advertisement, subscribe to its notifying characteristic, decode each
   notification in a pure function, and declare which commands start and stop a recording.
2. **Stamp the phone's receive time first** in the callback, number every sample, and keep
   any timestamp or counter the device sends, marked unverified until checked.
3. **One line** in `WearableDeviceRegistry.createDefault()`.
4. **Use the standard channel names** (`ppg_green`, `accel_x`, …) and the backend needs no
   change; a new kind of signal needs a few lines in `canonical_stream_samples`
   (`backend/src/main.rs`), and no migration. Unknown payloads are stored regardless.
5. **Test the decoder with recorded bytes**, then on hardware, alone and beside another
   wearable.

## Add a model

Models run outside the backend, behind an HTTP worker, so a new model does not change the
backend's language, build or memory budget.

1. **Worker.** `ml-inference-server/app.py` is the pattern: `POST /infer` takes a PPG window
   (`ppg_data`, `sample_rate`, `window_duration_s`), prepares it the way the model expects
   (`preprocessing.py`: band-pass, resample to the model's rate, normalise), runs it, and
   returns the embedding plus an optional head result. Export the model to ONNX
   (`export_onnx.py` shows how) so the worker needs no PyTorch.
2. **Declare the input contract** (rate, window, channels, preprocessing, output) as in
   [reference/PROCESSOR_CONTRACT.md](reference/PROCESSOR_CONTRACT.md). The
   backend never resamples evidence for a model; the worker does, per request.
3. **Add a service** to `deployment/cloud/docker-compose.yml` with a memory limit, and point
   the backend at it.

## Add a task head to PaPaGei

A head is a small linear model on PaPaGei's 512-number embedding, stored as
`ml-inference-server/heads/<id>.json` (manifest) and `<id>.npz` (weights). Set
`ML_LIVE_HEAD_ID=<id>` to run it live; results are stored as derived windows with method
`papagei_s+<id>`, so old and new heads can be compared on the same recordings.
[ADDING_A_MODEL_HEAD.md](ADDING_A_MODEL_HEAD.md) covers training one
(`scripts/train_head_from_rules.py`), the manifest format, registering it, how to evaluate it
honestly, and where an IMU or activity model would plug in.

## Change or add a live analysis method

The Live Analysis Engine is `backend/src/live.rs`; its output is written as derived windows
by `backend/src/derived.rs` under method `live_ppg` and version `RECIPE_VERSION`.

- **Bump `RECIPE_VERSION`** whenever the algorithm's output can change. Stored windows keep
  the version that made them, and the scorer keeps the newest run per method, so a change
  never silently rewrites history.
- **Recompute** old recordings with the new version
  (`wearable-backend recompute <recording id>`) and score both versions against the
  reference ([researcher guide](RESEARCHER_DATA_GUIDE.md)).
- Thresholds were tuned on one wearer's recordings; retune and say so if you change the
  device or population. Where a device is worn matters: `wear_site` in `live.rs` maps a device
  type to finger, ear, chest or other, which sets the movement limits, the wording of
  messages and, for the ear, the choice of pulse channel. Add your device there.
- Alert timing: transport faults raise after 3 windows (a stall at once), clipping and motion
  after 10, signal quality and rate after 20 (30 at the ear). Longer waits mean fewer
  interruptions and slower detection; re-run the fault-injection script after changing them.
- The heart rate participants see is chosen by `LIVE_HR_SOURCE` (PaPaGei or rules). It is
  display only: the rules' estimate is what is stored as `live_ppg` and rebuilt by recompute.
- `scripts/live_fault_injection.py` is the regression test for alerts: all six scripted
  faults should still be detected, with no alerts outside them.

## Change the schema

Prefer not to. If you must: add a migration in `backend/migrations/` that is safe to
re-run (`IF NOT EXISTS`, guarded `ALTER`s), mirror it in `backend/schema.sql` so new
instances get it on first start, and make the backend refuse to start (naming the file)
if it depends on the migration and it is missing. Evidence tables (`raw_packets`,
`stream_samples`) should never gain derived columns.

## Tests

See [TESTING.md](TESTING.md) for every suite, how to run it and what CI runs. Before sending a
change, at least:

```bash
cd backend && cargo test            # unit tests (no database needed)
cd frontend && npm test && npm run build
cd android-application/android && ./gradlew :app:testDebugUnitTest
python -m unittest discover -s scripts/tests && python -m unittest discover -s ml-inference-server/tests
scripts/test_erasure.sh             # if you touched anything that stores or deletes data (needs Docker)
```
