#!/bin/sh
# DB 백업 (사양서 12장: 하루 1회, 7일 보관 / 9.4: 백업 암호화). 복구 절차는 docs/runbooks/backup.md
# pg_dump(custom 형식)를 openssl AES-256(PBKDF2)로 암호화해 BACKUP_DIR 에 두고, 보관 기간이 지난 파일을 지운다.
# 사용: sh infra/scripts/backup-db.sh
#   필요: infra/.env 의 POSTGRES_USER·POSTGRES_DB·BACKUP_PASSPHRASE (값은 출력하지 않는다)
#   선택: BACKUP_DIR (기본 ~/famlog-backups), BACKUP_RETENTION_DAYS (기본 7)
#   매일: cron 예) 0 3 * * * cd /opt/famlog && sh infra/scripts/backup-db.sh >> /var/log/famlog-backup.log 2>&1
set -eu

INFRA="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$INFRA/.env"
[ -f "$ENV_FILE" ] || { echo "infra/.env 가 없습니다" >&2; exit 1; }

env_value() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
DB_USER=$(env_value POSTGRES_USER)
DB_NAME=$(env_value POSTGRES_DB)
BACKUP_PASSPHRASE=$(env_value BACKUP_PASSPHRASE)
BACKUP_DIR=${BACKUP_DIR:-$(env_value BACKUP_DIR)}
BACKUP_DIR=${BACKUP_DIR:-$HOME/famlog-backups}
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-$(env_value BACKUP_RETENTION_DAYS)}
RETENTION_DAYS=${RETENTION_DAYS:-7}
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
  docker compose -f "$INFRA/docker-compose.yml" --env-file "$ENV_FILE" exec -T postgres \
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
