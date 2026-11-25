import os
import sys
import time

import numpy as np
from flask import Flask, request, jsonify
from flask_cors import CORS

from preprocessing import PpgWindowConfig, prepare_ppg_window
from model_heads import apply_head, available_heads, head_input

HERE = os.path.dirname(os.path.abspath(__file__))
ONNX_PATH = os.path.join(HERE, 'weights', 'papagei_s.onnx')
TORCH_PATH = os.path.join(HERE, 'weights', 'papagei_s.pt')

app = Flask(__name__)
CORS(app, resources={r"/*": {"origins": ["http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:3000", "http://127.0.0.1:3000"]}})

# One callable, embed(window[1250]) -> embedding[512], whichever runtime loaded it.
embed = None
runtime = None


def load_onnx():
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = int(os.environ.get('PAPAGEI_THREADS', '1'))
    session = ort.InferenceSession(ONNX_PATH, options, providers=['CPUExecutionProvider'])

    def run(window):
        return session.run(None, {'ppg': window.reshape(1, 1, -1).astype(np.float32)})[0].reshape(-1)
    return run


def load_torch():
    import torch
    sys.path.insert(0, os.path.join(HERE, 'papagei'))
    from models.resnet import ResNet1DMoE

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = ResNet1DMoE(in_channels=1, base_filters=32, kernel_size=3, stride=2, groups=1,
                        n_block=18, n_classes=512, n_experts=3)
    if not os.path.exists(TORCH_PATH):
        raise FileNotFoundError(f"Model weights not found at {TORCH_PATH}")
    checkpoint = torch.load(TORCH_PATH, map_location=device)
    state_dict = checkpoint.get('state_dict', checkpoint) if isinstance(checkpoint, dict) else checkpoint
    model.load_state_dict({k[7:] if k.startswith('module.') else k: v for k, v in state_dict.items()})
    model.to(device).eval()

    def run(window):
        with torch.no_grad():
            out = model(torch.tensor(window).float().reshape(1, 1, -1).to(device))
        out = out[0] if isinstance(out, (tuple, list)) else out
        return out.detach().cpu().reshape(-1).numpy()
    return run, f"torch-{device}"


def load_model():
    """Prefer the ONNX export (no PyTorch needed); fall back to the PyTorch checkpoint."""
    global embed, runtime
    wanted = os.environ.get('PAPAGEI_RUNTIME', 'auto')
    if wanted in ('auto', 'onnx') and os.path.exists(ONNX_PATH):
        try:
            embed, runtime = load_onnx(), 'onnxruntime-cpu'
        except ImportError:
            if wanted == 'onnx':
                raise
    if embed is None:
        embed, runtime = load_torch()
    print(f"PaPaGei-S loaded ({runtime})")


@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status': 'ok', 'model_loaded': embed is not None, 'runtime': runtime,
                    'available_heads': available_heads()}), 200


@app.route('/heads', methods=['GET'])
def heads():
    """List installed, versioned task heads."""
    return jsonify({'heads': available_heads()}), 200


@app.route('/infer', methods=['POST'])
def infer():
    """
    Run PaPaGei-S on one PPG window.

    Request:  {"ppg_data": [float], "sample_rate": 25, "window_duration_s": 10, "head_id": optional}
    Response: {"embeddings": [512 floats], "preprocessing": {...}, "head_result": optional, ...}
    """
    if embed is None:
        return jsonify({'error': 'Model not loaded'}), 500

    try:
        data = request.get_json()
        ppg_data = np.array(data['ppg_data'], dtype=np.float32)
        head_id = data.get('head_id')
        if head_id:
            # Negated only for a head whose manifest sets input_transform.invert (off by default). Live
            # windows and backend recompute both arrive here with head_id, so they stay consistent.
            ppg_data = head_input(ppg_data, str(head_id))
        config = PpgWindowConfig(
            source_sample_rate_hz=float(data.get('sample_rate', 25)),
            window_duration_s=float(data.get('window_duration_s', 10)),
            target_sample_rate_hz=125.0,
            normalize=True,
        )
        prepared_ppg, preprocessing_report = prepare_ppg_window(ppg_data, config)

        started = time.perf_counter()
        embedding = np.asarray(embed(prepared_ppg), dtype=np.float32).reshape(-1)
        inference_ms = (time.perf_counter() - started) * 1000

        response = {
            'embeddings': embedding.tolist(),
            'embedding_dim': int(embedding.size),
            'preprocessing': preprocessing_report,
            'output_kind': 'embedding',
            'interpretation': 'encoder_feature_vector_not_a_prediction',
            'status': 'success',
            'runtime': runtime,
            'inference_ms': round(inference_ms, 2),
        }
        if head_id:
            response['head_result'] = apply_head(str(head_id), embedding)
            response['output_kind'] = 'prediction'
        if data.get('omit_embedding'):
            response.pop('embeddings')
        return jsonify(response), 200

    except Exception as e:
        return jsonify({'error': str(e), 'status': 'error'}), 400


if __name__ == '__main__':
    load_model()
    app.run(host=os.environ.get('PAPAGEI_HOST', '0.0.0.0'), port=int(os.environ.get('PAPAGEI_PORT', '5000')), debug=False)
