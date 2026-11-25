#!/usr/bin/env python3
"""Fault-injection evaluation of the backend's live analysis.

Plays a simulated OpenRing into the backend through the same path a phone
uses (REST to start a recording, MQTT for samples), injects scripted faults at
known times, and listens on the status topics for the alerts the backend
raises. Because every fault's start time is known, the run measures:

* detection: whether each fault raised the alert it should have;
* latency: fault start to alert, on the backend clock and at the subscriber;
* false alerts: alerts raised outside every fault's window;
* heart-rate error during clean segments, against the simulated rate.

Usage (local stack, no auth):
    python scripts/live_fault_injection.py

Usage (cloud, signed in as a participant):
    python scripts/live_fault_injection.py --backend https://api.example \\
        --broker mqtt.example:8883 --tls --mqtt-user u3 --mqtt-password ... \\
        --token "$ACCESS_TOKEN" --topic-prefix sensors/u3 --status-topic "status/u3/#"

Results are written to outputs/live_eval/<timestamp>/ as JSON and CSV.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import ssl
import statistics
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import paho.mqtt.client as mqtt

FS = 25.0
TRUE_BPM = 72.0
DEVICE = "OpenRing"
# Alerts for a fault are attributed to it until this long after it ends: the
# 10 s analysis window still contains faulty samples for that long.
ATTRIBUTION_TAIL_S = 15.0


@dataclass
class Fault:
    name: str
    start_s: float
    end_s: float
    expected: tuple[str, ...]
    description: str


# A clean segment separates every fault so each alert can be attributed and has
# time to clear before the next one starts.
SCHEDULE = [
    Fault("flatline", 20, 35, ("flatline",), "PPG pinned at a constant value"),
    Fault("stall", 50, 56, ("stream_stalled",), "Phone stops publishing for 6 s"),
    Fault("loss", 75, 95, ("sample_loss",), "30% of packets dropped between phone and cloud"),
    Fault("poor_fit", 115, 135, ("ppg_quality_low",), "PPG replaced by noise while still"),
    Fault("motion", 155, 175, ("motion_artifact",), "Wrist moving, PPG corrupted"),
    Fault("saturation", 195, 210, ("saturation",), "PPG clipped at the sensor ceiling"),
]
TOTAL_S = 230


def extend_poor_fit(extra_s: float) -> None:
    """Lengthen the loose-sensor fault and push every later fault back by the same amount."""
    global TOTAL_S
    seen = False
    for fault in SCHEDULE:
        if seen:
            fault.start_s += extra_s
            fault.end_s += extra_s
        elif fault.name == "poor_fit":
            fault.end_s += extra_s
            seen = True
    TOTAL_S += extra_s


def active_fault(t: float) -> Fault | None:
    for fault in SCHEDULE:
        if fault.start_s <= t < fault.end_s:
            return fault
    return None


def pulse(t: float) -> float:
    phase = (t * TRUE_BPM / 60.0) % 1.0
    return math.exp(-(((phase - 0.15) / 0.08) ** 2)) + 0.4 * math.exp(-(((phase - 0.45) / 0.12) ** 2))


def sample_at(t: float, rng: random.Random) -> dict:
    fault = active_fault(t)
    name = fault.name if fault else None
    baseline = 30.0 * math.sin(t * 0.2)
    green = 5000.0 + 200.0 * pulse(t) + baseline + rng.gauss(0, 4)
    ir = 8000.0 + 150.0 * pulse(t) + baseline + rng.gauss(0, 4)
    accel = [0.02 * rng.gauss(0, 1), 0.02 * rng.gauss(0, 1), 9.81 + 0.02 * rng.gauss(0, 1)]

    if name == "flatline":
        green, ir = 5123.0, 8123.0
    elif name == "poor_fit":
        green = 5000.0 + rng.gauss(0, 120)
        ir = 8000.0 + rng.gauss(0, 120)
    elif name == "motion":
        swing = 4.0 * math.sin(2 * math.pi * 1.8 * t)
        accel = [swing + rng.gauss(0, 0.5), 0.5 * swing + rng.gauss(0, 0.5), 9.81 + rng.gauss(0, 1.5)]
        green = 5000.0 + 60.0 * pulse(t) + 250.0 * math.sin(2 * math.pi * 1.8 * t + 0.7) + rng.gauss(0, 80)
        ir = 8000.0 + 60.0 * pulse(t) + 250.0 * math.sin(2 * math.pi * 1.8 * t + 0.7) + rng.gauss(0, 80)
    elif name == "saturation":
        green = min(5000.0 + 2000.0 * pulse(t) + rng.gauss(0, 4), 5600.0)
        ir = min(8000.0 + 2000.0 * pulse(t) + rng.gauss(0, 4), 8600.0)

    return {
        "ppg_green": round(green, 2),
        "ppg_ir": round(ir, 2),
        "accel_x": round(accel[0], 4),
        "accel_y": round(accel[1], 4),
        "accel_z": round(accel[2], 4),
        "temperature": 33.5,
    }


def http(method: str, url: str, token: str | None, body: dict | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read() or b"{}")


@dataclass
class Observed:
    alerts: list[dict] = field(default_factory=list)
    summaries: list[dict] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--broker", default="127.0.0.1:1883")
    parser.add_argument("--tls", action="store_true")
    parser.add_argument("--mqtt-user")
    parser.add_argument("--mqtt-password")
    parser.add_argument("--token", help="Bearer token for the REST API")
    parser.add_argument("--topic-prefix", default="sensors")
    parser.add_argument("--status-topic", default="status/#")
    parser.add_argument("--study-id", type=int)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", default="outputs/live_eval")
    parser.add_argument("--poor-fit-extra-s", type=float, default=0.0,
                        help="lengthen the loose-sensor fault by this many seconds and shift later faults "
                             "(default 0 = the standard schedule; used to time the slower quality alert)")
    args = parser.parse_args()
    if args.poor_fit_extra_s > 0:
        extend_poor_fit(args.poor_fit_extra_s)

    rng = random.Random(args.seed)
    address = f"SIM:{args.seed:02d}:{int(time.time()) % 100000:05d}"
    observed = Observed()

    host, port = args.broker.rsplit(":", 1)
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"live-eval-{address}")
    if args.mqtt_user:
        client.username_pw_set(args.mqtt_user, args.mqtt_password)
    if args.tls:
        client.tls_set(cert_reqs=ssl.CERT_REQUIRED)

    def on_message(_client, _userdata, message):
        received_ms = int(time.time() * 1000)
        try:
            payload = json.loads(message.payload)
        except ValueError:
            return
        event = payload.get("event", "")
        data = payload.get("data") or {}
        with observed.lock:
            if event.startswith("live.alert."):
                if data.get("device_type") == DEVICE:
                    observed.alerts.append({**data, "subscriber_received_ms": received_ms})
            elif event == "live.summary":
                for session in data.get("sessions", []):
                    if session.get("device_address") == address:
                        observed.summaries.append({**session, "subscriber_received_ms": received_ms})

    client.on_message = on_message
    client.connect(host, int(port), keepalive=30)
    client.subscribe(args.status_topic, qos=1)
    client.loop_start()

    print(f"Starting recording for simulated {DEVICE} {address}")
    body = {
        "experiment_label": f"live-eda fault injection {time.strftime('%Y-%m-%d %H:%M:%S')}",
        "protocol_name": "live_fault_injection_v1",
        "client_timestamp_ms": int(time.time() * 1000),
        "device_recordings": [{"device_type": DEVICE, "device_address": address, "session_label": "simulated"}],
    }
    if args.study_id is not None:
        body["study_id"] = args.study_id
    recording = http("POST", f"{args.backend}/api/recordings", args.token, body)
    experiment_id = recording["id"]
    session_id = recording["device_recordings"][0]["id"]

    topic = f"{args.topic_prefix}/{DEVICE}"
    start = time.time()
    run_start_ms = int(start * 1000)
    sample_index = 0
    published = dropped = 0
    last_report = -10
    try:
        while True:
            t = sample_index / FS
            if t >= TOTAL_S:
                break
            target = start + t
            delay = target - time.time()
            if delay > 0:
                time.sleep(delay)
            fault = active_fault(t)
            now_ms = int(time.time() * 1000)
            index = sample_index
            sample_index += 1
            if fault and fault.name == "stall":
                continue
            if fault and fault.name == "loss" and rng.random() < 0.30:
                dropped += 1
                continue
            packet = {
                "schemaVersion": 2,
                "device": DEVICE,
                "address": address,
                "type": "PPG",
                "timestamp": now_ms,
                "serverTimestamp": now_ms,
                "sampleRateHz": FS,
                "timing": {
                    "androidReceivedTimestampMs": now_ms,
                    "configuredSampleRateHz": FS,
                    "sampleIndex": index,
                    "clockSource": "arrival_only",
                    "clockQuality": "unvalidated",
                },
                "data": sample_at(t, rng),
            }
            client.publish(topic, json.dumps(packet), qos=0)
            published += 1
            if t - last_report >= 10:
                last_report = t
                label = fault.name if fault else "clean"
                with observed.lock:
                    alerts = len(observed.alerts)
                print(f"  t={t:5.0f}s  segment={label:<10}  published={published}  alerts={alerts}")
    finally:
        time.sleep(3)
        http("POST", f"{args.backend}/api/recordings/{experiment_id}/stop", args.token,
             {"client_timestamp_ms": int(time.time() * 1000)})
        time.sleep(2)
        client.loop_stop()
        client.disconnect()

    report = evaluate(observed, run_start_ms)
    report["run_start_ms"] = run_start_ms
    report.update({
        "experiment_id": experiment_id,
        "session_id": session_id,
        "device_address": address,
        "seed": args.seed,
        "published": published,
        "dropped_by_design": dropped,
        "schedule": [fault.__dict__ for fault in SCHEDULE],
    })
    write_outputs(Path(args.out), report, observed)


def evaluate(observed: Observed, run_start_ms: int) -> dict:
    raised = [a for a in observed.alerts if a.get("state") == "raised"]

    def t_of(ms: int) -> float:
        return (ms - run_start_ms) / 1000.0

    faults = []
    attributed = set()
    for fault in SCHEDULE:
        window_end = fault.end_s + ATTRIBUTION_TAIL_S
        candidates = [
            a for a in raised
            if a.get("code") in fault.expected and fault.start_s <= t_of(a["detected_at_ms"]) <= window_end
        ]
        first = min(candidates, key=lambda a: a["detected_at_ms"], default=None)
        for alert in raised:
            if fault.start_s <= t_of(alert["detected_at_ms"]) <= window_end:
                attributed.add(id(alert))
        faults.append({
            "fault": fault.name,
            "expected": list(fault.expected),
            "detected": first is not None,
            "detection_latency_s": round(t_of(first["detected_at_ms"]) - fault.start_s, 2) if first else None,
            "delivery_latency_ms": (first["subscriber_received_ms"] - first["detected_at_ms"]) if first else None,
            "other_alerts_during_fault": sorted({
                a["code"] for a in raised
                if a.get("code") not in fault.expected
                and fault.start_s <= t_of(a["detected_at_ms"]) <= window_end
            }),
        })

    false_alerts = [
        {"code": a["code"], "stream": a.get("stream_name"), "t_s": round(t_of(a["detected_at_ms"]), 1)}
        for a in raised if id(a) not in attributed
    ]

    clean_hr = []
    for summary in observed.summaries:
        t = t_of(summary["window_end_ms"])
        window_start = t - 10.0
        overlaps_fault = any(f.start_s < t and window_start < f.end_s for f in SCHEDULE)
        ppg = summary.get("ppg") or {}
        if not overlaps_fault and window_start >= 0 and ppg.get("hr_bpm"):
            clean_hr.append(ppg["hr_bpm"])
    errors = [abs(hr - TRUE_BPM) for hr in clean_hr]

    detected = sum(1 for f in faults if f["detected"])
    return {
        "true_bpm": TRUE_BPM,
        "faults": faults,
        "detected": detected,
        "fault_count": len(faults),
        "false_alerts": false_alerts,
        "heart_rate": {
            "clean_windows": len(clean_hr),
            "mean_abs_error_bpm": round(statistics.fmean(errors), 2) if errors else None,
            "max_abs_error_bpm": round(max(errors), 2) if errors else None,
        },
    }


def write_outputs(root: Path, report: dict, observed: Observed) -> None:
    folder = root / time.strftime("%Y%m%d_%H%M%S")
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "report.json").write_text(json.dumps(report, indent=2))
    with (folder / "alerts.csv").open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["state", "code", "stream", "severity", "detected_at_ms", "subscriber_received_ms", "message"])
        for alert in observed.alerts:
            writer.writerow([alert.get("state"), alert.get("code"), alert.get("stream_name"), alert.get("severity"),
                             alert.get("detected_at_ms"), alert.get("subscriber_received_ms"), alert.get("message")])
    # One row per published summary: the timeline behind the heart-rate and
    # quality figures, with the scripted segment it ended in.
    with (folder / "summaries.csv").open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["t_s", "segment", "hr_bpm", "sqi", "quality", "motion_locked",
                         "movement", "relative_std", "cadence_hz", "active_alerts"])
        for summary in observed.summaries:
            t = (summary["window_end_ms"] - report["run_start_ms"]) / 1000.0
            fault = active_fault(t)
            ppg = summary.get("ppg") or {}
            movement = summary.get("movement") or {}
            writer.writerow([
                round(t, 2), fault.name if fault else "clean", ppg.get("hr_bpm"), ppg.get("sqi"),
                ppg.get("quality"), ppg.get("motion_locked"), movement.get("state"),
                movement.get("relative_std"), movement.get("cadence_hz"),
                ";".join(a["code"] for a in summary.get("active_alerts", [])),
            ])

    print()
    print(f"{'fault':<12}{'expected':<18}{'detected':<10}{'latency s':<11}{'delivery ms':<12}other alerts")
    for fault in report["faults"]:
        print(f"{fault['fault']:<12}{','.join(fault['expected']):<18}{str(fault['detected']):<10}"
              f"{str(fault['detection_latency_s']):<11}{str(fault['delivery_latency_ms']):<12}"
              f"{','.join(fault['other_alerts_during_fault'])}")
    print(f"\nDetected {report['detected']}/{report['fault_count']} faults; "
          f"{len(report['false_alerts'])} false alert(s) {report['false_alerts']}")
    hr = report["heart_rate"]
    print(f"Heart rate over {hr['clean_windows']} clean windows: mean |error| {hr['mean_abs_error_bpm']} bpm, "
          f"max {hr['max_abs_error_bpm']} bpm (true {report['true_bpm']})")
    print(f"Results in {folder}")


if __name__ == "__main__":
    main()
