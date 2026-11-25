#!/bin/sh
# DB 백업 (사양서 12장: 하루 1회, 7일 보관 / 9.4: 백업 암호화). 복구 절차는 docs/runbooks/backup.md
# pg_dump(custom 형식)를 openssl AES-256(PBKDF2)로 암호화해 BACKUP_DIR 에 두고, 보관 기간이 지난 파일을 지운다.
# 사용: sh infra/scripts/backup-db.sh
#   필요: infra/.env 의 POSTGRES_USER·POSTGRES_DB·BACKUP_PASSPHRASE (값은 출력하지 않는다)
#   선택: BACKUP_DIR (기본 ~/famlog-backups), BACKUP_RETENTION_DAYS (기본 7)
#         BACKUP_S3_URI (예: s3://famlog-backup-xxx/db): 서버 밖 사본. aws CLI·인스턴스 역할 필요, 버킷 수명 규칙으로 지운다
#         BACKUP_METRICS_DIR: 성공 시각을 Prometheus 형식으로 남긴다 (운영 node-exporter → BackupStale 알림)
#   매일: cron 예) 0 3 * * * cd /opt/famlog && sh infra/scripts/backup-db.sh >> /var/log/famlog-backup.log 2>&1
set -eu

INFRA="$(cd "$(dirname "$0")/.." && pwd)"
# 운영: FAMLOG_ENV_FILE=/opt/famlog/.env FAMLOG_COMPOSE_FILE=infra/prod/docker-compose.yml (infra/prod/scripts 가 넣는다)
ENV_FILE=${FAMLOG_ENV_FILE:-$INFRA/.env}
COMPOSE_FILE_PATH=${FAMLOG_COMPOSE_FILE:-$INFRA/docker-compose.yml}
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 가 없습니다" >&2; exit 1; }

env_value() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
DB_USER=$(env_value POSTGRES_USER)
DB_NAME=$(env_value POSTGRES_DB)
BACKUP_PASSPHRASE=$(env_value BACKUP_PASSPHRASE)
BACKUP_DIR=${BACKUP_DIR:-$(env_value BACKUP_DIR)}
BACKUP_DIR=${BACKUP_DIR:-$HOME/famlog-backups}
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-$(env_value BACKUP_RETENTION_DAYS)}
RETENTION_DAYS=${RETENTION_DAYS:-7}
BACKUP_S3_URI=${BACKUP_S3_URI:-$(env_value BACKUP_S3_URI)}
BACKUP_METRICS_DIR=${BACKUP_METRICS_DIR:-$(env_value BACKUP_METRICS_DIR)}
[ -n "$BACKUP_PASSPHRASE" ] || { echo "infra/.env 에 BACKUP_PASSPHRASE 가 없습니다 (openssl rand -hex 32)" >&2; exit 1; }
export BACKUP_PASSPHRASE

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$BACKUP_DIR/famlog-$STAMP.dump.enc"
PARTIAL="$OUT.partial"

# 덤프 → 암호화. 실패하면 만들다 만 파일을 지운다 (pipefail 이 없는 sh 라 두 단계 결과를 따로 확인)
STATUS_FILE=$(mktemp)
trap 'rm -f "$PARTIAL" "$STATUS_FILE"' EXIT
{
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" exec -T postgres \
    pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc --no-owner || echo "pg_dump" > "$STATUS_FILE"
} | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_PASSPHRASE -out "$PARTIAL"
[ ! -s "$STATUS_FILE" ] || { echo "백업 실패: pg_dump" >&2; exit 1; }
[ -s "$PARTIAL" ] || { echo "백업 실패: 빈 파일" >&2; exit 1; }
chmod 600 "$PARTIAL"
mv "$PARTIAL" "$OUT"
(cd "$BACKUP_DIR" && sha256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")

# 보관 기간이 지난 백업 삭제 (사양서 8.9: 백업에 남은 데이터는 보관 기간이 지나면 지워진다)
find "$BACKUP_DIR" -name 'famlog-*.dump.enc*' -type f -mtime +"$((RETENTION_DAYS - 1))" -exec rm -f {} +

SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo "백업 완료: $(basename "$OUT") (${SIZE} bytes), 보관 ${RETENTION_DAYS}일, 위치 $BACKUP_DIR"

# 서버 밖 사본 (암호화된 파일 그대로). 실패하면 cron 로그에 남도록 0 이 아닌 값으로 끝낸다
if [ -n "$BACKUP_S3_URI" ]; then
  aws s3 cp --only-show-errors "$OUT" "$BACKUP_S3_URI/$(basename "$OUT")"
  aws s3 cp --only-show-errors "$OUT.sha256" "$BACKUP_S3_URI/$(basename "$OUT").sha256"
  echo "서버 밖 사본 올림: $BACKUP_S3_URI/$(basename "$OUT")"
fi

# 성공 시각 (서버 밖 사본까지 끝난 뒤). 임시 파일로 쓰고 옮겨 반쯤 쓴 파일을 읽지 않게 한다
if [ -n "$BACKUP_METRICS_DIR" ]; then
  mkdir -p "$BACKUP_METRICS_DIR"
  printf '# HELP famlog_backup_last_success_timestamp_seconds 마지막 DB 백업 성공 시각\n# TYPE famlog_backup_last_success_timestamp_seconds gauge\nfamlog_backup_last_success_timestamp_seconds %s\n' \
    "$(date +%s)" > "$BACKUP_METRICS_DIR/backup.prom.tmp"
  chmod 644 "$BACKUP_METRICS_DIR/backup.prom.tmp"
  mv "$BACKUP_METRICS_DIR/backup.prom.tmp" "$BACKUP_METRICS_DIR/backup.prom"
fi
