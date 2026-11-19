# FamLog 워커 (사양서 13.2): Python 3.12 + FFmpeg. 실행 이미지와 테스트 이미지(--target test)를 함께 정의한다.
#   실행: docker compose (infra/docker-compose.yml 의 worker 서비스)
#   테스트: docker build --target test -t famlog-worker-test worker && docker run --rm famlog-worker-test
FROM python:3.12.15-slim-bookworm AS base

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /worker
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY pyproject.toml .
COPY app ./app
COPY prompts ./prompts
RUN useradd --create-home --uid 10001 worker
USER worker

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD ["python", "-m", "app.health"]
CMD ["python", "-m", "app.main"]

FROM base AS test
USER root
COPY requirements-dev.txt .
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY tests ./tests
USER worker
CMD ["python", "-m", "pytest", "-q"]
