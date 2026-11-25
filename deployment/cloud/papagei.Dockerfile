# PaPaGei-S worker for the VM: ONNX Runtime on CPU, no PyTorch, so it fits
# beside MySQL and the backend on a 1 GB instance.
FROM python:3.11-slim

WORKDIR /app
COPY ml-inference-server/requirements-onnx.txt ./
RUN pip install --no-cache-dir -r requirements-onnx.txt

COPY ml-inference-server/app.py ml-inference-server/preprocessing.py ml-inference-server/model_heads.py ./
COPY ml-inference-server/heads ./heads
COPY ml-inference-server/weights/papagei_s.onnx ./weights/papagei_s.onnx

ENV PAPAGEI_RUNTIME=onnx PAPAGEI_THREADS=1 PAPAGEI_PORT=5000
EXPOSE 5000
CMD ["python", "app.py"]
