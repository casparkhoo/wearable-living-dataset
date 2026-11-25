"""Versioned, opt-in prediction heads for frozen PaPaGei embeddings.

An embedding is never treated as a prediction. A head must be accompanied by a
manifest and learned parameters, which makes its task, label set, provenance,
and output semantics explicit and persistable by the backend.
"""

import json
import re
from pathlib import Path
from typing import Any, Dict, List

import numpy as np


HEADS_DIR = Path(__file__).resolve().parent / "heads"
# Heads for the optional MOMENT accelerometer worker (moment_app.py) live apart, so the
# PaPaGei worker never lists or applies them.
IMU_HEADS_DIR = Path(__file__).resolve().parent / "heads_imu"


def available_heads(heads_dir: Path = HEADS_DIR) -> List[Dict[str, Any]]:
    """Return public metadata for every valid, locally installed head."""
    if not heads_dir.exists():
        return []
    heads: List[Dict[str, Any]] = []
    for manifest_path in sorted(heads_dir.glob("*.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        required = {"head_id", "head_version", "base_model_id", "input_dimension", "output_type", "weights_file"}
        if not required.issubset(manifest):
            continue
        heads.append({key: manifest.get(key) for key in (
            "head_id", "head_version", "display_name", "base_model_id",
            "input_dimension", "output_type", "labels", "task_description",
            "validation_status", "features", "device_types",
        )})
    return heads


def input_sign(head_id: str, heads_dir: Path = None) -> float:
    """-1.0 when the head's manifest sets input_transform.invert to true, else 1.0.

    Some PPG sensors (the ring) report detected light, which falls when blood volume rises, so their pulse
    is upside down relative to the photoplethysmogram PaPaGei was trained on. A head trained on negated
    windows says so in its manifest, and the worker negates its input before preprocessing. Off by default:
    a manifest without the field, or with anything other than a literal true, leaves the input unchanged."""
    heads_dir = HEADS_DIR if heads_dir is None else heads_dir
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", head_id) or ".." in head_id:
        raise ValueError(f"unknown model head: {head_id}")
    manifest_path = heads_dir / f"{head_id}.json"
    if not manifest_path.exists():
        raise ValueError(f"unknown model head: {head_id}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    transform = manifest.get("input_transform") or {}
    return -1.0 if isinstance(transform, dict) and transform.get("invert") is True else 1.0


def head_input(ppg_data: np.ndarray, head_id: str, heads_dir: Path = None) -> np.ndarray:
    """The raw window the worker preprocesses for this head: unchanged (the same array) unless the head inverts."""
    return -ppg_data if input_sign(head_id, heads_dir) < 0 else ppg_data


def apply_head(head_id: str, embedding: np.ndarray, base_model_id: str = "papagei_s",
               heads_dir: Path = HEADS_DIR) -> Dict[str, Any]:
    """Apply a persisted linear head; reject unknown or incompatible heads."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", head_id):
        raise ValueError(f"unknown model head: {head_id}")
    manifest_path = heads_dir / f"{head_id}.json"
    if not manifest_path.exists():
        raise ValueError(f"unknown model head: {head_id}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("base_model_id") != base_model_id:
        raise ValueError(f"head is not compatible with {base_model_id}")
    vector = np.asarray(embedding, dtype=np.float32).reshape(-1)
    if vector.size != int(manifest["input_dimension"]):
        raise ValueError("embedding dimension does not match head manifest")
    weights_path = heads_dir / str(manifest["weights_file"])
    if not weights_path.exists():
        raise ValueError("head weights file is unavailable")
    weights = np.load(weights_path)
    logits = vector @ weights["weights"] + weights["bias"]
    if manifest["output_type"] == "classification":
        probabilities = np.exp(logits - np.max(logits)); probabilities /= probabilities.sum()
        labels = manifest.get("labels", [])
        index = int(np.argmax(probabilities))
        return {"head": manifest, "prediction": {"label": labels[index] if index < len(labels) else str(index), "probabilities": probabilities.tolist()}}
    if manifest["output_type"] == "regression":
        return {"head": manifest, "prediction": {"value": float(np.asarray(logits).reshape(-1)[0]), "unit": manifest.get("unit")}}
    raise ValueError("unsupported model head output_type")
