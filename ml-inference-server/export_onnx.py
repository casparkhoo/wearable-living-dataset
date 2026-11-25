"""Convert PaPaGei-S to ONNX so the worker can run without PyTorch.

The exported graph takes one prepared window, shape [batch, 1, 1250] (10 s at
125 Hz, see preprocessing.py), and returns the 512-d embedding. Only the
embedding is exported; the pre-training regression heads are not used by the
pipeline. The script checks the ONNX output against PyTorch before writing.

    python export_onnx.py            # writes weights/papagei_s.onnx
"""
import os
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "papagei"))
from models.resnet import ResNet1DMoE  # noqa: E402

SAMPLES = 1250


class EmbeddingOnly(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        out = self.model(x)
        return out[0] if isinstance(out, (tuple, list)) else out


def load_torch_model():
    model = ResNet1DMoE(in_channels=1, base_filters=32, kernel_size=3, stride=2, groups=1,
                        n_block=18, n_classes=512, n_experts=3)
    state = torch.load(os.path.join(HERE, "weights", "papagei_s.pt"), map_location="cpu")
    state = state.get("state_dict", state) if isinstance(state, dict) else state
    model.load_state_dict({k[7:] if k.startswith("module.") else k: v for k, v in state.items()})
    return EmbeddingOnly(model).eval()


def main():
    out_path = os.path.join(HERE, "weights", "papagei_s.onnx")
    model = load_torch_model()
    example = torch.randn(1, 1, SAMPLES)
    torch.onnx.export(
        model, (example,), out_path,
        input_names=["ppg"], output_names=["embedding"],
        dynamic_axes={"ppg": {0: "batch"}, "embedding": {0: "batch"}},
        opset_version=17, dynamo=False,
    )

    import onnxruntime as ort
    session = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(8):
        x = rng.standard_normal((2, 1, SAMPLES)).astype(np.float32)
        with torch.no_grad():
            ref = model(torch.from_numpy(x)).numpy()
        got = session.run(None, {"ppg": x})[0]
        worst = max(worst, float(np.max(np.abs(ref - got)) / (np.max(np.abs(ref)) + 1e-9)))
    if worst > 1e-3:
        raise SystemExit(f"ONNX output differs from PyTorch (relative {worst:.2e}); not using it")
    print(f"wrote {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB), max relative difference {worst:.1e}")


if __name__ == "__main__":
    main()
