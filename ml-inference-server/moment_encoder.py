"""Frozen MOMENT-1-small encoder for 10 s accelerometer windows.

MOMENT (Goswami et al. 2024, AutonLab/MOMENT-1-small, MIT licence) is a T5 encoder over
8-sample patches of a 512-sample series. This module is a small re-implementation of the
`momentfm` package's `embed()` path for one 512-sample channel (reversible instance norm,
patching, linear patch embedding + fixed positional embedding, T5 encoder, mean over patches),
loading the published safetensors. It needs `torch`, `transformers` and `safetensors`, none of
which the PPG worker needs.

Input handling that is specific to this repository lives in `resample_window` and
`window_channels`: a window of accelerometer samples is resampled to exactly 512 samples by
linear interpolation over the sample index (the window is assumed to be uniformly sampled), and
each of the channels (x, y, z, magnitude) is embedded on its own. MOMENT's instance
normalisation removes each channel's mean and scale, so absolute acceleration is *not* in the
embedding; `window_stats` returns the few numbers that are, for use beside it.
"""
from __future__ import annotations

import glob
import json
import os
from typing import Dict, List, Optional

import numpy as np

SEQ_LEN = 512
PATCH_LEN = 8
D_MODEL = 512
CHANNELS = ("x", "y", "z", "mag")

_ENCODER = None


def resample_window(values, n_out: int = SEQ_LEN) -> np.ndarray:
    """Linear interpolation of a (n,) or (n, c) window onto n_out evenly spaced points.

    The first and last samples map to the first and last output points, so a 10 s window of
    250 samples at 25 Hz is stretched by 512/250 (about 2.05x). Raises ValueError for fewer
    than 8 samples or non-finite values."""
    arr = np.asarray(values, dtype=np.float64)
    if arr.ndim == 1:
        arr = arr[:, None]
    if arr.ndim != 2 or arr.shape[0] < 8:
        raise ValueError("need at least 8 samples")
    if not np.all(np.isfinite(arr)):
        raise ValueError("window contains non-finite values")
    src = np.linspace(0.0, 1.0, arr.shape[0])
    dst = np.linspace(0.0, 1.0, n_out)
    out = np.stack([np.interp(dst, src, arr[:, c]) for c in range(arr.shape[1])], axis=1)
    return out.astype(np.float32)


def window_channels(xyz) -> np.ndarray:
    """(n, 3) accelerometer samples -> (4, 512): x, y, z and magnitude, each resampled."""
    arr = np.asarray(xyz, dtype=np.float64)
    if arr.ndim != 2 or arr.shape[1] != 3:
        raise ValueError("expected an (n, 3) array of x, y, z samples")
    mag = np.linalg.norm(arr, axis=1, keepdims=True)
    return resample_window(np.concatenate([arr, mag], axis=1)).T.copy()


STAT_NAMES = ("mag_mean", "mag_std", "dmag_std", "x_std", "y_std", "z_std", "gx", "gy", "gz")


def window_stats(xyz) -> np.ndarray:
    """Information MOMENT's instance normalisation discards, plus a handcrafted baseline's inputs:
    mean magnitude, std of magnitude, std of the first difference of magnitude (a jerk proxy),
    per-axis std, and the unit vector of the mean acceleration (the gravity direction)."""
    arr = np.asarray(xyz, dtype=np.float64)
    mag = np.linalg.norm(arr, axis=1)
    d = np.diff(mag)
    mean = arr.mean(axis=0)
    g = mean / (np.linalg.norm(mean) + 1e-9)
    return np.array([mag.mean(), mag.std(), d.std(), *arr.std(axis=0), *g], dtype=np.float32)


def find_checkpoint(directory: Optional[str] = None) -> str:
    """Directory holding config.json + model.safetensors (MOMENT_DIR, or a HF cache snapshot)."""
    candidates = [directory, os.environ.get("MOMENT_DIR")]
    for base in candidates:
        if not base:
            continue
        if os.path.isfile(os.path.join(base, "model.safetensors")):
            return base
        hits = glob.glob(os.path.join(base, "**", "model.safetensors"), recursive=True)
        if hits:
            return os.path.dirname(hits[0])
    raise FileNotFoundError("MOMENT-1-small weights not found; set MOMENT_DIR (see docs/IMU_MODEL.md)")


class MomentEncoder:
    """embed(channels[c, 512]) -> [c, 512], one embedding per channel."""

    def __init__(self, directory: Optional[str] = None):
        import torch
        from safetensors.torch import load_file
        from transformers import T5Config, T5EncoderModel

        self.torch = torch
        root = find_checkpoint(directory)
        with open(os.path.join(root, "config.json"), encoding="utf-8") as fh:
            cfg = json.load(fh)
        t5 = T5Config.from_dict(cfg["t5_config"])
        encoder = T5EncoderModel(t5).get_encoder()
        state = load_file(os.path.join(root, "model.safetensors"))
        enc_state = {k[len("encoder."):]: v for k, v in state.items() if k.startswith("encoder.")}
        missing, unexpected = encoder.load_state_dict(enc_state, strict=False)
        missing = [k for k in missing if k != "embed_tokens.weight"]
        if missing or unexpected:
            raise RuntimeError(f"MOMENT encoder weights do not match: missing={missing} unexpected={unexpected}")
        self.encoder = encoder.eval()
        self.value_embedding = state["patch_embedding.value_embedding.weight"].float()  # [512, 8]
        self.position = state["patch_embedding.position_embedding.pe"].float()          # [1, 5000, 512]
        for p in self.encoder.parameters():
            p.requires_grad_(False)

    def embed(self, channels: np.ndarray) -> np.ndarray:
        torch = self.torch
        x = torch.as_tensor(np.asarray(channels, dtype=np.float32))
        if x.ndim != 2 or x.shape[1] != SEQ_LEN:
            raise ValueError(f"expected [c, {SEQ_LEN}] input")
        with torch.no_grad():
            mean = x.mean(dim=1, keepdim=True)
            std = x.var(dim=1, unbiased=False, keepdim=True).sqrt() + 1e-5   # RevIN, no affine
            x = torch.nan_to_num((x - mean) / std, nan=0.0, posinf=0.0, neginf=0.0)
            patches = x.unfold(-1, PATCH_LEN, PATCH_LEN)                      # [c, 64, 8]
            tokens = patches @ self.value_embedding.T + self.position[:, : patches.shape[1]]
            out = self.encoder(inputs_embeds=tokens).last_hidden_state        # [c, 64, 512]
            return out.mean(dim=1).cpu().numpy().astype(np.float32)


def get_encoder() -> MomentEncoder:
    global _ENCODER
    if _ENCODER is None:
        _ENCODER = MomentEncoder()
    return _ENCODER


def embed_xyz(xyz, encoder: Optional[MomentEncoder] = None) -> Dict[str, np.ndarray]:
    """Embeddings of an (n, 3) window, keyed by channel name."""
    enc = encoder or get_encoder()
    emb = enc.embed(window_channels(xyz))
    return {name: emb[i] for i, name in enumerate(CHANNELS)}


def feature_vector(names, emb: np.ndarray, stats: np.ndarray) -> np.ndarray:
    """The head's input, in the order its manifest lists `features`.

    `moment:<channel>` is that channel's 512-number embedding (emb has one row per CHANNELS);
    `stat:<name>` / `logstat:<name>` is one window_stats() value, raw or log1p. Training
    (scripts/train_activity_head.py) and the worker both build features through this function."""
    parts: List[np.ndarray] = []
    for name in names:
        kind, _, key = str(name).partition(":")
        if kind == "moment" and key in CHANNELS:
            parts.append(np.asarray(emb[CHANNELS.index(key)], dtype=np.float64))
        elif kind in ("stat", "logstat") and key in STAT_NAMES:
            value = float(stats[STAT_NAMES.index(key)])
            parts.append(np.array([np.log1p(value) if kind == "logstat" else value]))
        else:
            raise ValueError(f"unknown feature {name!r}")
    return np.concatenate(parts).astype(np.float32)
