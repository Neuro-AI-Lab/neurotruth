FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y \
    gcc \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/
COPY model/ ./model/

EXPOSE 25991

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "25991"]
