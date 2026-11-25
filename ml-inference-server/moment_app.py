"""Optional second worker: frozen MOMENT-1-small on 10 s accelerometer windows (pilot).

Same shape as app.py (PaPaGei): `POST /infer` returns the embeddings and, if a head is named,
a head result; `GET /heads`, `GET /health`. It is a separate process on its own port
(MOMENT_PORT, default 5001) because it needs torch and transformers, which the PPG worker does
not. Nothing starts it by default and no compose file includes it.

Request:  {"accel": [[x, y, z], ...], "sample_rate": 25, "window_duration_s": 10,
           "head_id": optional, "device_type": optional, "omit_embedding": optional}
Response: {"embeddings": {"x": [512], ...}, "head_result": {"head": {...},
           "prediction": {"label": ..., "probabilities": [...]}}, "preprocessing": {...}, ...}

Run:  MOMENT_DIR=<folder with MOMENT-1-small> python moment_app.py   (docs/IMU_MODEL.md)
"""
import os
import time

import numpy as np
from flask import Flask, jsonify, request

import moment_encoder as me
from model_heads import IMU_HEADS_DIR, apply_head, available_heads

BASE_MODEL_ID = "moment_1_small"
MIN_FILL = 0.8          # a window with fewer than this fraction of its nominal samples is refused
MAX_SAMPLES = 5000

app = Flask(__name__)
encoder = None
runtime = None


def load_model():
    global encoder, runtime
    encoder = me.MomentEncoder()
    runtime = "torch-cpu"
    print("MOMENT-1-small loaded")


def prepare_window(accel, sample_rate: float, window_duration_s: float):
    """Validate an (n, 3) accelerometer window; returns (array, report). Raises ValueError."""
    arr = np.asarray(accel, dtype=np.float64)
    if arr.ndim != 2 or arr.shape[1] != 3:
        raise ValueError("accel must be a list of [x, y, z] samples")
    if arr.shape[0] > MAX_SAMPLES:
        raise ValueError("window too long")
    if sample_rate <= 0 or window_duration_s <= 0:
        raise ValueError("sample_rate and window_duration_s must be positive")
    expected = sample_rate * window_duration_s
    if arr.shape[0] < MIN_FILL * expected:
        raise ValueError(f"window has {arr.shape[0]} samples, expected about {int(expected)}")
    if not np.all(np.isfinite(arr)):
        raise ValueError("window contains non-finite values")
    return arr, {
        "samples": int(arr.shape[0]),
        "expected_samples": int(round(expected)),
        "resampled_to": me.SEQ_LEN,
        "resampling": "linear interpolation over the sample index; channels x, y, z, magnitude, each normalised by the encoder",
    }


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "model_loaded": encoder is not None, "runtime": runtime,
                    "base_model_id": BASE_MODEL_ID,
                    "available_heads": available_heads(IMU_HEADS_DIR)}), 200


@app.route("/heads", methods=["GET"])
def heads():
    return jsonify({"heads": available_heads(IMU_HEADS_DIR)}), 200


@app.route("/infer", methods=["POST"])
def infer():
    if encoder is None:
        return jsonify({"error": "Model not loaded"}), 500
    try:
        data = request.get_json()
        arr, report = prepare_window(data["accel"], float(data.get("sample_rate", 25)),
                                     float(data.get("window_duration_s", 10)))
        started = time.perf_counter()
        emb = encoder.embed(me.window_channels(arr))
        stats = me.window_stats(arr)
        inference_ms = (time.perf_counter() - started) * 1000
        response = {
            "embeddings": {name: emb[i].tolist() for i, name in enumerate(me.CHANNELS)},
            "embedding_dim": me.D_MODEL,
            "preprocessing": report,
            "output_kind": "embedding",
            "interpretation": "encoder_feature_vector_not_a_prediction",
            "status": "success",
            "runtime": runtime,
            "inference_ms": round(inference_ms, 2),
        }
        head_id = data.get("head_id")
        if head_id:
            manifest_features = _head_features(str(head_id))
            vector = me.feature_vector(manifest_features, emb, stats)
            response["head_result"] = apply_head(str(head_id), vector, base_model_id=BASE_MODEL_ID,
                                                 heads_dir=IMU_HEADS_DIR)
            wanted = response["head_result"]["head"].get("device_types")
            device = data.get("device_type")
            if wanted and device and device not in wanted:
                raise ValueError(f"head was trained on {wanted}, not {device}")
            response["output_kind"] = "prediction"
        if data.get("omit_embedding"):
            response.pop("embeddings")
        return jsonify(response), 200
    except Exception as e:  # same contract as app.py: a 400 with the reason
        return jsonify({"error": str(e), "status": "error"}), 400


def _head_features(head_id: str):
    import json
    import re
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", head_id):
        raise ValueError(f"unknown model head: {head_id}")
    path = IMU_HEADS_DIR / f"{head_id}.json"
    if not path.exists():
        raise ValueError(f"unknown model head: {head_id}")
    features = json.loads(path.read_text(encoding="utf-8")).get("features")
    if not features:
        raise ValueError("head manifest lists no features")
    return features


if __name__ == "__main__":
    load_model()
    app.run(host=os.environ.get("MOMENT_HOST", "127.0.0.1"), port=int(os.environ.get("MOMENT_PORT", "5001")), debug=False)
