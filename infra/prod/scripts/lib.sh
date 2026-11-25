# 운영 스크립트 공통 (sh 로 . 해서 쓴다). 값은 출력하지 않는다.
#   FAMLOG_HOME (기본 /opt/famlog): .env·secrets/·backups/ 가 있는 곳
#   FAMLOG_ENV_FILE (기본 $FAMLOG_HOME/.env)
# SSM 명령은 HOME·PATH 가 비어 있을 수 있다 (docker·git 설정, snap 으로 깐 aws)
export HOME="${HOME:-/root}" PATH="$PATH:/usr/local/bin:/snap/bin"
PROD_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_DIR="$(cd "$PROD_DIR/../.." && pwd)"
FAMLOG_HOME=${FAMLOG_HOME:-/opt/famlog}
ENV_FILE=${FAMLOG_ENV_FILE:-$FAMLOG_HOME/.env}
COMPOSE_FILE_PATH="$PROD_DIR/docker-compose.yml"
export FAMLOG_ENV_FILE="$ENV_FILE" FAMLOG_COMPOSE_FILE="$COMPOSE_FILE_PATH"

fail() { echo "$*" >&2; exit 1; }
env_value() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }

# .env 의 한 줄을 바꾼다 (없으면 붙인다). 파일 권한은 그대로 둔다
set_env() {
  _tmp=$(mktemp)
  grep -v "^$1=" "$ENV_FILE" > "$_tmp" || true
  echo "$1=$2" >> "$_tmp"
  cat "$_tmp" > "$ENV_FILE"
  rm -f "$_tmp"
}

compose() { docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" "$@"; }
