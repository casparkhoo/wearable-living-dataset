# Living Dataset Schema

**Decision date:** 2026-07-05

The living dataset is one logical MySQL evidence schema. It contains experiment
context, one session per device recording, activity labels, sensor configuration,
untouched raw packets, and canonical native-rate samples. It is not a PaPaGei
dataset: PaPaGei, MOMENT, handcrafted features, and future processors consume the
same preserved evidence through the query-time post-processing boundary.

Processed windows, resampled signals, features, embeddings, and CSV exports are
transient outputs. They are accompanied by a reproducibility manifest but are not
inserted back into the evidence database.

## Dataset Shape

The wearable dataset has two persisted layers:

1. **Experiment context and evidence sessions**
   - `experiments` records a labelled research capture and its REST-controlled lifecycle.
   - `devices` records the wearable identity and optional hardware metadata.
   - `sessions` records one evidence period for one device and references its experiment.
   - `annotations` records experiment- or session-level label intervals with client and server timing evidence.
   - `measurement_configs` records automatically what the Android adapter asked the
     device to measure, including requested/effective streams and sample rates. It
     is acquisition provenance, not a user-facing post-processing form.

2. **Raw packets and canonical stream samples**
   - `raw_packets` stores the MQTT/device packet as emitted, including packet type, topic, timestamps, parser status, and full payload.
   - `stream_samples` stores canonical extracted streams such as `ppg`, `accel`, `gyro`, `mag`, `temperature`, or `pressure`.
   - Any raw packet can fan out into several `stream_samples` rows with the same `raw_packet_id`.
   - OpenRing waveform packets commonly fan out into `ppg`, `accel`, `gyro`, and `temperature`.
   - OpenEarable `IMU` packets can fan out into `accel`, `gyro`, and `mag`; `TEMP_BARO` can fan out into `pressure` and `temperature`; `PPG` can map to `ppg`.

Post-processing and export form a third, query-time layer. A request selects source
IDs and time ranges, then declares features, window length, stride, and target sample
rate. The response contains processed rows and a manifest describing those choices.

Experiment-scoped annotations (`session_id IS NULL`) apply to every overlapping
device recording in the experiment. Session-scoped annotations apply only to the
referenced device recording.

## Why This Shape

The thesis question is about trustworthy, synchronized wearable streams for downstream ML. A PPG-only schema would prematurely define the dataset around PaPaGei and make it harder to compare models, stream selections, or preprocessing approaches.

The Android app currently exposes two different acquisition styles:

- **OpenEarable:** the app sends `CONFIGURE_SENSOR` / `START_ALL_SENSORS` commands and receives packets whose sensor ID maps to a sensor group, such as `IMU`, `TEMP_BARO`, or `PPG`.
- **OpenRing:** the app sends `START_MEASUREMENT` and parses bundled waveform responses where the MQTT packet is labeled `PPG` but can contain PPG, accelerometer, gyroscope, and temperature values together.

The schema therefore avoids treating MQTT `type` as the canonical stream identity. `type` is stored on `raw_packets` as packet metadata. Canonical stream identity is derived from channel keys and stored in `stream_samples.stream_name`.

This shape keeps these concerns separate:

- **Measurement intent:** Which streams did the app ask the device to collect?
- **Raw evidence:** What did the devices send?
- **Canonical streams:** Which physiological or motion stream does each extracted channel belong to?

The evidence tables deliberately do not answer these later processing questions:

- **Window definition:** Which time span and streams are being evaluated?
- **Processing choice:** How was the window transformed for a model?
- **Model output:** What did a model produce?
- **Validation:** What ground truth or human judgment supports it?
- **Analysis dataset release:** Which processed windows/model outputs are accepted into a named analysis dataset version?

Those belong in the transient post-processing response and its export manifest,
linked to stable experiment, device-recording, packet, and sample identifiers. The
legacy `wearable_analysis_dataset` is not part of the current MVP architecture.

## Why Windows Stay Separate From Stream Samples

`stream_samples` stores canonical native-rate evidence: what was decoded from the
device packet. A model window is a versioned selection and transformation of that
evidence. It may be gap-checked, filtered, resampled, normalised and overlapped with
another window. Inserting those values back into `stream_samples` would make derived
data look like device evidence and would duplicate samples for every model.

Therefore the living dataset stores native samples in `stream_samples`. The
post-processing engine returns the window definition, source range, transformation
parameters, and generated rows in memory. CSV plus its JSON manifest may be saved by
the user, but neither is written back to MySQL.

## Initial MySQL File

The creation script is `backend/schema.sql`.

The first implementation should use MySQL 8-compatible SQL and environment-based backend configuration. The schema should be treated as a research artifact: if the shape changes, update this file and the development log with the reason.

## Sampling-Rate and Time Normalisation Policy

Different devices may publish different sample rates, packet sizes, clocks, and BLE
delivery patterns. The evidence database must preserve those differences; it must not
pretend that every row is a uniformly timed sample. Normalisation happens only in
query-time post-processing.

### Keep Two Time Axes

Every packet/sample must retain both of these fields:

- **source/device time** (`device_timestamp_ms`): the time supplied by the device, or
  a time reconstructed from a validated device sequence/sample counter.
- **arrival time** (`received_timestamp_ms`): when the Android/backend pipeline
  received the data.

Arrival time is valuable for debugging Bluetooth/MQTT latency, packet bursts, and
outages. It is not a suitable waveform time axis because BLE callbacks can batch
samples. Prefer source/device time for signal processing only after checking that it
is monotonic, has a known unit/epoch, and matches the configured rate. Otherwise
record it as unavailable and use an explicitly marked reconstructed clock.

### Device-Specific Policy

| Device | Current timing evidence | Normalisation rule |
|---|---|---|
| OpenEarable | The adapter parses `deviceTimestamp` from its packet header and may expose `ppg_sample_interval_us`. | Preserve both values. Verify units, wrap behaviour, monotonicity, and the relation to each PPG payload before using device time as the signal clock. Prefer the validated device clock. |
| OpenRing | The current adapter stamps parsed BLE callbacks with `System.currentTimeMillis()` and MQTT labels every PPG/IMU packet as 25 Hz. The waveform payload currently carries no exported device time or sequence number. | Treat callback time as arrival time only. Export a protocol frame/sample sequence number if available. If the ring is validated as fixed-rate, reconstruct a sample clock from a monotonic sample index and the actual configured rate; mark its clock source as `reconstructed_nominal_rate`. Do not use bursty callback spacing as sample spacing. |

The current OpenRing adapter publishes requested-rate metadata and a monotonic sample
index, but the protocol field previously treated as a frame sequence has not been
validated as a device sequence. It is retained only as a
`frameSequenceCandidate`. Experiment 3 showed consecutive sample indices across
2,752 packets, which proves ordering continuity for that recording but not an
absolute device clock or effective 25 Hz cadence. The clock therefore remains
`arrival_only` / `unvalidated`.

### Label and Evidence Clock Domains

Manual activity labels are timestamped on Android. Packets retain Android callback
time and backend receipt time. Those wall clocks are not interchangeable: experiment
3 observed a roughly 2.72-second offset between them. Label overlap should use
`android_received_timestamp_ms`, because it shares the phone clock, or a recorded
clock-offset mapping. Backend receipt time remains valuable for transport diagnostics.

### Required Evidence Metadata

Extend the Android packet contract and backend persistence before analysis to record:

- `configured_sample_rate_hz` and, when reported by hardware, `effective_sample_rate_hz`;
- `device_timestamp` plus its unit/epoch, if supplied by the device;
- `sequence_number` or sample index, including wrap handling;
- `arrival_timestamp_ms` at Android and backend receipt as audit metadata;
- `clock_source`: `device`, `reconstructed_nominal_rate`, or `arrival_only`;
- `clock_quality`: `validated`, `unvalidated`, `gap`, `duplicate`, or `out_of_order`.

`raw_packets` should preserve the original payload. `stream_samples` should preserve
the extracted source/arrival metadata. Query-time post-processing produces a
regularised waveform, embedding, or feature vector and reports its exact
transformation recipe without changing the evidence rows.

### Model-Independent Analysis Pipeline

For every requested model run, create a transient processed window rather than
changing the evidence rows:

1. Select one device/session/stream/channel and use its declared clock policy.
2. Order by source time or validated reconstructed sample index; detect duplicates,
   out-of-order points, and gaps.
3. Split recording segments at a documented gap threshold. Never interpolate across
   a long interruption.
4. Convert channel units/calibration when required.
5. Resample each contiguous segment to the selected model's declared input rate.
6. Apply that model's versioned filter, normalisation, and quality gate.
7. Return the input sample IDs/time range, clock policy, resampling method,
   processing version, and rejection reasons in the reproducibility manifest.

For example, PaPaGei-S can receive a 10-second PPG window resampled to 125 Hz after
these checks. A different model may retain 25 Hz, use accelerometer channels, or
require a different window length. The model manifest—not the acquisition database
or frontend—defines those requirements.
