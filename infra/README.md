# 로컬 개발 인프라

사양서 13장, 20.5 S1 기준. Docker(OrbStack 등)가 필요하다.

| 서비스 | 이미지 | 주소 (127.0.0.1 전용) | 용도 |
|---|---|---|---|
| postgres | `famlog/postgres:16-postgis-pgvector` (`postgres/Dockerfile`에서 빌드) | `localhost:5432` | PostgreSQL 16 + PostGIS + pgvector |
| redis | `redis:7.4-alpine` | `localhost:6379` | 캐시, 요청 제한, Pub/Sub, Streams. 비밀번호 필수 |
| seaweedfs | `chrislusf/seaweedfs:4.53` | S3 API `localhost:8333`, 파일 브라우저 `localhost:8888` | S3 호환 저장소. 버킷 `famlog-media` (비공개) |
| seaweedfs-init | 같은 이미지 | - | 버킷이 없으면 만들고 종료 (여러 번 실행해도 안전) |

> **MinIO 대신 SeaweedFS를 쓰는 이유:** MinIO는 커뮤니티 Docker 이미지 배포를 중단해서 받을 수 없다 (2026-11-13 확인).
> 서버 코드는 AWS SDK의 S3 API만 쓰므로 운영에서는 실제 S3로 엔드포인트만 바꾸면 된다.
> 로컬에서는 **path-style 주소**(`http://localhost:8333/famlog-media/key`)를 쓴다.

## 처음 실행

저장소 루트에서 실행한다.

```bash
cp infra/.env.example infra/.env     # 값을 바꾼다 (아래 한 줄로 무작위 값 생성 가능)
sed -i '' "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 16)/; s/^REDIS_PASSWORD=.*/REDIS_PASSWORD=$(openssl rand -hex 16)/; s/^S3_ACCESS_KEY=.*/S3_ACCESS_KEY=famlog$(openssl rand -hex 6)/; s/^S3_SECRET_KEY=.*/S3_SECRET_KEY=$(openssl rand -hex 20)/" infra/.env

docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build --wait
```

`infra/.env`는 커밋하지 않는다 (`.gitignore`). 커밋하는 것은 `.env.example`뿐이다.

## 자주 쓰는 명령

```bash
DC="docker compose -f infra/docker-compose.yml --env-file infra/.env"

$DC ps                 # 상태 (postgres·redis·seaweedfs가 healthy, seaweedfs-init은 exited 0)
$DC logs -f seaweedfs  # 로그
$DC stop               # 멈춤 (데이터 유지)
$DC down               # 컨테이너 삭제 (데이터 볼륨 유지)
$DC down -v            # ⚠️ 초기화: 데이터 볼륨까지 삭제. 다음 up 때 확장·버킷이 다시 만들어진다
```

`postgres/Dockerfile`이나 `postgres/init/`을 바꿨다면 `up --build`로 다시 빌드한다.
init SQL은 데이터 볼륨이 비어 있을 때만 실행되므로, 바뀐 내용을 반영하려면 `down -v`가 필요하다.

## 설치 확인

```bash
# 1) 확장: postgis, vector 가 보여야 한다
$DC exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\dx"'

# 2) 확장 동작
$DC exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select '"'"'[1,2,3]'"'"'::vector <-> '"'"'[1,2,4]'"'"'::vector, postgis_version();"'

# 3) Redis: 인증하면 PONG, 인증 없으면 NOAUTH
$DC exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping; redis-cli ping'

# 4) S3: 버킷 목록 (인증), 익명 접근은 403
$DC logs --no-log-prefix seaweedfs-init          # bucket created/exists: famlog-media
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8333/famlog-media/   # 403
```

AWS CLI로 보고 싶다면 (설치 없이 컨테이너로):

```bash
set -a; . infra/.env; set +a
docker run --rm --network host -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli:2.27.0 --endpoint-url http://127.0.0.1:8333 s3 ls s3://famlog-media
```

## 서버(Spring)에서 접속할 값 (S2에서 사용)

| 항목 | 로컬 값 |
|---|---|
| JDBC URL | `jdbc:postgresql://localhost:5432/${POSTGRES_DB}` |
| Redis | `localhost:6379`, 비밀번호 `${REDIS_PASSWORD}` |
| S3 endpoint | `http://localhost:8333`, region `us-east-1`(임의), path-style 접근, 버킷 `${S3_BUCKET_MEDIA}` |

## 알아둘 점

- **Presigned URL과 호스트:** 서명에 호스트가 포함된다. 서버가 `localhost:8333`으로 서명한 URL은
  Android 에뮬레이터(호스트 PC = `10.0.2.2`)나 실기기에서 그대로 쓸 수 없다. 앱 연동 단계(S9)에서
  "앱에 내려줄 공개 엔드포인트"를 따로 설정한다.
- DB 시간대는 UTC다 (사양서 10장: `timestamptz` UTC 저장).
- 포트가 이미 쓰이고 있으면 `infra/.env`의 `*_PORT` 값을 바꾼다.
