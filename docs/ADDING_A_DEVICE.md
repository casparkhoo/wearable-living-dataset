# Adding your own Bluetooth (GATT) wearable

This guide takes a Bluetooth Low Energy device you can read with a GATT client and makes
it a first-class source in the dataset: recorded with the other wearables, stored as
evidence with its clocks, exported, and available to live analysis and models.

**What it costs:** one Java class in the Android app and one line to register it. The
class carries a small `DeviceProfile` (names, where it is worn, which streams carry
pulse and movement), so no screen in the app has to know about your device: neither
`MainActivity` nor the device list is edited. The transport, the recording screens, the
database and the console do not change. If your device reports a kind of signal the
backend has no name for yet, add a few lines to the backend too (step 6).

A condensed, ordered version for quick reference is in [AGENTS.md](../AGENTS.md) at the
repository root.

**The worked example** is real code in this repository:
[`AndroidHeartRateDevice.java`](../android-application/android/app/src/main/java/com/wearable/controller/impl/AndroidHeartRateDevice.java)
supports any device with the standard Bluetooth Heart Rate Service (chest straps, arm
bands). It is about 250 lines, most of them plumbing you copy. Read it beside this guide.

## Before you start: what you need to know about the device

Find these out with a GATT browser such as nRF Connect, or from the device's firmware
documentation.

| Question | Heart-rate example |
|---|---|
| How do you recognise it when it advertises? (service UUID, name, or manufacturer ID) | advertises service `0x180D` |
| Which service and characteristic send the data (notify)? | service `0x180D`, characteristic `0x2A37` |
| Does it need a command written to start streaming? Which characteristic, which bytes? | no, it notifies once subscribed |
| What is the byte layout of one notification? | flags byte, 8- or 16-bit rate, optional RR intervals |
| Does a notification carry a timestamp or a counter? In what unit? | neither |
| How many samples per notification, at what rate? | one, about once a second |
| Does it need a larger MTU than the default 23 bytes? | no |

If you cannot answer the layout question from documentation, record notifications with
nRF Connect while doing something known (holding still, tapping the sensor) and work it
out from the bytes. Keep those recordings: they become your tests.

## How a device fits into the app

```
your device ──notify──▶ AndroidBLEService ──▶ YourAdapter.onBleNotification(bytes)
                         (scan, connect,          │  decode, stamp phone time
                          retry, MTU, queue)      ▼
                                              SensorData ──▶ MQTT ──▶ backend ──▶ evidence
```

`AndroidBLEService` owns everything generic: permissions, scanning, connecting,
reconnecting, MTU requests, subscribing, and a queue that sends one GATT write at a time.
Your adapter owns everything specific to the device: how to recognise it, what to
subscribe to, what to write, and how to turn bytes into named values.

An adapter implements two small interfaces and declares one profile:

- `BleDeviceAdapter`: the Bluetooth callbacks (`onBleServicesDiscovered`,
  `onBleMtuChanged`, `onBleNotification`).
- `WearableDevice`: what the rest of the app sees (connect, commands, capabilities, and
  which commands to send when a recording starts and stops).
- `DeviceProfile` (a `public static final PROFILE` field): the names people see, where the
  device is worn, which of its streams carry pulse and movement, its icon, and optional
  behaviour (pairing, several parts, a display). Screens ask the registry for the profile
  of a type instead of comparing type names.

## Step 1: Recognise the device

A static method decides, from an advertisement, whether a device is yours.

```java
public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
    return advertisement.hasService(SERVICE_UUID);
}
```

Prefer a service UUID or manufacturer ID to a name: names are easy to collide with.
`BleAdvertisement` gives you `hasService`, `hasManufacturer` and `getNormalizedName`.
Write 16-bit SIG UUIDs in full on the Bluetooth base UUID
(`0000180d-0000-1000-8000-00805f9b34fb`).

## Step 2: Subscribe when services are discovered

```java
@Override
public void onBleServicesDiscovered() {
    bleService.enableNotifications(address, SERVICE_UUID, MEASUREMENT_UUID);
}
```

Android allows **one GATT operation in flight per connection**; a second request made
before the first completes is silently refused. If your device needs a larger MTU,
request it here and subscribe in `onBleMtuChanged` instead (see
`AndroidOpenRingDevice`). Writes made through `bleService.write` are queued for you.

## Step 3: Decode notifications in a pure function

Keep decoding in a static method that takes bytes and returns values, with no Android
classes, so it can be tested on a laptop.

```java
public static Measurement parseMeasurement(byte[] data) {
    if (data == null || data.length < 2) return null;
    int flags = data[0] & 0xFF;
    ...
}
```

Rules that matter:

- **Check lengths before reading.** Return `null` for a packet shorter than its header
  promises. A wrong value that looks plausible is worse than a dropped packet.
- **Mind signedness and byte order.** Java bytes are signed: mask with `& 0xFF`.
  Most BLE devices are little-endian.
- **Do not convert units you have not verified.** Publish the device's native counts and
  say so; calibration can be applied later as a derived result, and cannot be undone if
  it was applied wrongly at capture.

## Step 4: Publish samples with their timing

In `onBleNotification`, take the phone's time **first**, then decode, then publish one
`SensorData` per sample.

```java
long receivedAtMs = System.currentTimeMillis();          // before anything else
...
SensorData sample = new SensorData(TYPE, address, "HEART_RATE", receivedAtMs);
sample.setData("heart_rate", measurement.heartRateBpm);
sample.setAcquisitionMetadata("androidReceivedTimestampMs", receivedAtMs);
sample.setAcquisitionMetadata("sampleIndex", sampleIndex.getAndIncrement());
sample.setAcquisitionMetadata("clockSource", "arrival_only");
sample.setAcquisitionMetadata("clockQuality", "unvalidated");
mqttManager.publishSensorData(sample, 2);
```

**Channel names.** The backend sorts values into streams by key, so use these names and
the device needs no backend change:

| Stream | Keys |
|---|---|
| `ppg` | `ppg_green`, `ppg_red`, `ppg_ir`, `ppg_ambient` |
| `accel` | `accel_x`, `accel_y`, `accel_z` |
| `gyro` | `gyro_x`, `gyro_y`, `gyro_z` |
| `mag` | `mag_x`, `mag_y`, `mag_z` |
| `temperature` | `temperature` |
| `pressure` | `pressure` |
| `heart_rate` | `heart_rate`, `rr_interval_ms` |

Any other numeric key is still stored, in a stream called `other`, and the raw packet is
always kept whatever its keys. Nothing is lost by using a new name; it is only less
convenient to query until you do step 6.

**Timing metadata.** This is what makes the data usable later.

| Key | Meaning |
|---|---|
| `androidReceivedTimestampMs` | phone time at the callback. Always set it. Labels are matched on this clock. |
| `sampleIndex` | a counter you increment per sample. Lets gaps and duplicates be found, and lets live analysis measure loss. |
| `configuredSampleRateHz` | the rate you asked the device for, if it has one. |
| `sourceTimestamp`, `sourceTimestampUnit` | the device's own timestamp, if it sends one. Store it even if you do not yet know what it means; use a unit such as `unix_ms_unverif` until you have checked it. |
| `clockSource`, `clockQuality` | `arrival_only` / `unvalidated` until you have compared the device's clock with a reference. |

If one notification carries several samples, publish one `SensorData` per sample, each
with its own `sampleIndex`, and record its position in the packet (the ring adapter uses
`framePointIndex`). **Never** spread arrival time across samples to invent per-sample
times; that is a derived result and belongs in analysis, with a recipe.

## Step 5: Say how a recording starts and stops, then register

The app asks each adapter which commands to send when the participant taps *Record*:

```java
@Override
public List<String> getRecordingStartCommands() {
    return Collections.singletonList("START_MEASUREMENT");
}
@Override
public List<String> getRecordingStopCommands() {
    return Collections.singletonList("STOP_MEASUREMENT");
}
```

Each name is passed to your `sendCommand`. For a device that needs bytes written to
start (most do), write them there with `bleService.write(address, SERVICE, TX_CHAR, bytes)`.
For a device that streams as soon as it is subscribed, as here, set a flag and only
forward samples while it is set. The app remembers the start command and sends it again
after a reconnection during a recording.

Declare the profile next to the type, then register the adapter, once, in
`WearableDeviceRegistry.createDefault()`:

```java
public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
        .displayName("Heart-rate strap")
        .shortName("HR strap")
        .pickerLabel("Heart-rate strap  \u00b7  standard Bluetooth heart rate")
        .wearPosition("chest")
        .stream("HEART_RATE", DeviceProfile.StreamRole.PULSE)
        .build();
```

```java
registry.register(AndroidHeartRateDevice.PROFILE,
        AndroidHeartRateDevice::matchesAdvertisement,
        AndroidHeartRateDevice::new);
```

The `TYPE` string is the device's id everywhere (saved on the phone, sent as `device` in
every packet, used in the MQTT topic and the database): keep it unique and never rename it.
`stream(sensorName, roles...)` names the sensor names your `SensorData` objects carry and
what each carries (`PULSE`, `MOVEMENT`, `AUDIO`, `TEMPERATURE`; `OTHER` for any other
scalar). The sensor name must be identical in `SensorData`, `stream(...)` and
`addSensor(...)`; `DeviceProfileTest` checks the last two. The icon key
(`iconKey`) is `ring`, `glasses` or `earbud`; anything else draws a plain dot.

Order matters only when two matchers could claim the same device: the first registered
wins, so put specific adapters before generic ones. The *Add a wearable* list, the scan
filter, automatic reconnection, names and icons, and battery reading (through the standard
Battery Service, when the device has it) all read the registry and need nothing further.

Optional profile features, for devices that need them (see `AndroidEvenGlassesDevice.PROFILE`
and `AndroidOpenRingDevice.PROFILE`):

- `bondBeforeConnect(...)`: the device keeps a connection only with a phone it is paired
  with; the app pairs first and shows your texts.
- `parts(...)`: one wearable that advertises as several Bluetooth devices (left and right
  arm); the app connects and disconnects them together.
- `resumeCommand(...)`: commands to send first after a reconnect during a recording.
- `display(...)`: the wearable shows the live dashboard to its wearer; the factory returns a
  `HeadUpDisplay` that the activity drives without knowing which device it is.
- `firmwareName(...)` and `fixedTitle(...)`: the firmware's name differs from the product
  name people use.

## Step 6 (only for a new kind of signal): name it in the backend

In `backend/src/main.rs`:

- `canonical_stream_samples`: add a `push_stream` call mapping your keys to a stream
  name, channel names and units, and add the keys to `known_keys`.
- `insert_default_sensor_configuration`: add an entry for your device type so each
  recording stores what was asked of the device (streams, rate, settings).

Neither needs a database migration: streams and channels are data, not columns. Add a
unit test beside `heart_rate_service_packet_becomes_its_own_stream`.

Tell the live analysis where the device is worn: add its type to `wear_site` in
`backend/src/live.rs` (finger, ear, chest or other), consistent with `wearPosition` in the
profile (`WearSite` has only finger, ear, chest and other: map wrist or face devices to
other, which uses default limits). That sets the movement limits and the wording participants see ("hand moving"
would be wrong for an earbud).

The console lists device types in `DEVICE_META` in `frontend/src/App.tsx` (colour, short
name). A type that is not listed still works, with a grey default; add an entry for a
nicer label, then run `cd frontend && npm run build`.

Live analysis runs on any `ppg` and `accel` stream automatically, and reports transport
health (stalls, loss when you send `sampleIndex` and a configured rate) for every stream.
Its pulse thresholds were tuned on a finger ring; check them on your device before
trusting its alerts ([EXTENDING.md](EXTENDING.md)).

## Step 7: Test

1. **Decoder, with recorded bytes** (no phone needed). Copy
   `HeartRateMeasurementTest.java`: one test per packet variant, one for truncated
   packets, one that the registry recognises your advertisement.
   `DeviceProfileTest.java` already checks every registered adapter (unique type,
   non-empty names, valid stream roles), so yours is covered once it is registered.
   ```bash
   cd android-application/android && ./gradlew :app:testDebugUnitTest
   ```
   Gradle needs the Android SDK: set `ANDROID_HOME`, or put `sdk.dir=...` in the gitignored
   `android-application/android/local.properties` (a new git worktree does not inherit it);
   see [DEVELOPER_SETUP.md](DEVELOPER_SETUP.md). Service UUID matching in `BleAdvertisement`
   is case-insensitive.
   The adapter class must load in a plain JVM: keep Android classes (`android.util.Log`)
   out of its static fields.
2. **On hardware.** Install the app, add the wearable, start a recording, and check:
   - the device appears under its type and reconnects after being switched off and on;
   - the console's live view shows its streams arriving at the rate you expect;
   - in the experiment's history, raw packets and samples are present and the sample
     index has no gaps;
   - a recording with an existing wearable at the same time still works.
3. **Timing.** Record a few minutes and compare the number of samples with
   duration × rate. If the device sends its own timestamps, compare their span with the
   phone's. Until those agree with a reference, leave the clock marked `unvalidated`:
   the system will keep both clocks and let analysis choose.

## Checklist

- [ ] Matcher uses a service UUID or manufacturer ID where possible
- [ ] One GATT request at a time (MTU, then subscribe)
- [ ] Decoder is a pure static function with length checks
- [ ] Phone time taken first in the callback; `sampleIndex` on every sample
- [ ] Device timestamps and counters kept, marked unverified
- [ ] Native units published; no unverified conversion
- [ ] Recording start/stop commands declared; `PROFILE` filled in; adapter registered
- [ ] No edit to `MainActivity`, the fragments or `AndroidBLEService`
- [ ] Recorded-byte tests, including truncated packets
- [ ] Tried on hardware alone and beside another wearable

## What this does not cover

- **Devices that only sync to a vendor's cloud** (for example the Empatica EmbracePlus)
  cannot be read over GATT by a third-party app. Their data can still be joined to a
  recording afterwards on wall-clock time, as `scripts/score_methods.py` does for the
  reference device.
- **Bonded or encrypted proprietary protocols**: a device that only needs ordinary Android
  bonding is handled by `bondBeforeConnect(...)`. One that needs the vendor's own pairing
  procedure first is device-specific; `AndroidBLEService.createBond` is available.
- **Audio and other high-rate streams** (kilohertz) need batching: publish arrays per
  message with `mqttManager.publishSensorDataBatched`, as the OpenEarable adapter does,
  not one message per sample.
- **iOS.** The app is Android only.
