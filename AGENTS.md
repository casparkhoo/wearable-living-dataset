# Adding an open-source wearable (guide for coding agents and developers)

This file tells you how to add support for one new Bluetooth Low Energy wearable to this
repository, in order, and how to know you are done. It uses the standard Heart Rate
adapter, `AndroidHeartRateDevice` (GATT service `0x180D`), as the worked example. Every
code excerpt below is copied from the files named next to it; if the code and this file
disagree, the code is right, and you should fix this file.

The longer, explanatory version is [docs/ADDING_A_DEVICE.md](docs/ADDING_A_DEVICE.md).

## 1. Scope and ground rules

In scope: a device you can read over BLE GATT with a client such as nRF Connect, whose
firmware or protocol is documented or can be worked out from recorded bytes.

Out of scope: devices that only sync to a vendor cloud, proprietary encrypted protocols
that need a vendor pairing step, iOS. See "What this does not cover" in
[docs/ADDING_A_DEVICE.md](docs/ADDING_A_DEVICE.md).

Rules:

- One new adapter class plus one registry line is the whole Android change. Do not edit
  `MainActivity.java`, `ConnectedDevicesFragment.java` or `AndroidBLEService.java` to add a
  device. If you find you need to, the generic layer is missing something: say so and add
  it to `DeviceProfile` or `WearableDeviceRegistry`, with a test, rather than adding a
  `"MyDevice".equals(type)` branch.
- Publish what the device reported, in its native units. Do not calibrate, resample or
  invent timestamps at capture time. Derived values belong in analysis, with a recipe.
- Take the phone's clock first in the notification callback. Keep any device timestamp or
  counter the device sends, marked unverified.
- Decoding is a pure static function with length checks and a recorded-bytes test.
- Never commit secrets: `.env` files, instance OCIDs, SSH keys, device access keys.
- No `Co-Authored-By` or AI attribution lines in commit messages. Commit locally, ask
  before pushing; pushing `master` deploys the console.
- Say what you tested. The JVM tests do not exercise Bluetooth. A new adapter is
  untested on hardware until someone has run it on a phone with the device.

## 2. Repo map (what you touch, and what you only read)

| Path | What it is | Touch? |
|---|---|---|
| `android-application/android/app/src/main/java/com/wearable/controller/impl/` | One class per wearable (`AndroidHeartRateDevice`, `AndroidOpenRingDevice`, ...) | add yours |
| `.../controller/WearableDeviceRegistry.java` | The registration point: `createDefault()` | one line |
| `.../controller/DeviceProfile.java` | Names, wear position, stream roles, icon, pairing, parts, display | read; extend only if a field is missing |
| `.../controller/WearableDevice.java` | Interface the app sees | read |
| `.../controller/bluetooth/` | `AndroidBLEService` (scan, connect, queue), `BleDeviceAdapter`, `BleAdvertisement` | read |
| `.../controller/MQTTManager.java` | Serialises `SensorData` and publishes it | read |
| `android-application/android/app/src/test/java/com/wearable/controller/` | JVM unit tests | add yours |
| `backend/src/main.rs` | Ingest: `parse_payload`, `canonical_stream_samples`, `insert_default_sensor_configuration` | only for a new kind of signal |
| `backend/src/live.rs` | Live analysis: `wear_site` | one line |
| `frontend/src/App.tsx` | Console: `DEVICE_META` (colour, short name) | optional label |
| `scripts/local.sh` | Run the whole stack locally in Docker | use |
| `docs/ADDING_A_DEVICE.md`, `docs/TESTING.md` | Background | read |

## 3. Steps, in order

### Step 0. Write down what the device does

Answer these before writing code (nRF Connect, datasheet, or recorded notifications):

1. How does it advertise: service UUID, name, manufacturer ID? Prefer a service UUID or
   manufacturer ID; names collide.
2. Which service and characteristic notify? Does anything have to be written first to
   start streaming? Which bytes?
3. Byte layout of one notification. Signed or unsigned, endianness, units.
4. Does it carry a timestamp or a counter? In what unit?
5. Samples per notification, and rate.
6. Is a larger MTU needed than the default 23 bytes?
7. Does it need bonding before it keeps a connection?
8. Is it one Bluetooth device, or several (for example left and right parts)?

Heart Rate example: advertises `0x180D`; notifies `0x2A37`; nothing to write; flags byte,
8- or 16-bit rate, optional RR intervals; no timestamp; one sample about once a second; no
MTU change; no bonding; one device.

UUIDs: the short form `0000xxxx-0000-1000-8000-00805f9b34fb` is only for numbers assigned
by the Bluetooth SIG (like `0x180D`). A made-up or proprietary device must use its own
full 128-bit UUID; never put your own service in the SIG base range.

Save real recorded notifications. They become your test inputs.

### Step 1. The adapter class

Create `impl/AndroidYourDevice.java`. It implements `WearableDevice` (what the app sees)
and `BleDeviceAdapter` (the Bluetooth callbacks). Copy `AndroidHeartRateDevice.java` and
change it; most of the file is plumbing every adapter repeats.

When you copy it, delete what is specific to heart rate: the `Measurement` class and
`parseMeasurement` body (write your own), the `rr_interval_ms` / `rrIntervalsMs` /
`sensorContact` block in `onBleNotification`, the `"HEART_RATE"` and `"heart_rate"` literals
(including `DeviceCapabilities.Builder.addSensor("HEART_RATE")` in the constructor), and
the UUID constants. Keep the plumbing.

**One name, three places.** The sensor name you pass to `new SensorData(TYPE, address,
<sensorName>, ...)`, the name in `PROFILE.stream(<sensorName>, ...)` and the name in
`addSensor(<sensorName>)` must be the same string. `DeviceProfileTest` fails if a profile
stream is not among the adapter's declared sensors; it cannot see the `SensorData` literal,
so check that one by eye.

Header, identifiers and the profile (from `AndroidHeartRateDevice.java`):

```java
public class AndroidHeartRateDevice implements WearableDevice, BleDeviceAdapter {
    public static final String TYPE = "HeartRateMonitor";

    /** How the app names and treats this wearable; see DeviceProfile. */
    public static final DeviceProfile PROFILE = new DeviceProfile.Builder(TYPE)
            .displayName("Heart-rate strap")
            .shortName("HR strap")
            .pickerLabel("Heart-rate strap  ·  standard Bluetooth heart rate")
            .wearPosition("chest")
            .stream("HEART_RATE", DeviceProfile.StreamRole.PULSE)
            .build();
    // 16-bit SIG identifiers written out on the Bluetooth base UUID.
    public static final String SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb";
    public static final String MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb";
```

What the profile fields mean (all in `DeviceProfile.java`):

| Field | Used for |
|---|---|
| `TYPE` (constructor argument) | The id. Unique and stable: it is saved on the phone, sent as `device` in every packet, used as the MQTT topic suffix and stored in the database. Never rename it later. |
| `displayName`, `shortName`, `pickerLabel` | Device list, "Which wearable are you adding?" dialog, dashboard. |
| `firmwareName` | Only when the firmware advertises a name nobody uses; it is replaced by `displayName` in text. |
| `fixedTitle(true)` | List title is always `displayName`, not the advertised name. |
| `wearPosition` | A word for the UI (`finger`, `ear`, `chest`, `face`, `wrist`). The backend's `WearSite` has only Finger, Ear, Chest and Other: `wrist` and `face` map to Other (default limits). See step 5. |
| `stream(sensorName, roles...)` | Which sensor names your samples use and what they carry: `PULSE`, `MOVEMENT`, `AUDIO`, `TEMPERATURE`, `OTHER`. Use `OTHER` for a scalar that is none of the first four. |
| `iconKey` | `ring`, `glasses`, `earbud`; anything else draws a dot. |
| `dashboardPriority`, `dashboardDefault` | Tie-break when several wearables record at once. Leave unset. |
| `resumeCommand(...)` | Commands to send first after a reconnect during a recording. |
| `bondBeforeConnect(...)`, `parts(...)`, `display(...)` | Only for devices that must be bonded first, are made of several parts, or show the live dashboard. See `AndroidEvenGlassesDevice.PROFILE`. |

Recognise the device (same file):

```java
public static boolean matchesAdvertisement(BleAdvertisement advertisement) {
    return advertisement.hasService(SERVICE_UUID);
}
```

Declare the commands the app sends when a recording starts and stops:

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

Each name is passed to your `sendCommand`. A device that needs bytes written to start
streaming writes them there with `bleService.write(...)`. A device that streams as soon as
it is subscribed (like this one) sets a flag and forwards samples only while it is set.
The app remembers start commands and replays them after a reconnect.

Subscribe when services are discovered. Android allows one GATT operation in flight per
connection; a second request before the first completes is silently refused. If you need
a bigger MTU, request it here and subscribe in `onBleMtuChanged` (see
`AndroidOpenRingDevice`).

```java
@Override
public void onBleServicesDiscovered() {
    // One GATT operation at a time: this adapter needs no MTU change, so the
    // subscription is its only request.
    bleService.enableNotifications(address, SERVICE_UUID, MEASUREMENT_UUID);
}
```

### Step 2. The decoder, as a pure function

Put decoding in a static method that takes bytes and returns values, with no Android
classes. Return `null` for a packet shorter than its header promises. From
`AndroidHeartRateDevice.parseMeasurement`:

```java
public static Measurement parseMeasurement(byte[] data) {
    if (data == null || data.length < 2) return null;
    int flags = data[0] & 0xFF;
    int offset = 1;
    int heartRate;
    if ((flags & 0x01) != 0) {
        if (data.length < offset + 2) return null;
        heartRate = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        offset += 2;
    } else {
        heartRate = data[offset] & 0xFF;
        offset += 1;
    }
    Boolean contact = (flags & 0x04) != 0 ? Boolean.valueOf((flags & 0x02) != 0) : null;
    if ((flags & 0x08) != 0) {
        if (data.length < offset + 2) return null;
        offset += 2; // Energy Expended: not used
    }
    List<Double> rr = new ArrayList<>();
    if ((flags & 0x10) != 0) {
        int remaining = data.length - offset;
        if (remaining < 2 || remaining % 2 != 0) return null;
        for (; offset + 1 < data.length; offset += 2) {
            int raw = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            rr.add(raw * 1000.0 / 1024.0);
        }
    }
    return new Measurement(heartRate, contact, rr);
}
```

Rules: check lengths before every read; mask bytes with `& 0xFF`; most BLE data is
little-endian; a wrong plausible value is worse than a dropped packet; do not convert
units you have not verified.

### Step 3. Publish samples: stream names and payload format

In `onBleNotification`, take the phone's time first, decode, then publish one `SensorData`
per sample (from `AndroidHeartRateDevice.java`):

```java
long receivedAtMs = System.currentTimeMillis();
if (!MEASUREMENT_UUID.equalsIgnoreCase(characteristicUuid) || !streaming) return;
Measurement measurement = parseMeasurement(data);
if (measurement == null) return;

SensorData sample = new SensorData(TYPE, address, "HEART_RATE", receivedAtMs);
sample.setData("heart_rate", measurement.heartRateBpm);
...
sample.setAcquisitionMetadata("androidReceivedTimestampMs", receivedAtMs);
sample.setAcquisitionMetadata("sampleIndex", sampleIndex.getAndIncrement());
// The service carries no timestamp: arrival time is the only clock there is.
sample.setAcquisitionMetadata("clockSource", "arrival_only");
sample.setAcquisitionMetadata("clockQuality", "unvalidated");

if (dataListener != null) dataListener.onDataReceived(sample);
if (mqttEnabled && mqttManager != null && mqttManager.isConnected()) {
    mqttManager.publishSensorData(sample, 2);
}
```

`SensorData(deviceType, address, sensorType, timestampMs)`: the third argument is the
sensor name (`"HEART_RATE"`), which must be the name you gave `PROFILE.stream(...)`.
High-rate devices (hundreds of samples per second or audio) call
`mqttManager.publishSensorDataBatched(sample, 2)` instead, as the ring and earable
adapters do.

`MQTTManager.serializeSensorData` turns that into the JSON the backend ingests, published
to topic `<prefix>/<device type>` (for example `sensors/HeartRateMonitor`; the prefix and
broker login come from the signed-in account). One heart-rate sample looks like this
(values illustrative):

```json
{
  "schemaVersion": 2,
  "device": "HeartRateMonitor",
  "address": "AA:BB:CC:DD:EE:FF",
  "type": "HEART_RATE",
  "timestamp": 1731744000123,
  "serverTimestamp": 1731744000130,
  "sampleRateHz": 0,
  "data": { "heart_rate": 64, "rr_interval_ms": 937.5 },
  "timing": {
    "androidReceivedTimestampMs": 1731744000123,
    "sampleIndex": 41,
    "clockSource": "arrival_only",
    "clockQuality": "unvalidated",
    "rrIntervalsMs": [937.5]
  }
}
```

The backend accepts one such object, or a JSON array of them (batched), optionally
gzip-compressed (`parse_payload` in `backend/src/main.rs`).

Channel names decide which stream a value lands in. Use these and the backend needs no
change:

| Stream | Keys in `data` |
|---|---|
| `ppg` | `ppg_green`, `ppg_red`, `ppg_ir`, `ppg_ambient` |
| `accel` | `accel_x`, `accel_y`, `accel_z` |
| `gyro` | `gyro_x`, `gyro_y`, `gyro_z` |
| `mag` | `mag_x`, `mag_y`, `mag_z` |
| `temperature` | `temperature` |
| `pressure` | `pressure` |
| `heart_rate` | `heart_rate`, `rr_interval_ms` |

Any other numeric key is stored in a stream called `other`. The raw packet is always kept.

Timing keys in `timing` (set through `setAcquisitionMetadata`):

| Key | Meaning |
|---|---|
| `androidReceivedTimestampMs` | Phone time at the callback. Always set. Labels are matched on this clock. |
| `sampleIndex` | Counter per sample, so gaps and duplicates can be found. |
| `configuredSampleRateHz` | The rate you asked the device for, if any. Becomes `sampleRateHz`. |
| `sourceTimestamp`, `sourceTimestampUnit` | The device's own timestamp, if it sends one. Keep it, unit `unix_ms_unverif` until checked. |
| `clockSource`, `clockQuality` | `arrival_only` / `unvalidated` until compared with a reference. |

If one notification carries several samples, publish one `SensorData` per sample, each with
its own `sampleIndex`. Never spread arrival time across them.

### Step 4. Register the adapter (one line)

In `WearableDeviceRegistry.createDefault()` (the registry also builds the "Add a wearable"
list, the scan filter, reconnection and the display names from the profile):

```java
// Last, so a device with its own adapter is never claimed by the generic one.
registry.register(AndroidHeartRateDevice.PROFILE,
        AndroidHeartRateDevice::matchesAdvertisement,
        AndroidHeartRateDevice::new);
```

Order matters only when two matchers can claim the same device: the first registered wins,
so put specific adapters before generic ones. Duplicate types are rejected at start-up.

### Step 5. Backend: only if the signal is new

Every device needs three backend edits (the configuration arm, the `wear_site` arm and a
test); a new named stream needs a fourth. A custom scalar key (any numeric key not in the
table above) needs **no** `push_stream` and no `known_keys` entry: it lands in the stream
called `other` automatically. Add them only when you want a named stream with units.

- `canonical_stream_samples` in `backend/src/main.rs` (only for a named stream with units):
  a `push_stream` call mapping your keys to a stream name, channel names and units, and the
  keys added to `known_keys`. Heart rate does it like this:

  ```rust
  push_stream(
      &mut samples,
      "heart_rate",
      &packet.data,
      &[("bpm", "heart_rate"), ("rr_interval_ms", "rr_interval_ms")],
      json!({ "bpm": "bpm", "rr_interval_ms": "ms" }),
  );
  ```

- `insert_default_sensor_configuration` in `backend/src/main.rs`: add a match arm for your
  `TYPE` so each recording stores what was asked of the device. `requested_streams` lists
  backend stream names (`ppg`, `accel`, `gyro`, `heart_rate`, `other`, ...), not the sensor
  names your adapter uses:

  ```rust
  "HeartRateMonitor" => Some((
      "START_MEASUREMENT",
      json!(["heart_rate"]),
      None,
      json!({
          "source": "android_adapter_default",
          "gatt_service": "0x180D",
          "event_driven": true
      }),
  )),
  ```

- `wear_site` in `backend/src/live.rs`: add your type. `WearSite` has only `Finger`, `Ear`,
  `Chest` and `Other`; map a wrist or face device to `Other` (default limits). Add a new
  variant, with a test, only if it needs site-specific movement limits and wording.

  ```rust
  "HeartRateMonitor" => WearSite::Chest,
  ```

- **Backend test (required):** add a `#[cfg(test)]` test beside
  `heart_rate_service_packet_becomes_its_own_stream` that feeds a packet of your device
  through `canonical_stream_samples` and checks the stream names and values.

No database migration is needed: streams and channels are data. Live analysis runs on any
`ppg` and `accel` stream automatically; its pulse thresholds were tuned on a finger ring,
so check them on your device before trusting its alerts ([docs/EXTENDING.md](docs/EXTENDING.md)).
Backend files are shared with other work: keep your edit to these lines.

### Step 6. Console label (optional)

`frontend/src/App.tsx` has `DEVICE_META`, a colour, short name and one-line description per
device type. An unlisted type gets a grey default and the first four letters of its name.
Add an entry if you want better; nothing breaks without it. After editing `App.tsx`, run
`cd frontend && npm run build` (type-check and production build) to confirm it compiles.
Pushing `master` deploys the console, so do not push without being asked.

### Step 7. Tests to write

Android JVM tests, in `android-application/android/app/src/test/java/com/wearable/controller/`:

1. A decoder test with recorded bytes, modelled on `HeartRateMeasurementTest.java`: one
   test per packet variant (each flag combination, 8- and 16-bit values), truncated packets
   returning `null`, odd lengths, unsigned values above 127, empty and one-byte packets, and
   that the registry recognises your advertisement. From `HeartRateMeasurementTest.java`:

   ```java
   HashSet<String> services = new HashSet<>();
   services.add("0000180D-0000-1000-8000-00805F9B34FB");
   WearableDeviceRegistry registry = WearableDeviceRegistry.createDefault();
   assertEquals(AndroidHeartRateDevice.TYPE,
           registry.identify(new BleAdvertisement("Polar H10 1234", services, Collections.emptySet())));
   ```

   `new BleAdvertisement(name, serviceUuids, manufacturerIds)`: the advertised local name,
   the set of advertised service UUIDs, and the set of manufacturer ids (ints). Service UUID
   matching is case-insensitive (both sides are lower-cased), so the upper-case UUID above
   matches the lower-case constant. Test a non-matching advertisement too.

   ```java
   @Test
   public void truncatedPacketsAreRejectedNotGuessed() {
       assertNull(AndroidHeartRateDevice.parseMeasurement(null));
       assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x00)));
       assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x01, 0x2C)));
       assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x10, 60, 0x00)));
       assertNull(AndroidHeartRateDevice.parseMeasurement(bytes(0x08, 60, 0x10)));
   }
   ```

2. `DeviceProfileTest` already checks every registered adapter: unique type, non-empty
   names, valid stream roles, glasses-only display and bonding. It runs on your adapter
   automatically. If your adapter breaks one of its assumptions (for example a second
   `dashboardDefault`), change the test deliberately, not by accident.
3. Backend: a `#[cfg(test)]` test in `backend/src/main.rs` for any new stream mapping.

Run them:

```bash
cd android-application/android && ./gradlew :app:testDebugUnitTest
cd backend && cargo test --locked
```

Gradle needs the Android SDK: set `ANDROID_HOME`, or put `sdk.dir=/path/to/Sdk` in
`android-application/android/local.properties` (gitignored). A new git worktree does not
inherit that file, so create it again there. See [docs/DEVELOPER_SETUP.md](docs/DEVELOPER_SETUP.md).

The adapter class loads in JVM tests only if its static initialisers use no Android API.
Keep `android.util.Log` and friends inside methods, not in static fields.

### Step 8. Validate end to end with the local stack

Needs Docker, and for the phone step an Android phone with developer options and `adb`.

```bash
scripts/local.sh up          # creates settings, builds, starts, prints addresses
scripts/local.sh status      # containers, health and addresses
scripts/local.sh phone       # builds the debug app pointed at this stack, installs it with adb
scripts/local.sh logs backend
```

(`scripts/local.sh admin <email>` makes an account platform administrator after it has
signed in once; see [docs/LOCAL_HOSTING.md](docs/LOCAL_HOSTING.md).)

On the phone: sign in, tap Add a wearable, choose your device's picker line, connect, start
a recording. Check, in this order:

1. The device appears under its display name with a battery level if it reports one, and
   reconnects after you switch it off and on.
2. The console's live view shows your streams arriving at the expected rate.
3. In the experiment history, raw packets and samples exist and `sampleIndex` has no gaps.
4. Record a few minutes; compare the sample count with duration times rate. If the device
   sends its own timestamps, compare their span with the phone's. Leave the clock marked
   `unvalidated` until both agree with a reference.
5. A recording with another wearable at the same time still works.

## 4. Definition of done

- [ ] Matcher uses a service UUID or manufacturer ID where possible
- [ ] `TYPE` is unique, stable, and will never be renamed
- [ ] `PROFILE` has display name, short name, picker label, wear position and stream roles
- [ ] Decoder is a pure static function with length checks and unsigned masking
- [ ] One GATT request at a time (MTU, then subscribe)
- [ ] Phone time taken first; `sampleIndex` on every sample; device clocks kept, unverified
- [ ] Native units published; no unverified conversion
- [ ] Recording start and stop commands declared
- [ ] One `register(...)` line added; no edit to `MainActivity`, the fragments or `AndroidBLEService`
- [ ] Backend: stream mapping (if new), `insert_default_sensor_configuration` arm, `wear_site` arm, test
- [ ] Recorded-byte tests incl. truncated packets; `./gradlew :app:testDebugUnitTest` and `cargo test --locked` pass
- [ ] Tried on hardware alone and beside another wearable, or the pull request says plainly that it was not
- [ ] Sensor name identical in `SensorData`, `PROFILE.stream(...)` and `addSensor(...)`
- [ ] Any temporary or trial edits (test devices, debug flags, local registry lines, `local.properties`
      you created in a worktree) removed or left untracked; `git status` shows only intended files
- [ ] `docs/ADDING_A_DEVICE.md` still true, or updated
- [ ] No secrets, no AI attribution in commits, nothing pushed without being asked

## 5. Pitfalls

- **Naming drift.** The `TYPE` string, the `SensorData` sensor name, the `stream(...)` name
  in the profile, the backend match arms and `wear_site` must agree. A typo gives a device
  the generic profile and no default measurement configuration, with no error.
- **Two GATT requests at once.** The second is dropped silently. Chain them from the
  callbacks (services discovered, MTU changed), not back to back.
- **Signed bytes.** Forgetting `& 0xFF` turns 200 bpm into -56.
- **Plausible wrong data.** Accepting a short packet by treating missing bytes as zero.
  Return `null`.
- **Inventing per-sample times** by dividing the arrival gap. Do not; store the index.
- **Android address changes.** Some devices use a new random address after every restart.
  The app re-matches remembered devices by name and type; give the device a stable
  advertised name if the firmware allows.
- **Matching by name only.** Names collide; the first registered matcher wins, so a greedy
  matcher can steal another adapter's device.
- **Static initialisers that use Android classes** make the adapter impossible to load in a
  JVM test. `DeviceProfileTest` will then fail on every adapter.
- **High rates, one message per sample.** Use `publishSensorDataBatched` above a few
  tens of samples per second.
- **Bonding.** If the device drops a connection within milliseconds, it probably needs
  bonding first; use `bondBeforeConnect(...)` in the profile (see `AndroidEvenGlassesDevice`).
- **Phone clock.** A phone whose clock is wrong breaks TLS and authentication in ways that
  look unrelated. Check the date before debugging the network.
- **Live thresholds.** The pulse and movement thresholds in `backend/src/live.rs` were tuned
  on a ring. Do not trust live alerts for a new wearable until you have looked at its data.
- **Not tested on hardware.** JVM tests cover decoding and registration only. Say so.
