"""Model-input preparation for PPG workers.

This module produces derived model input only; it never modifies wearable evidence.
"""

from dataclasses import dataclass
from typing import Any, Dict, Sequence, Tuple

import numpy as np
from scipy.signal import cheby2, sosfiltfilt


@dataclass(frozen=True)
class PpgWindowConfig:
    source_sample_rate_hz: float
    window_duration_s: float = 10.0
    target_sample_rate_hz: float = 125.0
    normalize: bool = True
    low_cut_hz: float = 0.5
    high_cut_hz: float = 12.0
    filter_order: int = 4

    @property
    def source_sample_count(self) -> int:
        return round(self.source_sample_rate_hz * self.window_duration_s)

    @property
    def target_sample_count(self) -> int:
        return round(self.target_sample_rate_hz * self.window_duration_s)


def prepare_ppg_window(samples: Sequence[float], config: PpgWindowConfig) -> Tuple[np.ndarray, Dict[str, Any]]:
    """Apply the PaPaGei-compatible derived-input pipeline to one complete PPG window."""
    if config.source_sample_rate_hz <= 0:
        raise ValueError("source_sample_rate_hz must be positive")

    raw = np.asarray(samples, dtype=np.float32).reshape(-1)
    required = config.source_sample_count
    if raw.size < required:
        raise ValueError(f"need at least {required} source samples for a {config.window_duration_s:g}s window; received {raw.size}")

    source = raw[-required:]
    if not np.isfinite(source).all():
        raise ValueError("source window contains non-finite values")

    source_std = float(np.std(source))
    source_range = float(np.max(source) - np.min(source))
    flatline = source_std <= max(1e-6, abs(float(np.mean(source))) * 1e-8)
    if flatline:
        raise ValueError("source window is effectively flatline")

    # The reference PaPaGei pipeline uses a fourth-order Chebyshev band-pass
    # (0.5-12 Hz). Clamp the upper cutoff below Nyquist for lower-rate devices.
    nyquist = config.source_sample_rate_hz / 2.0
    high_cut_hz = min(config.high_cut_hz, nyquist * 0.9)
    if not 0 < config.low_cut_hz < high_cut_hz:
        raise ValueError("sample rate is too low for the configured PPG band-pass filter")
    sos = cheby2(config.filter_order, 20, [config.low_cut_hz, high_cut_hz], btype="bandpass", fs=config.source_sample_rate_hz, output="sos")
    pad_length = 3 * (2 * len(sos) + 1)
    if source.size <= pad_length:
        raise ValueError("source window is too short for zero-phase band-pass filtering")
    filtered = sosfiltfilt(sos, source).astype(np.float32)

    source_time = np.arange(required, dtype=np.float64) / config.source_sample_rate_hz
    target_time = np.arange(config.target_sample_count, dtype=np.float64) / config.target_sample_rate_hz
    resampled = np.interp(target_time, source_time, filtered).astype(np.float32)

    processed = resampled
    if config.normalize:
        mean = float(np.mean(processed))
        std = float(np.std(processed))
        if std <= 1e-8:
            raise ValueError("resampled window has zero variance")
        processed = (processed - mean) / std

    report: Dict[str, Any] = {
        "pipeline_id": "papagei_ppg_bandpass_resample_quality_normalize",
        "pipeline_version": "0.2.0",
        "source_sample_rate_hz": config.source_sample_rate_hz,
        "source_sample_count": required,
        "window_duration_s": config.window_duration_s,
        "target_sample_rate_hz": config.target_sample_rate_hz,
        "target_sample_count": config.target_sample_count,
        "normalization": "zscore_per_window" if config.normalize else "none",
        "bandpass_filter": {
            "family": "chebyshev_type_ii",
            "order": config.filter_order,
            "low_cut_hz": config.low_cut_hz,
            "high_cut_hz": high_cut_hz,
            "zero_phase": True,
        },
        "quality": {
            "source_min": float(np.min(source)),
            "source_max": float(np.max(source)),
            "source_std": source_std,
            "source_range": source_range,
            "flatline": flatline,
            "finite": True,
        },
    }
    return processed, report
