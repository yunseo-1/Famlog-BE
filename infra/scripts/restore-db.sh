#!/bin/sh
# DB 복구 (docs/runbooks/backup.md). 암호화된 백업을 풀어 pg_restore 한다.
# 사용:
#   sh infra/scripts/restore-db.sh <백업 파일> [대상 DB]
#     대상 DB 를 주지 않으면 famlog_restore_<시각> 새 DB 에 복구한다 (리허설·확인용, 운영 DB 는 건드리지 않는다)
#   sh infra/scripts/restore-db.sh <백업 파일> --into-main
#     운영 DB(POSTGRES_DB)를 지우고 백업으로 바꾼다. API 서버·워커를 먼저 멈춘다. 확인을 위해 DB 이름을 입력받는다
# 복구 뒤 할 일(삭제 요청 다시 적용 등)은 runbook 을 따른다.
set -eu

INFRA="$(cd "$(dirname "$0")/.." && pwd)"
# 운영: FAMLOG_ENV_FILE=/opt/famlog/.env FAMLOG_COMPOSE_FILE=infra/prod/docker-compose.yml (infra/prod/scripts 가 넣는다)
ENV_FILE=${FAMLOG_ENV_FILE:-$INFRA/.env}
COMPOSE_FILE_PATH=${FAMLOG_COMPOSE_FILE:-$INFRA/docker-compose.yml}
FILE="${1:-}"
TARGET="${2:-}"
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 가 없습니다" >&2; exit 1; }
[ -n "$FILE" ] && [ -f "$FILE" ] || { echo "사용: sh infra/scripts/restore-db.sh <백업 파일> [대상 DB | --into-main]" >&2; exit 1; }

env_value() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
DB_USER=$(env_value POSTGRES_USER)
MAIN_DB=$(env_value POSTGRES_DB)
BACKUP_PASSPHRASE=$(env_value BACKUP_PASSPHRASE)
[ -n "$BACKUP_PASSPHRASE" ] || { echo "infra/.env 에 BACKUP_PASSPHRASE 가 없습니다" >&2; exit 1; }
export BACKUP_PASSPHRASE

if [ -f "$FILE.sha256" ]; then
  (cd "$(dirname "$FILE")" && sha256 -c "$(basename "$FILE").sha256" >/dev/null) \
    || { echo "체크섬이 맞지 않습니다: $FILE" >&2; exit 1; }
fi

psql() {
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" exec -T postgres psql -U "$DB_USER" "$@"
}

if [ "$TARGET" = "--into-main" ]; then
  printf '운영 DB(%s)를 백업으로 바꿉니다. API 서버·워커를 멈췄나요? 계속하려면 DB 이름을 입력하세요: ' "$MAIN_DB"
  read -r answer
  [ "$answer" = "$MAIN_DB" ] || { echo "취소했습니다" >&2; exit 1; }
  TARGET="$MAIN_DB"
  psql -d postgres -q -c "DROP DATABASE \"$TARGET\" WITH (FORCE)"
elif [ -z "$TARGET" ]; then
  TARGET="famlog_restore_$(date -u +%Y%m%d%H%M%S)"
fi

psql -d postgres -q -c "CREATE DATABASE \"$TARGET\""
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in "$FILE" \
  | docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" exec -T postgres \
      pg_restore -U "$DB_USER" -d "$TARGET" --no-owner --exit-on-error
echo "복구 완료: DB $TARGET"
psql -d "$TARGET" -At -c "SELECT 'flyway ' || max(installed_rank) || ', users ' || (SELECT count(*) FROM users)
  || ', messages ' || (SELECT count(*) FROM messages) FROM flyway_schema_history"
echo "다음: docs/runbooks/backup.md 의 '복구 뒤 할 일'"
