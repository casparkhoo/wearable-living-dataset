# Android–Backend Control Contract

The system has two network paths:

```text
sensor data:  Android -> MQTT sensors/# -> backend -> living dataset
control:      Android -> REST /api/...  -> backend -> living dataset
status:       Android <- MQTT status/#  <- backend
```

The phone is the capture controller; the backend is authoritative for experiment,
device-recording and activity-label IDs.
MQTT ingestion accepts a device packet only while that device has an active
REST-created device recording.

## Capture Sequence

1. **Start recording** calls `POST /api/recordings` once with the experiment and
   every currently connected wearable. The backend creates all records in one
   database transaction. It also records the Android adapter's automatic requested
   sensor configuration; the user is not asked for post-processing parameters.
2. Wearable measurements publish packets over `sensors/#`.
3. `POST /api/experiments/{id}/annotations` starts an activity label and
   `POST /api/annotations/{id}/stop` ends it.
4. **End recording** calls `POST /api/recordings/{id}/stop`, which closes the
   experiment, all remaining device recordings, and any active activity label.

The lower-level legacy routes remain available for compatibility, but ordinary app
users do not manage experiment and device-recording lifecycles separately.

An annotation with `session_id = null` has experiment scope: it applies to every
device recording in that experiment whose evidence overlaps the label interval.
A non-null `session_id` restricts the label to that device recording.

`GET /api/experiments/active` returns the active experiment, device-recording IDs
and activity-label IDs so the Android UI can recover after an activity/app restart.

Android stores the selected REST base URL and MQTT broker in app-private preferences.
On the next launch it restores both values, recovers the active REST control state,
and reconnects to the saved MQTT broker automatically. Clearing the app's storage or
uninstalling it clears these saved connection settings.

## Experiment History

Both Android and the desktop frontend use the same read-only history endpoints:

- `GET /api/experiments` lists experiments grouped at experiment level.
- `GET /api/experiments/{id}/details?limit=100` returns experiment metadata, device
  recordings, automatic sensor configurations, activity labels, and a bounded
  preview of the latest raw packets and native stream samples.

The evidence limit is clamped to 1–500 rows for each evidence type. These endpoints
only read preserved evidence; expanding an experiment does not run post-processing
or write anything to the database.

## Minimal Requests

Start an experiment and its device recordings:

```json
{
  "experiment_label": "seated desk session",
  "protocol_name": "desk-context-v1",
  "client_timestamp_ms": 1784510000000,
  "device_recordings": [
    {
      "device_type": "OpenRing",
      "device_address": "AA:BB:CC:DD:EE:FF",
      "session_label": "left hand"
    }
  ]
}
```

Only `experiment_label`, `client_timestamp_ms`, and at least one device recording are
needed for the normal phone workflow. `protocol_name` is nullable metadata for a
named, versioned study procedure; `session_label` is nullable metadata for cases such
as body location or two devices of the same type. Neither field is a
post-processing setting, and neither should block a technical capture.

Start an activity label:

```json
{
  "label_name": "activity",
  "label_value": "seated at desk",
  "started_at_ms": 1784510010000,
  "client_timestamp_ms": 1784510010000,
  "client_monotonic_ns": 1534439211000,
  "source": "manual_app"
}
```

The backend additionally records its receipt time. These are different clock domains:
experiment 3 showed the phone clock approximately 2.72 seconds ahead of backend
receipt time. Interval labels must be matched to `android_received_timestamp_ms` or
through an explicit estimated offset; they must not be compared directly with backend
receipt time as though the clocks were identical.

## MQTT Status

Lifecycle events are JSON messages under:

- `status/recordings/{experiment_id}`
- legacy device-recording events: `status/evidence-sessions/{session_id}`
- activity-label events: `status/annotations/{annotation_id}`

Experiment and device-recording state messages are retained. Activity-label events
are transient. Sensor packets remain under `sensors/#` and are not mixed with control
events.

## Living-Dataset Boundary

One schema contains experiments, device recordings, activity labels and native
evidence. It intentionally uses multiple tables:

- `raw_packets` and `stream_samples` are immutable/native evidence;
- windowing, feature selection and resampling run when queried;
- CSV and model-ready outputs are returned with a manifest and are not inserted
  back into the evidence schema;
- PaPaGei is a consumer of exported evidence, not a core schema table.
