# PostgreSQL 16 + PostGIS 3 + pgvector (사양서 10장, 13.2)
# 공식 postgis/postgis 이미지에는 pgvector가 없어서, 멀티 아키텍처(arm64/amd64)인 공식 postgres 이미지에
# PGDG 저장소 패키지로 두 확장을 설치한다. Testcontainers(S2)도 이 이미지를 같이 쓴다.
FROM postgres:16-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-16-postgis-3 \
        postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*

COPY init/ /docker-entrypoint-initdb.d/
