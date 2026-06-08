# ── API 방식 (OpenAI 등 외부 API 호출만 할 경우) ──────────
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y gcc && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]


# ── 로컬 모델 방식 (vLLM 사용 시 아래로 교체) ──────────────
# FROM nvidia/cuda:12.1.0-cudnn8-devel-ubuntu22.04
#
# ENV DEBIAN_FRONTEND=noninteractive
# RUN apt-get update && apt-get install -y python3 python3-pip git && \
#     rm -rf /var/lib/apt/lists/*
#
# WORKDIR /app
#
# COPY requirements.txt .
# RUN pip3 install --no-cache-dir -r requirements.txt
#
# COPY app/ ./app/
#
# EXPOSE 8001
#
# CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
