FROM python:3.11-slim

WORKDIR /app

# Default to CPU wheels. For GPU inference, build with:
#   --build-arg TORCH_FLAVOR=cu124
#   --build-arg TORCH_INDEX_URL=https://download.pytorch.org/whl/cu124
ARG TORCH_VERSION=2.6.0
ARG TORCH_FLAVOR=cpu
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu

# scipy/torch wheels are prebuilt, but gcc is kept for packages that may need
# small native extensions during install.
RUN apt-get update && apt-get install -y \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Install dependencies before copying source so Docker can cache this layer.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir --extra-index-url "$TORCH_INDEX_URL" \
        "torch==${TORCH_VERSION}+${TORCH_FLAVOR}"

# These are used by NVIDIA Container Toolkit when the container is started with
# `docker run --gpus all`.
ENV NVIDIA_VISIBLE_DEVICES=all
ENV NVIDIA_DRIVER_CAPABILITIES=compute,utility

# `model/` contains the active checkpoint and the exact training-time code used
# by the inference service.
COPY app/ ./app/
COPY model/ ./model/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
