# PaPaGei ML Inference Server

Flask-based inference server for the PaPaGei PPG foundation model.

## Setup

1. **Clone PaPaGei repository:**
   ```bash
   git clone https://github.com/Nokia-Bell-Labs/papagei-foundation-model.git papagei
   ```

2. **Download model weights:**
   ```bash
   python download_weights.py
   ```
   Or manually download from [Zenodo](https://zenodo.org/records/13983110) and place in `weights/papagei_s.pt`.

3. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

## ONNX runtime (used on the cloud VM)

`python export_onnx.py` converts `weights/papagei_s.pt` to `weights/papagei_s.onnx` and checks
the two agree (max relative difference about 1e-6). When the ONNX file exists the server uses
ONNX Runtime and never imports PyTorch (`PAPAGEI_RUNTIME=auto|onnx|torch`). On one CPU thread a
10 s window takes about 2 ms and the process holds about 160 MB, so it runs beside MySQL and the
backend on the 1 GB VM (`deployment/cloud/papagei.Dockerfile`, `requirements-onnx.txt`).

## Heads

`heads/<id>.json` + `heads/<id>.npz` is a versioned linear head on the 512-d embedding. The
heart-rate head `hr_ring_embraceplus` is written by
`scripts/papagei_posthoc.py --save-head hr_ring_embraceplus`, trained on ring windows labelled
with the EmbracePlus pulse rate. Its manifest records the training recordings and the
leave-one-recording-out score. The backend applies it to live windows when
`ML_INFERENCE_ENABLED=true` and `ML_LIVE_HEAD_ID=hr_ring_embraceplus`.

## Running

On Windows:

```bash
run-server.cmd
```

The launcher creates/uses `venv`, installs dependencies once, downloads weights if missing, and starts `app.py`.

Manual fallback:

```bash
python app.py
```

Server will start on `http://localhost:5000`

## API Endpoints

### `GET /health`
Health check.

**Response:**
```json
{
  "status": "ok",
  "model_loaded": true
}
```

### `POST /infer`
Run inference on PPG data.

**Request:**
```json
{
  "ppg_data": [1.0, 2.5, 3.1, ...],
  "sample_rate": 25
}
```

**Response:**
```json
{
  "embeddings": [0.123, 0.456, ...],
  "status": "success",
  "device": "cuda"
}
```

## Architecture

- **Model:** PaPaGei-S (ResNet1D with MoE)
- **Input:** 1D PPG waveform (expects ~1250 samples at 125 Hz = 10 seconds)
- **Output:** 512-dimensional embedding
- **Device:** GPU (CUDA) if available, falls back to CPU

## TODO

- [ ] Add signal preprocessing (bandpass filtering, resampling)
- [ ] Add windowing support (1s, 5s, 10s windows)
- [ ] Add model selection (papagei_p, papagei_s, papagei_s_svri)
- [ ] Add confidence scores / feature attribution
- [ ] Add request validation and error handling

## Optional: MOMENT accelerometer worker (pilot, off by default)

`moment_app.py` is a second, separate worker (port 5001) running frozen MOMENT-1-small on 10 s
accelerometer windows, with heads in `heads_imu/` (none shipped: the pilot's result was negative).
Install `requirements-moment.txt`, point `MOMENT_DIR` at the downloaded checkpoint and run
`python moment_app.py`. The backend only uses it when `LIVE_ACTIVITY_MODEL` is set. See
[docs/IMU_MODEL.md](../docs/IMU_MODEL.md).
